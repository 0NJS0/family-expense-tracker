"""deploy/backup/backup.sh with stub pg_dump/pg_restore/date: retention, monthly
copies, checksums, no half-written backups, and the daily schedule maths."""

import os
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "deploy" / "backup" / "backup.sh"

STUBS = {
    "pg_dump": """#!/bin/sh
[ -n "${FAIL_DUMP:-}" ] && exit 1
while [ $# -gt 0 ]; do [ "$1" = "--file" ] && { echo "dump of $FAKE_NOW" > "$2"; }; shift; done
""",
    "pg_restore": """#!/bin/sh
[ -n "${FAIL_RESTORE:-}" ] && exit 1
exit 0
""",
    # Prints FAKE_NOW ("YYYY-mm-dd HH:MM:SS") in the requested +FORMAT.
    "date": """#!/usr/bin/env python3
import os, sys
from datetime import datetime
now = datetime.strptime(os.environ["FAKE_NOW"], "%Y-%m-%d %H:%M:%S")
fmt = next((a[1:] for a in sys.argv[1:] if a.startswith("+")), "%a %b %d %H:%M:%S %Y")
print(now.strftime(fmt))
""",
}


@pytest.fixture
def env(tmp_path: Path) -> dict[str, str]:
    stubs = tmp_path / "stubs"
    stubs.mkdir()
    for name, body in STUBS.items():
        path = stubs / name
        path.write_text(body)
        path.chmod(0o755)
    receipts = tmp_path / "receipts" / "hh"
    receipts.mkdir(parents=True)
    (receipts / "r1.jpg").write_bytes(b"jpeg")
    return {
        "PATH": f"{stubs}:{os.environ['PATH']}",
        "BACKUPS": str(tmp_path / "backups"),
        "RECEIPTS": str(tmp_path / "receipts"),
        "BACKUP_DATABASE_URL": "postgresql://fet_app@db/fet_prod",
        "BACKUP_KEEP_DAILY": "3",
        "BACKUP_KEEP_MONTHLY": "2",
    }


def run(
    env: dict[str, str], now: datetime, *args: str, **extra: str
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [os.environ.get("FET_TEST_SH", "sh"), str(SCRIPT), *args],
        env={**env, "FAKE_NOW": now.strftime("%Y-%m-%d %H:%M:%S"), **extra},
        capture_output=True,
        text=True,
        check=False,
    )


def test_a_backup_is_complete_checked_and_also_kept_monthly(env: dict[str, str]) -> None:
    result = run(env, datetime(2026, 10, 20, 2, 30, tzinfo=UTC))
    assert result.returncode == 0, result.stderr
    folder = Path(env["BACKUPS"]) / "daily" / "2026-10-20_0230"
    assert sorted(p.name for p in folder.iterdir()) == [
        "SHA256SUMS",
        "fet_prod.dump",
        "receipts.tar.gz",
    ]
    check = subprocess.run(
        ["sha256sum", "-c", "SHA256SUMS"], cwd=folder, capture_output=True, check=False
    )
    assert check.returncode == 0
    listing = subprocess.run(
        ["tar", "-tzf", str(folder / "receipts.tar.gz")], capture_output=True, text=True, check=True
    )
    assert "./hh/r1.jpg" in listing.stdout
    monthly = Path(env["BACKUPS"]) / "monthly" / "2026-10" / "fet_prod.dump"
    assert (
        monthly.stat().st_ino == (folder / "fet_prod.dump").stat().st_ino
    )  # a hard link, no extra space
    assert (Path(env["BACKUPS"]) / "LAST_SUCCESS").exists()


def test_old_backups_are_pruned_daily_and_monthly(env: dict[str, str]) -> None:
    day = datetime(2026, 8, 30, 2, 30, tzinfo=UTC)
    for _ in range(40):  # Aug 30 .. Oct 8
        assert run(env, day).returncode == 0
        day += timedelta(days=1)
    daily = sorted(p.name for p in (Path(env["BACKUPS"]) / "daily").iterdir())
    monthly = sorted(p.name for p in (Path(env["BACKUPS"]) / "monthly").iterdir())
    assert daily == ["2026-10-06_0230", "2026-10-07_0230", "2026-10-08_0230"]
    assert monthly == ["2026-09", "2026-10"]  # August pruned (keep 2)
    # The monthly copy is the month's first backup.
    assert (
        "2026-10-01" in (Path(env["BACKUPS"]) / "monthly" / "2026-10" / "fet_prod.dump").read_text()
    )


@pytest.mark.parametrize("failure", ["FAIL_DUMP", "FAIL_RESTORE"])
def test_a_failed_or_unreadable_dump_leaves_nothing_behind(
    env: dict[str, str], failure: str
) -> None:
    result = run(env, datetime(2026, 10, 20, 2, 30, tzinfo=UTC), **{failure: "1"})
    assert result.returncode != 0
    backups = Path(env["BACKUPS"])
    assert list((backups / "daily").iterdir()) == []
    assert not any(p.name.startswith(".in-progress") for p in backups.iterdir())
    assert not (backups / "LAST_SUCCESS").exists()


@pytest.mark.parametrize(
    ("now", "at", "seconds"),
    [
        ("01:00:00", "02:30", 5400),
        ("03:00:00", "02:30", 84600),
        ("02:30:00", "02:30", 86400),
        ("07:59:30", "08:05", 330),  # leading zeros must not be read as octal
        ("23:59:59", "00:00", 1),
    ],
)
def test_next_run_time(env: dict[str, str], now: str, at: str, seconds: int) -> None:
    result = run(
        env,
        datetime.strptime(f"2026-10-20 {now}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=UTC),
        "--seconds-until",
        at,
    )
    assert result.returncode == 0, result.stderr
    assert int(result.stdout) == seconds


def test_backups_are_private_to_their_owner(env: dict[str, str]) -> None:
    result = run(env, datetime(2026, 10, 20, 2, 30, tzinfo=UTC))
    assert result.returncode == 0, result.stderr
    backups = Path(env["BACKUPS"])
    folder = backups / "daily" / "2026-10-20_0230"
    for path in [backups / "daily", backups / "monthly", folder, backups / "LAST_SUCCESS"]:
        assert path.stat().st_mode & 0o077 == 0, path
    for name in ["fet_prod.dump", "receipts.tar.gz", "SHA256SUMS"]:
        assert (folder / name).stat().st_mode & 0o077 == 0, name


def test_a_password_in_the_database_url_is_refused(env: dict[str, str]) -> None:
    # A URL argument shows in `ps`; the password must come from PGPASSWORD.
    result = run(
        env,
        datetime(2026, 10, 20, 2, 30, tzinfo=UTC),
        BACKUP_DATABASE_URL="postgresql://fet_app:secret@db/fet_prod",
    )
    assert result.returncode != 0
    assert "PGPASSWORD" in result.stderr
    assert not (Path(env["BACKUPS"]) / "daily").exists()
