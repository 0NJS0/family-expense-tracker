from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from app.models import Category, Invitation, User
from app.services.households import (
    DEFAULT_EXPENSE_CATEGORIES,
    DEFAULT_INCOME_CATEGORIES,
    AlreadyBootstrapped,
    bootstrap,
)
from tests.conftest import PASSWORD, Actor, login


def test_new_household_has_bilingual_default_categories(admin: Actor) -> None:
    categories = admin.get("/categories").json()
    expense = [c for c in categories if c["kind"] == "expense"]
    income = [c for c in categories if c["kind"] == "income"]
    assert len(expense) == len(DEFAULT_EXPENSE_CATEGORIES)
    assert len(income) == len(DEFAULT_INCOME_CATEGORIES)
    groceries = next(c for c in expense if c["name_en"] == "Groceries")
    assert groceries["name_bn"] == "বাজার"
    assert all(c["is_default"] for c in categories)


def test_current_household(admin: Actor) -> None:
    body = admin.get("/households/current").json()
    assert body["timezone"] == "Asia/Dhaka"
    assert body["currency"] == "BDT"
    assert body["week_start"] == 5


def test_create_second_household_requires_header(admin: Actor) -> None:
    response = admin.client.post(
        "/api/v1/households",
        headers={"Authorization": f"Bearer {admin.access_token}"},
        json={"name": "Village home"},
    )
    assert response.status_code == 201
    no_header = admin.client.get(
        "/api/v1/households/current", headers={"Authorization": f"Bearer {admin.access_token}"}
    )
    assert no_header.status_code == 422
    assert admin.get("/households/current").json()["id"] == str(admin.household_id)


def test_invalid_timezone_rejected(admin: Actor) -> None:
    response = admin.post("/households", {"name": "X", "timezone": "Not/AZone"})
    assert response.status_code == 422


def test_invitation_flow(admin: Actor, client: TestClient) -> None:
    invite = admin.post("/members/invitations", {"display_name": "Amma", "role": "member"})
    assert invite.status_code == 201
    token = invite.json()["token"]
    accepted = client.post(
        "/api/v1/invitations/accept",
        json={"token": token, "username": "amma", "password": PASSWORD},
    )
    assert accepted.status_code == 201
    assert accepted.json()["display_name"] == "Amma"
    assert accepted.json()["preferred_language"] == "bn"
    members = {m["username"]: m["role"] for m in admin.get("/members").json()}
    assert members == {"admin": "admin", "amma": "member"}
    login(client, "amma")

    reused = client.post(
        "/api/v1/invitations/accept",
        json={"token": token, "username": "someone", "password": PASSWORD},
    )
    assert reused.status_code == 404


def test_invitation_token_stored_hashed(admin: Actor, db: Session) -> None:
    token = admin.post("/members/invitations", {"display_name": "Abba"}).json()["token"]
    stored = db.scalars(select(Invitation.token_hash)).all()
    assert token not in stored


def test_expired_invitation_rejected(admin: Actor, client: TestClient, db: Session) -> None:
    token = admin.post("/members/invitations", {"display_name": "Abba"}).json()["token"]
    db.execute(update(Invitation).values(expires_at=Invitation.expires_at - timedelta(days=8)))
    db.commit()
    response = client.post(
        "/api/v1/invitations/accept",
        json={"token": token, "username": "abba", "password": PASSWORD},
    )
    assert response.status_code == 404


def test_invitation_username_taken(admin: Actor, client: TestClient) -> None:
    token = admin.post("/members/invitations", {"display_name": "Abba"}).json()["token"]
    response = client.post(
        "/api/v1/invitations/accept",
        json={"token": token, "username": "admin", "password": PASSWORD},
    )
    assert response.status_code == 409


def test_invitation_password_policy(admin: Actor, client: TestClient) -> None:
    token = admin.post("/members/invitations", {"display_name": "Abba"}).json()["token"]
    response = client.post(
        "/api/v1/invitations/accept", json={"token": token, "username": "abba", "password": "short"}
    )
    assert response.status_code == 422


def test_bootstrap_runs_only_once(db: Session) -> None:
    _user, household = bootstrap(
        db,
        username="owner",
        display_name="Owner",
        password=PASSWORD,
        household_name="Family",
        timezone="Asia/Dhaka",
    )
    assert household.name == "Family"
    assert db.scalar(select(func.count()).select_from(Category)) > 0
    with pytest.raises(AlreadyBootstrapped):
        bootstrap(
            db,
            username="intruder",
            display_name="X",
            password=PASSWORD,
            household_name="Other",
            timezone="Asia/Dhaka",
        )
    db.rollback()
    assert db.scalar(select(func.count()).select_from(User)) == 1


def test_bootstrap_cli_refuses_second_run(
    db: Session,
    settings,
    monkeypatch: pytest.MonkeyPatch,  # type: ignore[no-untyped-def]
) -> None:
    from app import cli
    from app.config import get_settings

    monkeypatch.setenv("FET_DATABASE_URL", settings.database_url)
    monkeypatch.setenv("FET_BOOTSTRAP_PASSWORD", PASSWORD)
    get_settings.cache_clear()
    try:
        args = ["bootstrap", "--username", "owner", "--display-name", "Owner", "--household", "Fam"]
        assert cli.main(args) == 0
        assert cli.main(args) == 1
    finally:
        get_settings.cache_clear()
    assert db.scalar(select(func.count()).select_from(User)) == 1
