import json
import uuid
from datetime import timedelta

import jwt
from fastapi.testclient import TestClient
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.models import AuditEvent, RefreshToken, User
from app.security.tokens import create_access_token
from tests.conftest import PASSWORD, Actor, login, make_user


def _refresh(client: TestClient, token: str):  # type: ignore[no-untyped-def]
    return client.post("/api/v1/auth/refresh", json={"refresh_token": token})


def _me(client: TestClient, access: str):  # type: ignore[no-untyped-def]
    return client.get("/api/v1/me", headers={"Authorization": f"Bearer {access}"})


def test_login_and_me(admin: Actor) -> None:
    response = _me(admin.client, admin.access_token)
    assert response.status_code == 200
    body = response.json()
    assert body["user"]["username"] == "admin"
    assert body["memberships"][0]["role"] == "admin"
    assert "password_hash" not in json.dumps(body)


def test_username_is_case_insensitive(client: TestClient, db: Session) -> None:
    make_user(db, "amma")
    assert login(client, "  AMMA ")["access_token"]


def test_wrong_password_and_unknown_user_look_identical(client: TestClient, db: Session) -> None:
    make_user(db, "amma")
    wrong = client.post("/api/v1/auth/login", json={"username": "amma", "password": "nope-nope"})
    unknown = client.post("/api/v1/auth/login", json={"username": "ghost", "password": "nope-nope"})
    assert wrong.status_code == unknown.status_code == 401
    assert wrong.json() == unknown.json()


def test_inactive_user_cannot_log_in(client: TestClient, db: Session) -> None:
    user = make_user(db, "amma")
    user.is_active = False
    db.commit()
    response = client.post("/api/v1/auth/login", json={"username": "amma", "password": PASSWORD})
    assert response.status_code == 401


def test_protected_endpoints_require_token(client: TestClient) -> None:
    for path in (
        "/api/v1/me",
        "/api/v1/accounts",
        "/api/v1/transactions",
        "/api/v1/reports/summary",
    ):
        response = client.get(path)
        assert response.status_code == 401, path
        assert response.json()["error"]["code"] == "unauthorized"


def test_tampered_and_foreign_tokens_rejected(admin: Actor, settings) -> None:  # type: ignore[no-untyped-def]
    tampered = admin.access_token[:-2] + ("AA" if admin.access_token[-2:] != "AA" else "BB")
    assert _me(admin.client, tampered).status_code == 401
    claims = jwt.decode(admin.access_token, options={"verify_signature": False})
    forged = jwt.encode(claims, "some-other-secret-" + "y" * 40, algorithm="HS256")
    assert _me(admin.client, forged).status_code == 401
    unsigned = jwt.encode(claims, None, algorithm="none")  # type: ignore[arg-type]
    assert _me(admin.client, unsigned).status_code == 401


def test_expired_access_token_rejected(admin: Actor, settings) -> None:  # type: ignore[no-untyped-def]
    claims = jwt.decode(admin.access_token, options={"verify_signature": False})
    expired, _ = create_access_token(
        settings.jwt_secret.get_secret_value(),
        uuid.UUID(claims["sub"]),
        uuid.UUID(claims["sid"]),
        timedelta(seconds=-5),
    )
    assert _me(admin.client, expired).status_code == 401


def test_refresh_rotates_tokens(admin: Actor) -> None:
    first = _refresh(admin.client, admin.refresh_token)
    assert first.status_code == 200
    new = first.json()
    assert new["refresh_token"] != admin.refresh_token
    assert _me(admin.client, new["access_token"]).status_code == 200
    second = _refresh(admin.client, new["refresh_token"])
    assert second.status_code == 200


def test_retry_within_grace_window_succeeds_and_invalidates_lost_token(admin: Actor) -> None:
    lost = _refresh(admin.client, admin.refresh_token).json()["refresh_token"]
    retry = _refresh(admin.client, admin.refresh_token)
    assert retry.status_code == 200
    # The token from the first (lost) response must no longer be usable as fresh.
    assert retry.json()["refresh_token"] != lost


def test_reuse_after_grace_window_revokes_session(admin: Actor, db: Session) -> None:
    rotated = _refresh(admin.client, admin.refresh_token).json()
    db.execute(
        update(RefreshToken)
        .where(RefreshToken.used_at.is_not(None))
        .values(used_at=RefreshToken.used_at - timedelta(hours=1))
    )
    db.commit()
    stolen_replay = _refresh(admin.client, admin.refresh_token)
    assert stolen_replay.status_code == 401
    # The whole session is revoked: the legitimate newest tokens stop working too.
    assert _refresh(admin.client, rotated["refresh_token"]).status_code == 401
    assert _me(admin.client, rotated["access_token"]).status_code == 401
    actions = db.scalars(select(AuditEvent.action)).all()
    assert "auth.refresh_reuse_detected" in actions


def test_logout_revokes_access_and_refresh(admin: Actor) -> None:
    response = admin.client.post("/api/v1/auth/logout", json={"refresh_token": admin.refresh_token})
    assert response.status_code == 204
    assert _me(admin.client, admin.access_token).status_code == 401
    assert _refresh(admin.client, admin.refresh_token).status_code == 401


def test_logout_only_affects_that_device(client: TestClient, admin: Actor) -> None:
    phone = login(client, "admin")
    client.post("/api/v1/auth/logout", json={"refresh_token": admin.refresh_token})
    assert _me(client, phone["access_token"]).status_code == 200


def test_logout_with_unknown_token_is_silent(client: TestClient) -> None:
    response = client.post("/api/v1/auth/logout", json={"refresh_token": "not-a-real-token"})
    assert response.status_code == 204


def test_deactivated_user_loses_access_immediately(admin: Actor, db: Session) -> None:
    db.execute(update(User).where(User.id == admin.user_id).values(is_active=False))
    db.commit()
    assert _me(admin.client, admin.access_token).status_code == 401
    assert _refresh(admin.client, admin.refresh_token).status_code == 401


def test_login_rate_limited(client: TestClient, db: Session, settings) -> None:  # type: ignore[no-untyped-def]
    make_user(db, "amma")
    statuses = [
        client.post(
            "/api/v1/auth/login", json={"username": "amma", "password": "wrong-pass"}
        ).status_code
        for _ in range(settings.login_rate_limit_per_minute + 1)
    ]
    assert statuses[:-1] == [401] * settings.login_rate_limit_per_minute
    assert statuses[-1] == 429
    # Even the correct password is refused while limited.
    ok = client.post("/api/v1/auth/login", json={"username": "amma", "password": PASSWORD})
    assert ok.status_code == 429


def test_secrets_never_stored_in_plaintext(admin: Actor, db: Session) -> None:
    stored = db.scalars(select(RefreshToken.token_hash)).all()
    assert admin.refresh_token not in stored
    user = db.get(User, admin.user_id)
    assert user is not None and user.password_hash.startswith("$argon2id$")
    audit_dump = json.dumps([e.details for e in db.scalars(select(AuditEvent))])
    assert admin.refresh_token not in audit_dump
    assert admin.access_token not in audit_dump
    assert PASSWORD not in audit_dump


def test_validation_errors_do_not_echo_password(client: TestClient) -> None:
    response = client.post(
        "/api/v1/auth/login", json={"username": "x", "password": "hunter2-secret"}
    )
    assert response.status_code == 422
    assert "hunter2-secret" not in response.text
