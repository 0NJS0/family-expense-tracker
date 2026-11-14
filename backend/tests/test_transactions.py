import threading
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from fastapi import FastAPI
from sqlalchemy import func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.errors import Conflict
from app.models import Household, Role, Transaction, User
from app.schemas.finance import TransactionCreate, TransactionUpdate
from app.services import transactions
from app.services.context import HouseholdContext
from tests.conftest import Actor, at

WHEN = at("2026-03-05T12:00:00")


@pytest.fixture
def setup(admin: Actor) -> dict[str, str]:
    return {
        "cash": admin.create_account("Cash"),
        "bank": admin.create_account("Bank", "bank"),
        "groceries": admin.category_id("Groceries"),
        "salary": admin.category_id("Salary"),
    }


def _expense_body(s: dict[str, str], **overrides: Any) -> dict[str, Any]:
    return {
        "client_id": str(uuid.uuid4()),
        "type": "expense",
        "amount_poisha": 12050,
        "category_id": s["groceries"],
        "account_id": s["cash"],
        "occurred_at": WHEN,
        **overrides,
    }


# --- creation & validation ---------------------------------------------------


def test_create_expense(admin: Actor, setup: dict[str, str]) -> None:
    response = admin.post("/transactions", _expense_body(setup, note="  rice and dal "))
    assert response.status_code == 201
    body = response.json()
    assert body["amount_poisha"] == 12050
    assert body["note"] == "rice and dal"
    assert body["version"] == 1
    assert body["created_by_user_id"] == body["attributed_user_id"] == str(admin.user_id)
    # Stored in UTC, same instant as the +06:00 input.
    assert datetime.fromisoformat(body["occurred_at"]) == datetime.fromisoformat(WHEN)


def test_amount_as_decimal_taka_string(admin: Actor, setup: dict[str, str]) -> None:
    body = _expense_body(setup, amount="1,250.75")
    del body["amount_poisha"]
    assert admin.post("/transactions", body).json()["amount_poisha"] == 125075


@pytest.mark.parametrize(
    "amount_fields",
    [
        {"amount_poisha": 0},
        {"amount_poisha": -500},
        {"amount_poisha": 120.5},
        {"amount_poisha": 120.0},  # floats are refused even when integral
        {"amount_poisha": "12050"},
        {"amount_poisha": True},
        {"amount_poisha": 100_000_000_001},  # above the per-transaction ceiling
        {"amount_poisha": 2**63},  # would overflow BIGINT
        {"amount_poisha": None, "amount": "12,50"},
        {"amount_poisha": None, "amount": "1.005"},
        {"amount_poisha": None, "amount": "0"},
        {"amount_poisha": None, "amount": "-1"},
        {"amount_poisha": 100, "amount": "1.00"},  # both forms at once
        {"amount_poisha": None},  # neither
    ],
)
def test_invalid_amounts_rejected(admin: Actor, setup: dict[str, str], amount_fields) -> None:  # type: ignore[no-untyped-def]
    body = _expense_body(setup, **amount_fields)
    if body.get("amount_poisha") is None:
        body.pop("amount_poisha")
    response = admin.post("/transactions", body)
    assert response.status_code == 422, response.text
    assert response.json()["error"]["code"] == "validation_failed"


def test_maximum_amount_accepted(admin: Actor, setup: dict[str, str]) -> None:
    assert (
        admin.post("/transactions", _expense_body(setup, amount_poisha=100_000_000_000)).status_code
        == 201
    )


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"category_id": None}, "needs a category"),
        ({"destination_account_id": "BANK"}, "Only transfers"),
        ({"category_id": "SALARY"}, "not an expense category"),
        ({"category_id": str(uuid.uuid4())}, "Category not found"),
        ({"account_id": str(uuid.uuid4())}, "Account not found"),
        ({"occurred_at": "2026-03-05T12:00:00"}, None),  # naive timestamp
        ({"occurred_at": "2099-01-01T00:00:00+06:00"}, "future"),
        ({"occurred_at": "1999-12-31T00:00:00+06:00"}, "past"),
        ({"note": "x" * 501}, None),
        ({"type": "refund"}, None),
        ({"unexpected": 1}, None),
    ],
)
def test_expense_validation(admin: Actor, setup: dict[str, str], overrides, message) -> None:  # type: ignore[no-untyped-def]
    resolved = {
        k: {"BANK": setup["bank"], "SALARY": setup["salary"]}.get(v, v) if isinstance(v, str) else v
        for k, v in overrides.items()
    }
    response = admin.post("/transactions", _expense_body(setup, **resolved))
    assert response.status_code == 422, response.text
    if message:
        assert message in response.json()["error"]["message"]


