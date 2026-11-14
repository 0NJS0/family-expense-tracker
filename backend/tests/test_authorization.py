"""Role-based authorization and household isolation."""

import uuid

from tests.conftest import Actor, at


def _expense(actor: Actor, account: str, amount: int = 1000, **extra):  # type: ignore[no-untyped-def]
    return actor.create_tx(
        type="expense",
        amount_poisha=amount,
        category_id=actor.category_id("Groceries"),
        account_id=account,
        occurred_at=at("2026-03-05T12:00:00"),
        **extra,
    )


# --- roles ------------------------------------------------------------------


def test_viewer_is_read_only(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    cash = admin.create_account("Cash")
    tx = _expense(admin, cash)
    viewer: Actor = join(admin, "nana", "viewer")

    assert viewer.get("/transactions").status_code == 200
    assert viewer.get("/reports/summary").status_code == 200
    assert viewer.get(f"/accounts/{cash}/balance").status_code == 200

    denied = viewer.post(
        "/transactions",
        {
            "client_id": str(uuid.uuid4()),
            "type": "expense",
            "amount_poisha": 100,
            "category_id": admin.category_id("Groceries"),
            "account_id": cash,
            "occurred_at": at("2026-03-05T12:00:00"),
        },
    )
    assert denied.status_code == 403
    assert denied.json()["error"]["code"] == "forbidden"
    assert (
        viewer.post(f"/transactions/{tx['id']}/void", {"version": 1, "reason": "x"}).status_code
        == 403
    )
    assert viewer.post("/accounts", {"name": "Mine", "kind": "cash"}).status_code == 403
    assert viewer.post("/members/invitations", {"display_name": "X"}).status_code == 403


def test_member_permissions(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    cash = admin.create_account("Cash")
    member: Actor = join(admin, "amma", "member")
    own = _expense(member, cash)
    admins = _expense(admin, cash)

    assert (
        member.patch(
            f"/transactions/{own['id']}", {"version": 1, "amount_poisha": 1500}
        ).status_code
        == 200
    )
    assert (
        member.patch(
            f"/transactions/{admins['id']}", {"version": 1, "amount_poisha": 1}
        ).status_code
        == 403
    )
    assert (
        member.post(f"/transactions/{admins['id']}/void", {"version": 1, "reason": "x"}).status_code
        == 403
    )
    assert member.post("/accounts", {"name": "Wallet", "kind": "cash"}).status_code == 403
    assert (
        member.post("/categories", {"kind": "expense", "name_en": "X", "name_bn": "X"}).status_code
        == 403
    )
    assert member.post("/members/invitations", {"display_name": "X"}).status_code == 403
    assert member.get("/audit-events").status_code == 403


def test_admin_can_correct_members_transaction(admin: Actor, join) -> None:  # type: ignore[no-untyped-def]
    cash = admin.create_account("Cash")
    member: Actor = join(admin, "amma", "member")
    tx = _expense(member, cash)
    response = admin.patch(f"/transactions/{tx['id']}", {"version": 1, "amount_poisha": 900})
    assert response.status_code == 200
    assert response.json()["created_by_user_id"] == str(member.user_id)


# --- household isolation ----------------------------------------------------


def test_other_household_data_is_invisible(admin: Actor, make_actor) -> None:  # type: ignore[no-untyped-def]
    cash = admin.create_account("Cash")
    tx = _expense(admin, cash, amount=5000)
    outsider: Actor = make_actor("neighbour", "Neighbours")

    assert outsider.get(f"/transactions/{tx['id']}").status_code == 404
    assert outsider.get(f"/accounts/{cash}").status_code == 404
    assert outsider.get(f"/accounts/{cash}/balance").status_code == 404
    assert (
        outsider.patch(f"/transactions/{tx['id']}", {"version": 1, "amount_poisha": 1}).status_code
        == 404
    )
    assert (
        outsider.post(f"/transactions/{tx['id']}/void", {"version": 1, "reason": "x"}).status_code
        == 404
    )
    assert outsider.get("/transactions").json()["total"] == 0
    assert (
        outsider.get(
            "/reports/summary", params={"date_from": "2026-03-01", "date_to": "2026-03-31"}
        ).json()["expense_poisha"]
        == 0
    )
    admin_category_ids = {c["id"] for c in admin.get("/categories").json()}
    assert admin_category_ids.isdisjoint({c["id"] for c in outsider.get("/categories").json()})


def test_cannot_act_in_household_by_header_spoofing(admin: Actor, make_actor) -> None:  # type: ignore[no-untyped-def]
    outsider: Actor = make_actor("neighbour", "Neighbours")
    spoofed = {
        "Authorization": f"Bearer {outsider.access_token}",
        "X-Household-ID": str(admin.household_id),
    }
    response = outsider.client.get("/api/v1/transactions", headers=spoofed)
    assert response.status_code == 404


def test_cannot_reference_other_households_objects(admin: Actor, make_actor) -> None:  # type: ignore[no-untyped-def]
    their_cash = admin.create_account("Cash")
    outsider: Actor = make_actor("neighbour", "Neighbours")
    my_cash = outsider.create_account("Cash")

    # Their account as source, my account as source with their category, and
    # their member as attributed person: all rejected.
    for fields in (
        {"account_id": their_cash, "category_id": outsider.category_id("Groceries")},
        {"account_id": my_cash, "category_id": admin.category_id("Groceries")},
        {
            "account_id": my_cash,
            "category_id": outsider.category_id("Groceries"),
            "attributed_user_id": str(admin.user_id),
        },
    ):
        outsider.create_tx(
            expect=422,
            type="expense",
            amount_poisha=100,
            occurred_at=at("2026-03-05T12:00:00"),
            **fields,
        )
    outsider.create_tx(
        expect=422,
        type="transfer",
        amount_poisha=100,
        account_id=my_cash,
        destination_account_id=their_cash,
        occurred_at=at("2026-03-05T12:00:00"),
    )
    assert (
        outsider.get("/reports/summary", params={"member_id": str(admin.user_id)}).status_code
        == 422
    )


def test_same_client_id_in_different_households_is_independent(admin: Actor, make_actor) -> None:  # type: ignore[no-untyped-def]
    outsider: Actor = make_actor("neighbour", "Neighbours")
    client_id = str(uuid.uuid4())
    a = _expense(admin, admin.create_account("Cash"), client_id=client_id)
    b = _expense(outsider, outsider.create_account("Cash"), client_id=client_id)
    assert a["id"] != b["id"]
