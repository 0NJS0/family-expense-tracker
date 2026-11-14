"""The Docker image installs backend/requirements.txt; it must match uv.lock."""

import shutil
import subprocess
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parents[1]


@pytest.mark.skipif(shutil.which("uv") is None, reason="uv not installed")
def test_requirements_txt_matches_the_lock_file() -> None:
    exported = subprocess.run(
        [
            "uv",
            "export",
            "--frozen",
            "--no-dev",
            "--no-emit-project",
            "--format",
            "requirements-txt",
        ],
        cwd=BACKEND,
        capture_output=True,
        text=True,
        check=True,
    ).stdout

    def body(text: str) -> list[str]:
        return [line for line in text.splitlines() if not line.startswith("#")]

    fix = "uv export --frozen --no-dev --no-emit-project --format requirements-txt"
    fix += " -o requirements.txt"
    assert body(exported) == body((BACKEND / "requirements.txt").read_text()), f"run: {fix}"