def test_income_requires_income_category(admin: Actor, setup: dict[str, str]) -> None:
    body = _expense_body(setup, type="income")
    response = admin.post("/transactions", body)
    assert response.status_code == 422
    assert "not an income category" in response.json()["error"]["message"]
    assert admin.post("/transactions", {**body, "category_id": setup["salary"]}).status_code == 201


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({}, "needs a destination"),
        ({"destination_account_id": "CASH"}, "must be different"),
        ({"destination_account_id": "BANK", "category_id": "GROC"}, "do not have a category"),
    ],
)
def test_transfer_validation(admin: Actor, setup: dict[str, str], overrides, message) -> None:  # type: ignore[no-untyped-def]
    names = {"CASH": setup["cash"], "BANK": setup["bank"], "GROC": setup["groceries"]}
    body = {
        "client_id": str(uuid.uuid4()),
        "type": "transfer",
        "amount_poisha": 50000,
        "account_id": setup["cash"],
        "occurred_at": WHEN,
        **{k: names[v] for k, v in overrides.items()},
    }
    response = admin.post("/transactions", body)
    assert response.status_code == 422
    assert message in response.json()["error"]["message"]


def test_archived_account_and_category_rejected_for_new_entries(
    admin: Actor, setup: dict[str, str]
) -> None:
    admin.patch(f"/categories/{setup['groceries']}", {"is_archived": True})
    response = admin.post("/transactions", _expense_body(setup))
    assert response.status_code == 422 and "archived" in response.json()["error"]["message"]
    admin.patch(f"/categories/{setup['groceries']}", {"is_archived": False})
    admin.patch(f"/accounts/{setup['cash']}", {"is_archived": True})
    response = admin.post("/transactions", _expense_body(setup))
    assert response.status_code == 422 and "archived" in response.json()["error"]["message"]


