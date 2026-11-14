import threading
import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import Conflict
from app.models import AuditEvent, Household, Role, User
from app.schemas.identity import MemberUpdate
from app.services import households
from app.services.context import HouseholdContext
from tests.conftest import PASSWORD, Actor, at, login


def _members(actor: Actor) -> dict[str, dict]:  # type: ignore[type-arg]
    return {m["username"]: m for m in actor.get("/members").json()}


# --- roles and deactivation ------------------------------------------------------


def test_change_role(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    response = admin.patch(f"/members/{amma.user_id}", {"role": "viewer"})
    assert response.status_code == 200
    assert response.json()["role"] == "viewer"
    # Takes effect on the next request with the same token.
    assert amma.post("/accounts", {"name": "X", "kind": "cash"}).status_code == 403
    events = admin.get("/audit-events", params={"limit": 200}).json()["items"]
    change = next(e for e in events if e["action"] == "membership.updated")
    assert change["details"]["changes"] == {"role": {"from": "member", "to": "viewer"}}


def test_only_admins_manage_members(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    assert amma.patch(f"/members/{admin.user_id}", {"role": "viewer"}).status_code == 403
    assert amma.patch(f"/members/{amma.user_id}", {"role": "admin"}).status_code == 403


def test_unknown_or_foreign_member_is_404(admin: Actor, make_actor) -> None:  # type: ignore[no-untyped-def]
    other: Actor = make_actor("neighbour", "Neighbours")
    assert admin.patch(f"/members/{other.user_id}", {"role": "viewer"}).status_code == 404
    assert admin.patch(f"/members/{uuid.uuid4()}", {"role": "viewer"}).status_code == 404


def test_last_admin_cannot_be_demoted_or_deactivated(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    join(admin, "amma", "member")
    assert admin.patch(f"/members/{admin.user_id}", {"role": "member"}).status_code == 409
    assert admin.patch(f"/members/{admin.user_id}", {"is_active": False}).status_code == 409
    assert _members(admin)["admin"]["role"] == "admin"


def test_admin_can_step_down_when_another_admin_exists(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    join(admin, "bhai", "admin")
    assert admin.patch(f"/members/{admin.user_id}", {"role": "member"}).status_code == 200


def test_concurrent_mutual_demotion_keeps_an_admin(app: FastAPI, admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    bhai: Actor = join(admin, "bhai", "admin")
    barrier = threading.Barrier(2)
    results: list[object] = [None, None]

    def demote(i: int, actor: Actor, target: uuid.UUID) -> None:
        with app.state.sessionmaker() as db:
            user = db.get(User, actor.user_id)
            household = db.get(Household, actor.household_id)
            assert user is not None and household is not None
            ctx = HouseholdContext(user=user, household=household, role=Role.ADMIN)
            barrier.wait()
            try:
                results[i] = households.update_member(
                    db, ctx, target, MemberUpdate(role=Role.MEMBER)
                )
            except Conflict as exc:
                results[i] = exc

    threads = [
        threading.Thread(target=demote, args=(0, admin, bhai.user_id)),
        threading.Thread(target=demote, args=(1, bhai, admin.user_id)),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    assert sum(isinstance(r, Conflict) for r in results) == 1, results
    # Whoever was demoted is still a member and can read the list.
    assert [m["role"] for m in _members(admin).values()].count("admin") == 1


def test_deactivated_member_loses_access_but_history_remains(
    admin: Actor,
    join,
    client: TestClient,  # type: ignore[no-untyped-def]
) -> None:
    cash = admin.create_account("Cash")
    groceries = admin.category_id("Groceries")
    amma: Actor = join(admin, "amma", "member")
    old = amma.create_tx(
        type="expense",
        amount_poisha=500_00,
        category_id=groceries,
        account_id=cash,
        occurred_at=at("2026-03-01T09:00:00"),
    )

    assert admin.patch(f"/members/{amma.user_id}", {"is_active": False}).status_code == 200
    assert _members(admin)["amma"]["is_active"] is False

    # Immediate loss of access with the existing token; the household looks nonexistent.
    assert amma.get("/transactions").status_code == 404
    me = client.get("/api/v1/me", headers={"Authorization": f"Bearer {amma.access_token}"})
    assert me.json()["memberships"] == []

    # History intact: her transaction still counts and individual reports still work.
    s = admin.get(
        "/reports/summary",
        params={"date_from": "2026-03-01", "date_to": "2026-03-31", "member_id": str(amma.user_id)},
    ).json()
    assert s["expense_poisha"] == 500_00

    # No new data may be attributed to her or owned by her ...
    admin.create_tx(
        expect=422,
        type="expense",
        amount_poisha=100,
        category_id=groceries,
        account_id=cash,
        occurred_at=at("2026-03-02T09:00:00"),
        attributed_user_id=str(amma.user_id),
    )
    owned = admin.post(
        "/accounts", {"name": "Purse", "kind": "cash", "owner_user_id": str(amma.user_id)}
    )
    assert owned.status_code == 422
    # ... but her old entries can still be corrected.
    fixed = admin.patch(f"/transactions/{old['id']}", {"version": 1, "amount_poisha": 450_00})
    assert fixed.status_code == 200

    # Reactivation restores access.
    assert admin.patch(f"/members/{amma.user_id}", {"is_active": True}).status_code == 200
    assert amma.get("/transactions").status_code == 200


# --- password change --------------------------------------------------------------


def test_change_password_revokes_other_devices(admin: Actor, client: TestClient) -> None:
    phone = login(client, "admin")
    new_password = "a brand new passphrase"
    response = admin.post(
        "/me/password", {"current_password": PASSWORD, "new_password": new_password}
    )
    assert response.status_code == 204

    # This device keeps working; the other device is logged out.
    assert admin.get("/households/current").status_code == 200
    other = client.get("/api/v1/me", headers={"Authorization": f"Bearer {phone['access_token']}"})
    assert other.status_code == 401
    refresh = client.post("/api/v1/auth/refresh", json={"refresh_token": phone["refresh_token"]})
    assert refresh.status_code == 401

    old = client.post("/api/v1/auth/login", json={"username": "admin", "password": PASSWORD})
    assert old.status_code == 401
    assert login(client, "admin", new_password)["access_token"]


def test_change_password_validation(admin: Actor) -> None:
    wrong = admin.post(
        "/me/password", {"current_password": "not-it-at-all", "new_password": "valid password 2"}
    )
    assert wrong.status_code == 401
    same = admin.post("/me/password", {"current_password": PASSWORD, "new_password": PASSWORD})
    assert same.status_code == 422
    short = admin.post("/me/password", {"current_password": PASSWORD, "new_password": "short"})
    assert short.status_code == 422


def test_password_change_audited_without_secrets(admin: Actor, db: Session) -> None:
    new_password = "another strong pass"
    admin.post("/me/password", {"current_password": PASSWORD, "new_password": new_password})
    event = db.scalars(select(AuditEvent).where(AuditEvent.action == "user.password_changed")).one()
    assert PASSWORD not in str(event.details) and new_password not in str(event.details)
