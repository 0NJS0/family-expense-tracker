"""Private accounts: only the owner and admins see them (owner decision, 2026-10-16).

A member must not learn anything about someone else's private account from any
endpoint: accounts, balances, transactions, reports, CSV export or the device
change feed. Shared accounts keep true balances even when private money moved
into them.
"""

from typing import Any

import pytest

from tests.conftest import Actor, at

WHEN = at("2026-10-15T12:00:00")
RANGE = {"date_from": "2026-10-01", "date_to": "2026-10-31"}


@pytest.fixture
def family(admin: Actor, join: Any) -> dict[str, Any]:
    abba = join(admin, "abba", "member")
    amma = join(admin, "amma", "member")
    shared = admin.create_account("Family cash")
    private = admin.create_account(
        "Abba's bKash", "mobile_wallet", owner_user_id=str(abba.user_id), is_private=True
    )
    groceries = admin.category_id("Groceries")
    return {
        "admin": admin,
        "abba": abba,
        "amma": amma,
        "shared": shared,
        "private": private,
        "groceries": groceries,
    }


def _expense(actor: Actor, account: str, groceries: str, amount: int) -> dict[str, Any]:
    return actor.create_tx(
        type="expense",
        amount_poisha=amount,
        category_id=groceries,
        account_id=account,
        occurred_at=WHEN,
    )


def _ids(response: Any) -> set[str]:
    assert response.status_code == 200, response.text
    return {row["id"] for row in response.json()}


def test_a_private_account_needs_an_owner(admin: Actor) -> None:
    response = admin.post("/accounts", {"name": "Secret", "kind": "cash", "is_private": True})
    assert response.status_code == 422
    shared = admin.create_account("Box")
    assert admin.patch(f"/accounts/{shared}", {"is_private": True}).status_code == 422


def test_only_owner_and_admins_see_the_account(family: dict[str, Any]) -> None:
    f = family
    assert f["private"] in _ids(f["admin"].get("/accounts"))
    assert f["private"] in _ids(f["abba"].get("/accounts"))
    assert f["private"] not in _ids(f["amma"].get("/accounts"))
    assert f["shared"] in _ids(f["amma"].get("/accounts"))
    # Not even its existence: 404 like an account of another household.
    assert f["amma"].get(f"/accounts/{f['private']}").status_code == 404
    assert f["amma"].get(f"/accounts/{f['private']}/balance").status_code == 404
    names = [a["name"] for a in f["amma"].get("/accounts/balances").json()["accounts"]]
    assert "Abba's bKash" not in names


def test_entries_on_a_private_account_are_hidden_everywhere(family: dict[str, Any]) -> None:
    f = family
    secret = _expense(f["abba"], f["private"], f["groceries"], 70_000)
    open_one = _expense(f["amma"], f["shared"], f["groceries"], 10_000)
    amma, abba, admin = f["amma"], f["abba"], f["admin"]

    def listed(actor: Actor) -> set[str]:
        return {t["id"] for t in actor.get("/transactions").json()["items"]}

    assert listed(amma) == {open_one["id"]}
    assert listed(abba) == listed(admin) == {open_one["id"], secret["id"]}
    assert amma.get(f"/transactions/{secret['id']}").status_code == 404

    def spent(actor: Actor) -> int:
        return int(actor.get("/reports/summary", params=RANGE).json()["expense_poisha"])

    # Other members' reports leave it out; the owner and admins see the full picture.
    assert spent(amma) == 10_000
    assert spent(abba) == spent(admin) == 80_000
    by_member = amma.get("/reports/summary", params=RANGE).json()["by_member"]
    assert all(row["user_id"] != str(abba.user_id) for row in by_member)
    series = amma.get("/reports/timeseries", params={**RANGE, "granularity": "month"}).json()
    assert sum(b["expense_poisha"] for b in series["buckets"]) == 10_000

    csv = amma.get("/transactions/export.csv", params=RANGE)
    assert csv.status_code == 200
    assert "700.00" not in csv.text and "100.00" in csv.text

    feed = amma.get("/sync/changes").json()
    assert {t["id"] for t in feed["items"]} == {open_one["id"]}


def test_a_member_cannot_use_or_change_someone_elses_private_account(
    family: dict[str, Any],
) -> None:
    f = family
    response = f["amma"].post(
        "/transactions",
        {
            "client_id": "00000000-0000-4000-8000-000000000001",
            "type": "expense",
            "amount_poisha": 100,
            "category_id": f["groceries"],
            "account_id": f["private"],
            "occurred_at": WHEN,
        },
    )
    assert response.status_code == 422
    assert "Abba" not in response.text  # the error does not reveal the account
    secret = _expense(f["admin"], f["private"], f["groceries"], 500)
    assert (
        f["amma"]
        .patch(f"/transactions/{secret['id']}", {"version": 1, "amount_poisha": 1})
        .status_code
        == 404
    )
    assert (
        f["amma"]
        .post(f"/transactions/{secret['id']}/void", {"version": 1, "reason": "x"})
        .status_code
        == 404
    )


def test_transfers_touching_a_private_account_are_hidden_but_shared_balances_stay_true(
    family: dict[str, Any],
) -> None:
    f = family
    transfer = f["abba"].create_tx(
        type="transfer",
        amount_poisha=25_000,
        account_id=f["private"],
        destination_account_id=f["shared"],
        occurred_at=WHEN,
    )
    assert f["amma"].get(f"/transactions/{transfer['id']}").status_code == 404
    # The shared account's balance is its real balance, for everyone.
    assert f["amma"].balance(f["shared"]) == f["admin"].balance(f["shared"]) == 25_000


def test_making_an_account_private_later_removes_it_from_other_phones(
    family: dict[str, Any],
) -> None:
    f = family
    wallet = f["admin"].create_account("Abba's cash", owner_user_id=str(f["abba"].user_id))
    entry = _expense(f["abba"], wallet, f["groceries"], 3_000)
    first = f["amma"].get("/sync/changes").json()
    assert entry["id"] in {t["id"] for t in first["items"]}

    assert f["admin"].patch(f"/accounts/{wallet}", {"is_private": True}).status_code == 200
    # A later edit puts the entry back in the feed, now as "removed" for Amma.
    f["abba"].patch(f"/transactions/{entry['id']}", {"version": 1, "note": "medicine"})
    later = f["amma"].get("/sync/changes", params={"cursor": first["next_cursor"]}).json()
    assert entry["id"] not in {t["id"] for t in later["items"]}
    assert entry["client_id"] in later["removed"]
    # The owner still receives it in full.
    assert entry["id"] in {t["id"] for t in f["abba"].get("/sync/changes").json()["items"]}


def test_sharing_an_account_again_clears_private_and_is_audited(family: dict[str, Any]) -> None:
    f = family
    response = f["admin"].patch(f"/accounts/{f['private']}", {"owner_user_id": None})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["is_shared"] is True and body["is_private"] is False
    assert f["private"] in _ids(f["amma"].get("/accounts"))
    events = f["admin"].get("/audit-events", params={"entity_type": "account"}).json()["items"]
    changes = [e["details"].get("changes", {}) for e in events if e["action"] == "account.updated"]
    assert any("is_private" in c and "owner_user_id" in c for c in changes)


def test_only_admins_change_privacy(family: dict[str, Any]) -> None:
    f = family
    assert f["abba"].patch(f"/accounts/{f['private']}", {"is_private": False}).status_code == 403
