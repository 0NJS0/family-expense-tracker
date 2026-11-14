import json
import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import AuditEvent
from tests.conftest import Actor, at

# --- accounts -------------------------------------------------------------------


def test_create_and_list_accounts(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    shared = admin.post("/accounts", {"name": "Family cash", "kind": "cash"}).json()
    personal = admin.post(
        "/accounts",
        {"name": "Amma bKash", "kind": "mobile_wallet", "owner_user_id": str(amma.user_id)},
    ).json()
    assert shared["is_shared"] is True and shared["opening_balance"] is None
    assert personal["is_shared"] is False and personal["owner_user_id"] == str(amma.user_id)
    names = {a["name"] for a in amma.get("/accounts").json()}
    assert names == {"Family cash", "Amma bKash"} | {"নগদ", "বিকাশ", "ব্যাংক"}


def test_account_validation(admin: Actor) -> None:
    admin.create_account("Cash")
    assert admin.post("/accounts", {"name": "CASH", "kind": "cash"}).status_code == 409
    assert admin.post("/accounts", {"name": "X", "kind": "credit_card"}).status_code == 422
    assert admin.post("/accounts", {"name": "  ", "kind": "cash"}).status_code == 422
    outsider = str(uuid.uuid4())
    assert (
        admin.post(
            "/accounts", {"name": "Y", "kind": "cash", "owner_user_id": outsider}
        ).status_code
        == 422
    )


def test_archive_account_hides_from_default_list(admin: Actor) -> None:
    cash = admin.create_account("Cash")
    assert admin.patch(f"/accounts/{cash}", {"is_archived": True}).status_code == 200
    assert {a["name"] for a in admin.get("/accounts").json()} == {"নগদ", "বিকাশ", "ব্যাংক"}
    archived = admin.get("/accounts", params={"include_archived": True}).json()
    assert [a["name"] for a in archived if a["is_archived"]] == ["Cash"]
    assert admin.patch(f"/accounts/{cash}", {"name": None}).status_code == 422


def test_archived_account_keeps_history_and_edits(admin: Actor) -> None:
    cash = admin.create_account("Cash")
    tx = admin.create_tx(
        type="expense",
        amount_poisha=100,
        category_id=admin.category_id("Groceries"),
        account_id=cash,
        occurred_at=at("2026-03-01T09:00:00"),
    )
    admin.patch(f"/accounts/{cash}", {"is_archived": True})
    assert admin.balance(cash) == -100
    # Correcting an existing entry on an archived account is still allowed.
    assert (
        admin.patch(f"/transactions/{tx['id']}", {"version": 1, "amount_poisha": 200}).status_code
        == 200
    )


# --- categories -----------------------------------------------------------------


def test_custom_category(admin: Actor) -> None:
    created = admin.post(
        "/categories", {"kind": "expense", "name_en": "Medicine for Abba", "name_bn": "আব্বার ওষুধ"}
    )
    assert created.status_code == 201
    assert created.json()["is_default"] is False
    duplicate = admin.post(
        "/categories", {"kind": "expense", "name_en": "medicine for abba", "name_bn": "x"}
    )
    assert duplicate.status_code == 409
    # Same English name is fine for the other kind.
    assert (
        admin.post(
            "/categories", {"kind": "income", "name_en": "Medicine for Abba", "name_bn": "x"}
        ).status_code
        == 201
    )
    only_income = admin.get("/categories", params={"kind": "income"}).json()
    assert all(c["kind"] == "income" for c in only_income)


def test_category_kind_cannot_change(admin: Actor) -> None:
    groceries = admin.category_id("Groceries")
    assert admin.patch(f"/categories/{groceries}", {"kind": "income"}).status_code == 422


def test_rename_category(admin: Actor) -> None:
    groceries = admin.category_id("Groceries")
    response = admin.patch(f"/categories/{groceries}", {"name_bn": "কাঁচাবাজার"})
    assert response.status_code == 200 and response.json()["name_bn"] == "কাঁচাবাজার"


# --- audit trail ----------------------------------------------------------------


def test_financial_changes_are_audited(admin: Actor, db: Session) -> None:
    cash = admin.create_account("Cash")
    tx = admin.create_tx(
        type="expense",
        amount_poisha=500,
        category_id=admin.category_id("Groceries"),
        account_id=cash,
        occurred_at=at("2026-03-01T09:00:00"),
        note="secret medical note",
    )
    admin.patch(
        f"/transactions/{tx['id']}", {"version": 1, "amount_poisha": 700, "note": "changed"}
    )
    admin.post(f"/transactions/{tx['id']}/void", {"version": 2, "reason": "duplicate"})

    events = admin.get("/audit-events", params={"entity_id": tx["id"]}).json()["items"]
    by_action = {e["action"]: e for e in events}
    assert set(by_action) == {"transaction.created", "transaction.updated", "transaction.voided"}
    assert all(e["actor_user_id"] == str(admin.user_id) for e in events)
    assert by_action["transaction.created"]["details"]["amount_poisha"] == 500
    changes = by_action["transaction.updated"]["details"]["changes"]
    assert changes["amount_poisha"] == {"from": 500, "to": 700}
    assert changes["note"] == {"changed": True}
    assert by_action["transaction.voided"]["details"] == {"version": 3, "has_reason": True}

    everything = json.dumps([e.details for e in db.scalars(select(AuditEvent))], ensure_ascii=False)
    assert "secret medical note" not in everything
    assert "duplicate" not in everything  # the void reason is free text, like a note


def test_admin_changes_are_audited(admin: Actor) -> None:
    cash = admin.create_account("Cash")
    admin.post(
        f"/accounts/{cash}/opening-balance", {"amount_poisha": 100, "as_of_date": "2026-01-01"}
    )
    admin.patch(f"/accounts/{cash}", {"name": "Wallet"})
    admin.post("/categories", {"kind": "expense", "name_en": "Pets", "name_bn": "পোষা প্রাণী"})
    admin.post("/members/invitations", {"display_name": "Amma", "role": "viewer"})
    actions = {
        e["action"] for e in admin.get("/audit-events", params={"limit": 200}).json()["items"]
    }
    assert {
        "household.created",
        "account.created",
        "account.opening_balance_set",
        "account.updated",
        "category.created",
        "invitation.created",
    } <= actions


def test_audit_log_is_household_scoped(admin: Actor, make_actor) -> None:  # type: ignore[no-untyped-def]
    admin.create_account("Cash")
    other: Actor = make_actor("neighbour", "Neighbours")
    actions = [e["action"] for e in other.get("/audit-events").json()["items"]]
    assert "account.created" not in actions


def test_new_household_gets_simple_default_accounts(admin: Actor, db: Session) -> None:
    accounts = {a["name"]: a for a in admin.get("/accounts").json()}
    assert set(accounts) == {"নগদ", "বিকাশ", "ব্যাংক"}
    assert accounts["নগদ"]["kind"] == "cash"
    assert accounts["বিকাশ"]["kind"] == "mobile_wallet"
    assert accounts["বিকাশ"]["institution"] == "bKash"
    assert accounts["ব্যাংক"]["kind"] == "bank"
    # Shared, no starting balance, no bank details: just labels for where money is.
    assert all(a["is_shared"] and a["opening_balance"] is None for a in accounts.values())
    created = next(
        e for e in admin.get("/audit-events").json()["items"] if e["action"] == "household.created"
    )
    assert created["details"]["default_accounts"] == ["নগদ", "বিকাশ", "ব্যাংক"]
    # They behave like any other account: rename (e.g. to the bank's name) and archive.
    bank = accounts["ব্যাংক"]["id"]
    assert (
        admin.patch(
            f"/accounts/{bank}", {"name": "সোনালী ব্যাংক", "institution": "Sonali Bank"}
        ).status_code
        == 200
    )


def test_health_and_readiness(client) -> None:  # type: ignore[no-untyped-def]
    assert client.get("/api/v1/health").json() == {"status": "ok"}
    assert client.get("/api/v1/ready").json() == {"status": "ok"}


def test_unknown_route_uses_error_envelope(client) -> None:  # type: ignore[no-untyped-def]
    body = client.get("/api/v1/nope").json()
    assert body["error"]["code"] == "not_found"
