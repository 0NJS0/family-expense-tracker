"""Disposable PostgreSQL databases for tests.

TEST_DATABASE_URL points at a maintenance database (e.g. ``postgres``) for a
role with CREATEDB. Each test session creates ``fet_test_<random>``, uses it,
and drops it. No other database is ever touched.
"""

import os
import secrets
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

from sqlalchemy import create_engine, make_url, text
from sqlalchemy.engine import URL

ENV_FILE = Path(__file__).resolve().parent.parent / ".env.test"


def admin_url() -> URL:
    raw = os.environ.get("TEST_DATABASE_URL")
    if not raw and ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "TEST_DATABASE_URL":
                raw = value.strip()
    if not raw:
        raise RuntimeError(
            "TEST_DATABASE_URL is not set (env var or backend/.env.test); see docs/testing.md"
        )
    return make_url(raw)


def create_database(prefix: str = "fet_test") -> URL:
    name = f"{prefix}_{secrets.token_hex(6)}"
    base = admin_url()
    engine = create_engine(base, isolation_level="AUTOCOMMIT")
    with engine.connect() as conn:
        conn.execute(text(f'CREATE DATABASE "{name}"'))
    engine.dispose()
    return base.set(database=name)


def drop_database(url: URL) -> None:
    name = url.database
    if not name or not name.startswith("fet_test_"):
        raise RuntimeError(f"refusing to drop non-test database {name!r}")
    engine = create_engine(admin_url(), isolation_level="AUTOCOMMIT")
    with engine.connect() as conn:
        conn.execute(text(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)'))
    engine.dispose()


@contextmanager
def temporary_database() -> Iterator[URL]:
    url = create_database()
    try:
        yield url
    finally:
        drop_database(url)


def migrate(url: URL, revision: str = "head", *, downgrade: bool = False) -> None:
    from alembic import command
    from alembic.config import Config

    cfg = Config(str(Path(__file__).resolve().parent.parent / "alembic.ini"))
    cfg.attributes["database_url"] = url.render_as_string(hide_password=False)
    cfg.attributes["configure_logger"] = False
    if downgrade:
        command.downgrade(cfg, revision)
    else:
        command.upgrade(cfg, revision)
