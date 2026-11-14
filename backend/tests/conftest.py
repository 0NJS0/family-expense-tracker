import uuid
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import SecretStr
from sqlalchemy import text
from sqlalchemy.engine import URL
from sqlalchemy.orm import Session

from app.config import Environment, Settings
from app.main import create_app
from app.models import User
from app.schemas.identity import HouseholdCreate
from app.security.passwords import hash_password
from app.services.households import create_household
from tests import pgutil

PASSWORD = "correct horse battery"

# Tests must not depend on a developer's local backend/.env (which may hold a
# real secret or point at another database): settings come only from code.
Settings.model_config["env_file"] = None


@pytest.fixture(scope="session")
def database_url() -> Iterator[URL]:
    url = pgutil.create_database()
    try:
        pgutil.migrate(url)
        yield url
    finally:
        pgutil.drop_database(url)


@pytest.fixture(scope="session")
def settings(database_url: URL, tmp_path_factory: pytest.TempPathFactory) -> Settings:
    return Settings(
        environment=Environment.TEST,
        receipts_dir=str(tmp_path_factory.mktemp("receipts")),
        database_url=database_url.render_as_string(hide_password=False),
        jwt_secret=SecretStr("test-secret-" + "x" * 40),
        log_level="WARNING",
    )


@pytest.fixture(scope="session")
def app(settings: Settings) -> Iterator[FastAPI]:
    application = create_app(settings)
    yield application
    application.state.engine.dispose()


@pytest.fixture(autouse=True)
def _clean_database(app: FastAPI) -> Iterator[None]:
    yield
    with app.state.engine.begin() as conn:
        tables = conn.execute(
            text(
                "SELECT string_agg(quote_ident(tablename), ', ') FROM pg_tables "
                "WHERE schemaname = 'public' AND tablename <> 'alembic_version'"
            )
        ).scalar_one()
        # TRUNCATE does not fire the row-level append-only trigger on audit_events.
        conn.execute(text(f"TRUNCATE {tables} CASCADE"))
    for limiter in app.state.rate_limiters.values():
        limiter.reset()


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)


@pytest.fixture
def db(app: FastAPI) -> Iterator[Session]:
    with app.state.sessionmaker() as session:
        yield session


@dataclass
class Actor:
    """A logged-in user acting in a household through the API."""

    client: TestClient
    user_id: uuid.UUID
    username: str
    household_id: uuid.UUID
    access_token: str
    refresh_token: str

    @property
    def headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.access_token}",
            "X-Household-ID": str(self.household_id),
        }

    def get(self, path: str, **kwargs: Any) -> Any:
        return self.client.get(f"/api/v1{path}", headers=self.headers, **kwargs)

    def post(self, path: str, json: Any = None) -> Any:
        return self.client.post(f"/api/v1{path}", headers=self.headers, json=json)

    def patch(self, path: str, json: Any = None) -> Any:
        return self.client.patch(f"/api/v1{path}", headers=self.headers, json=json)

    # --- convenience builders -------------------------------------------

    def category_id(self, name_en: str) -> str:
        for category in self.get("/categories").json():
            if category["name_en"] == name_en:
                return str(category["id"])
        raise AssertionError(f"no category {name_en!r}")

    def create_account(self, name: str, kind: str = "cash", **extra: Any) -> str:
        response = self.post("/accounts", {"name": name, "kind": kind, **extra})
        assert response.status_code == 201, response.text
        return str(response.json()["id"])

    def create_tx(self, expect: int = 201, **fields: Any) -> dict[str, Any]:
        body = {"client_id": str(uuid.uuid4()), **fields}
        response = self.post("/transactions", body)
        assert response.status_code == expect, response.text
        result: dict[str, Any] = response.json()
        return result

    def balance(self, account_id: str, **params: Any) -> int:
        response = self.get(f"/accounts/{account_id}/balance", params=params)
        assert response.status_code == 200, response.text
        return int(response.json()["balance_poisha"])


def login(client: TestClient, username: str, password: str = PASSWORD) -> dict[str, Any]:
    response = client.post("/api/v1/auth/login", json={"username": username, "password": password})
    assert response.status_code == 200, response.text
    result: dict[str, Any] = response.json()
    return result


def make_user(db: Session, username: str, display_name: str | None = None) -> User:
    user = User(
        username=username,
        display_name=display_name or username.title(),
        password_hash=hash_password(PASSWORD),
    )
    db.add(user)
    db.commit()
    return user


@pytest.fixture
def make_actor(client: TestClient, db: Session):  # type: ignore[no-untyped-def]
    """Create a user who is admin of a new household and log them in."""

    def _make(username: str, household_name: str = "Home", week_start: int = 5) -> Actor:
        user = make_user(db, username)
        household = create_household(
            db, user, HouseholdCreate(name=household_name, week_start=week_start)
        )
        tokens = login(client, username)
        return Actor(
            client=client,
            user_id=user.id,
            username=username,
            household_id=household.id,
            access_token=tokens["access_token"],
            refresh_token=tokens["refresh_token"],
        )

    return _make


@pytest.fixture
def admin(make_actor) -> Actor:  # type: ignore[no-untyped-def]
    actor: Actor = make_actor("admin")
    return actor


@pytest.fixture
def join(client: TestClient):  # type: ignore[no-untyped-def]
    """Invite a new user into ``host``'s household with ``role`` and log them in."""

    def _join(host: Actor, username: str, role: str) -> Actor:
        invite = host.post("/members/invitations", {"display_name": username, "role": role})
        assert invite.status_code == 201, invite.text
        accepted = client.post(
            "/api/v1/invitations/accept",
            json={"token": invite.json()["token"], "username": username, "password": PASSWORD},
        )
        assert accepted.status_code == 201, accepted.text
        tokens = login(client, username)
        return Actor(
            client=client,
            user_id=uuid.UUID(accepted.json()["id"]),
            username=username,
            household_id=host.household_id,
            access_token=tokens["access_token"],
            refresh_token=tokens["refresh_token"],
        )

    return _join


def at(value: str) -> str:
    """ISO timestamp in Asia/Dhaka (+06:00) for readable test data."""
    return datetime.fromisoformat(value + "+06:00").isoformat()
