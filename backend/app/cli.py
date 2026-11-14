"""Administrative commands.

    uv run python -m app.cli bootstrap --username admin --display-name "Admin" \
        --household "Our family"

The password is read interactively (or from FET_BOOTSTRAP_PASSWORD for
automation) and is never accepted as a command-line argument, where it
would be visible in the process list and shell history.
"""

import argparse
import getpass
import os
import sys

from pydantic import TypeAdapter, ValidationError

from app.config import get_settings
from app.database import build_engine, build_sessionmaker
from app.schemas.common import DisplayName, Password, ShortText, Username
from app.services.households import AlreadyBootstrapped, bootstrap


def _read_password() -> str:
    from_env = os.environ.get("FET_BOOTSTRAP_PASSWORD")
    if from_env:
        return from_env
    first = getpass.getpass("Admin password (min 8 characters): ")
    if first != getpass.getpass("Repeat password: "):
        sys.exit("Passwords do not match")
    return first


def _bootstrap(args: argparse.Namespace) -> int:
    settings = get_settings()
    try:
        username = TypeAdapter(Username).validate_python(args.username)
        display_name = TypeAdapter(DisplayName).validate_python(args.display_name)
        household = TypeAdapter(ShortText).validate_python(args.household)
        password = TypeAdapter(Password).validate_python(_read_password())
    except ValidationError as exc:
        print(f"Invalid input: {exc.errors()[0]['msg']}", file=sys.stderr)
        return 2
    engine = build_engine(settings.database_url)
    try:
        with build_sessionmaker(engine)() as db:
            user, home = bootstrap(
                db,
                username=username,
                display_name=display_name,
                password=password,
                household_name=household,
                timezone=settings.default_timezone,
            )
    except AlreadyBootstrapped as exc:
        print(f"Refusing to bootstrap: {exc}", file=sys.stderr)
        return 1
    finally:
        engine.dispose()
    print(f"Created admin '{user.username}' and household '{home.name}' ({home.id})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.cli")
    sub = parser.add_subparsers(dest="command", required=True)
    boot = sub.add_parser("bootstrap", help="Create the first admin user and household (once)")
    boot.add_argument("--username", required=True)
    boot.add_argument("--display-name", required=True)
    boot.add_argument("--household", required=True)
    args = parser.parse_args(argv)
    if args.command == "bootstrap":
        return _bootstrap(args)
    return 2  # pragma: no cover - argparse rejects unknown commands


if __name__ == "__main__":
    sys.exit(main())