def test_attributed_member(admin: Actor, setup: dict[str, str], join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    tx = admin.create_tx(
        **{k: v for k, v in _expense_body(setup).items() if k != "client_id"},
        attributed_user_id=str(amma.user_id),
    )
    assert tx["created_by_user_id"] == str(admin.user_id)
    assert tx["attributed_user_id"] == str(amma.user_id)


# --- idempotency ---------------------------------------------------------------


def test_retry_with_same_client_id_does_not_duplicate(admin: Actor, setup: dict[str, str]) -> None:
    body = _expense_body(setup)
    first = admin.post("/transactions", body)
    second = admin.post("/transactions", body)
    assert first.status_code == 201
    assert second.status_code == 200
    assert first.json()["id"] == second.json()["id"]
    assert admin.get("/transactions").json()["total"] == 1


def test_retry_equivalent_time_in_other_offset_is_same_request(
    admin: Actor, setup: dict[str, str]
) -> None:
    body = _expense_body(setup, occurred_at="2026-03-05T12:00:00+06:00")
    admin.post("/transactions", body)
    replay = admin.post("/transactions", {**body, "occurred_at": "2026-03-05T06:00:00Z"})
    assert replay.status_code == 200


def test_client_id_reused_for_different_data_conflicts(admin: Actor, setup: dict[str, str]) -> None:
    body = _expense_body(setup)
    admin.post("/transactions", body)
    response = admin.post("/transactions", {**body, "amount_poisha": 99})
    assert response.status_code == 409
    assert admin.get("/transactions").json()["total"] == 1


def test_replay_after_edit_returns_current_state(admin: Actor, setup: dict[str, str]) -> None:
    body = _expense_body(setup)
    created = admin.post("/transactions", body).json()
    admin.patch(f"/transactions/{created['id']}", {"version": 1, "amount_poisha": 500})
    replay = admin.post("/transactions", body)
    assert replay.status_code == 200
    assert replay.json()["amount_poisha"] == 500


def _context(db: Session, user_id: uuid.UUID, household_id: uuid.UUID) -> HouseholdContext:
    user = db.get(User, user_id)
    household = db.get(Household, household_id)
    assert user is not None and household is not None
    return HouseholdContext(user=user, household=household, role=Role.ADMIN)


def _run_concurrently(n: int, work) -> list[Any]:  # type: ignore[no-untyped-def]
    barrier = threading.Barrier(n)
    results: list[Any] = [None] * n

    def runner(i: int) -> None:
        barrier.wait()
        try:
            results[i] = work(i)
        except Exception as exc:
            results[i] = exc

    threads = [threading.Thread(target=runner, args=(i,)) for i in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    return results


def test_concurrent_duplicate_submissions_create_one_row(
    app: FastAPI, admin: Actor, setup: dict[str, str]
) -> None:
    payload = TransactionCreate.model_validate(_expense_body(setup))

    def submit() -> uuid.UUID:
        with app.state.sessionmaker() as db:
            tx, _ = transactions.create_transaction(
                db, _context(db, admin.user_id, admin.household_id), payload
            )
            return tx.id

    results = _run_concurrently(8, lambda _: submit())
    assert all(isinstance(r, uuid.UUID) for r in results), results
    assert len(set(results)) == 1
    with app.state.sessionmaker() as db:
        assert db.scalar(select(func.count()).select_from(Transaction)) == 1


def test_concurrent_edits_with_same_version_only_one_wins(
    app: FastAPI, admin: Actor, setup: dict[str, str]
) -> None:
    tx = admin.post("/transactions", _expense_body(setup)).json()

    def edit(amount: int):  # type: ignore[no-untyped-def]
        def _work() -> int:
            with app.state.sessionmaker() as db:
                updated = transactions.update_transaction(
                    db,
                    _context(db, admin.user_id, admin.household_id),
                    uuid.UUID(tx["id"]),
                    TransactionUpdate(version=1, amount_poisha=amount),
                )
                return updated.amount_poisha

        return _work

    workers = [edit(111), edit(222)]
    results = _run_concurrently(2, lambda i: workers[i]())
    wins = [r for r in results if isinstance(r, int)]
    conflicts = [r for r in results if isinstance(r, Conflict)]
    assert len(wins) == 1 and len(conflicts) == 1, results
    final = admin.get(f"/transactions/{tx['id']}").json()
    assert final["amount_poisha"] == wins[0]
    assert final["version"] == 2


# --- edits & voids -----------------------------------------------------------


def test_edit_requires_current_version(admin: Actor, setup: dict[str, str]) -> None:
    tx = admin.post("/transactions", _expense_body(setup)).json()
    ok = admin.patch(f"/transactions/{tx['id']}", {"version": 1, "amount_poisha": 9000})
    assert ok.status_code == 200 and ok.json()["version"] == 2
    stale = admin.patch(f"/transactions/{tx['id']}", {"version": 1, "amount_poisha": 1})
    assert stale.status_code == 409
    assert stale.json()["error"]["details"] == [{"current_version": 2}]
    assert admin.get(f"/transactions/{tx['id']}").json()["amount_poisha"] == 9000


def test_edit_cannot_change_type_or_break_shape(admin: Actor, setup: dict[str, str]) -> None:
    tx = admin.post("/transactions", _expense_body(setup)).json()
    path = f"/transactions/{tx['id']}"
    assert admin.patch(path, {"version": 1, "type": "income"}).status_code == 422
    assert admin.patch(path, {"version": 1, "category_id": setup["salary"]}).status_code == 422
    assert admin.patch(path, {"version": 1, "category_id": None}).status_code == 422
    assert admin.patch(path, {"version": 1, "amount_poisha": None}).status_code == 422
    assert (
        admin.patch(path, {"version": 1, "destination_account_id": setup["bank"]}).status_code
        == 422
    )
    assert admin.get(path).json()["version"] == 1


def test_noop_edit_does_not_bump_version(admin: Actor, setup: dict[str, str]) -> None:
    tx = admin.post("/transactions", _expense_body(setup)).json()
    response = admin.patch(f"/transactions/{tx['id']}", {"version": 1, "amount_poisha": 12050})
    assert response.json()["version"] == 1


def test_edit_note_can_be_cleared(admin: Actor, setup: dict[str, str]) -> None:
    tx = admin.post("/transactions", _expense_body(setup, note="private")).json()
    response = admin.patch(f"/transactions/{tx['id']}", {"version": 1, "note": None})
    assert response.json()["note"] is None


def test_void(admin: Actor, setup: dict[str, str]) -> None:
    tx = admin.post("/transactions", _expense_body(setup)).json()
    path = f"/transactions/{tx['id']}"
    assert admin.post(f"{path}/void", {"version": 1, "reason": ""}).status_code == 422
    voided = admin.post(f"{path}/void", {"version": 1, "reason": "Entered twice"})
    assert voided.status_code == 200
    body = voided.json()
    assert body["voided_at"] and body["void_reason"] == "Entered twice" and body["version"] == 2
    assert admin.post(f"{path}/void", {"version": 2, "reason": "again"}).status_code == 409
    assert admin.patch(path, {"version": 2, "amount_poisha": 1}).status_code == 409
    # Still retrievable: history is kept, never deleted.
    assert admin.get(path).status_code == 200
    assert admin.get("/transactions").json()["total"] == 0
    assert admin.get("/transactions", params={"include_voided": True}).json()["total"] == 1


# --- listing -----------------------------------------------------------------


def test_list_filters_and_pagination(admin: Actor, setup: dict[str, str], join) -> None:  # type: ignore[no-untyped-def]
    amma: Actor = join(admin, "amma", "member")
    for day in range(1, 6):
        admin.create_tx(
            **{k: v for k, v in _expense_body(setup).items() if k != "client_id"}
            | {"occurred_at": at(f"2026-03-0{day}T09:00:00"), "note": f"day {day}"}
        )
    admin.create_tx(
        type="income",
        amount_poisha=100000,
        category_id=setup["salary"],
        account_id=setup["bank"],
        occurred_at=at("2026-03-03T09:00:00"),
        attributed_user_id=str(amma.user_id),
    )
    admin.create_tx(
        type="transfer",
        amount_poisha=5000,
        account_id=setup["bank"],
        destination_account_id=setup["cash"],
        occurred_at=at("2026-03-04T09:00:00"),
    )

    page = admin.get("/transactions", params={"limit": 2, "offset": 0}).json()
    assert page["total"] == 7 and len(page["items"]) == 2
    # Newest first.
    assert page["items"][0]["occurred_at"] >= page["items"][1]["occurred_at"]
    all_ids = [
        t["id"]
        for offset in (0, 2, 4, 6)
        for t in admin.get("/transactions", params={"limit": 2, "offset": offset}).json()["items"]
    ]
    assert len(all_ids) == len(set(all_ids)) == 7

    def total(**params: Any) -> int:
        return int(admin.get("/transactions", params=params).json()["total"])

    assert total(type="income") == 1
    assert total(type="transfer") == 1
    assert total(member_id=str(amma.user_id)) == 1
    assert total(category_id=setup["groceries"]) == 5
    assert total(account_id=setup["bank"]) == 2  # income + transfer source
    assert total(account_id=setup["cash"]) == 6  # 5 expenses + transfer destination
    assert total(date_from="2026-03-02", date_to="2026-03-03") == 3
    assert total(q="DAY 3") == 1
    assert total(q="%") == 0  # LIKE wildcards are escaped
    assert admin.get("/transactions", params={"limit": 500}).status_code == 422


# --- database backstops --------------------------------------------------------


def test_database_rejects_category_kind_mismatch_even_without_service(
    admin: Actor, setup: dict[str, str], db: Session
) -> None:
    with pytest.raises(IntegrityError):
        db.execute(
            text(
                "INSERT INTO transactions (id, household_id, client_id, request_fingerprint, "
                "created_by_user_id, attributed_user_id, type, amount_poisha, category_id, "
                "account_id, occurred_at) VALUES (gen_random_uuid(), :h, gen_random_uuid(), 'x', "
                ":u, :u, 'income', 100, :c, :a, now())"
            ),
            {
                "h": admin.household_id,
                "u": admin.user_id,
                "c": setup["groceries"],
                "a": setup["cash"],
            },
        )
    db.rollback()


def test_database_rejects_cross_household_account(admin: Actor, make_actor, db: Session) -> None:  # type: ignore[no-untyped-def]
    other: Actor = make_actor("neighbour", "Neighbours")
    their_account = other.create_account("Cash")
    with pytest.raises(IntegrityError):
        db.execute(
            text(
                "INSERT INTO transactions (id, household_id, client_id, request_fingerprint, "
                "created_by_user_id, attributed_user_id, type, amount_poisha, "
                "account_id, destination_account_id, occurred_at) VALUES (gen_random_uuid(), :h, "
                "gen_random_uuid(), 'x', :u, :u, 'transfer', 100, :a, :a2, now())"
            ),
            {
                "h": admin.household_id,
                "u": admin.user_id,
                "a": admin.create_account("Mine"),
                "a2": their_account,
            },
        )
    db.rollback()


def test_occurred_at_and_created_at_are_distinct(admin: Actor, setup: dict[str, str]) -> None:
    tx = admin.post("/transactions", _expense_body(setup)).json()
    created = datetime.fromisoformat(tx["created_at"])
    assert abs(created - datetime.now(UTC)) < timedelta(minutes=5)
    assert datetime.fromisoformat(tx["occurred_at"]) != created
