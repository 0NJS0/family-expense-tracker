"""Seed a throwaway household through the real API, or verify the phone's entry.

    python scripts/e2e_seed.py seed   URL USER PASSWORD
    python scripts/e2e_seed.py verify URL USER PASSWORD
Dev-only data; used by scripts/e2e.sh.
"""

import json
import sys
import urllib.request
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

mode, base, user, password = sys.argv[1:5]
api = base.rstrip("/") + "/api/v1"


def call(method: str, path: str, body: object = None, token: str = "", hh: str = "") -> object:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if hh:
        headers["X-Household-ID"] = hh
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(api + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 - local test server
        return json.loads(response.read() or b"null")


token = call("POST", "/auth/login", {"username": user, "password": password})["access_token"]  # type: ignore[index]
hh = call("GET", "/me", token=token)["memberships"][0]["household_id"]  # type: ignore[index]


def c(method: str, path: str, body: object = None) -> object:
    return call(method, path, body, token, hh)


if mode == "seed":
    accounts = {a["name"]: a["id"] for a in c("GET", "/accounts")}  # type: ignore[union-attr]
    cats = {x["name_en"]: x["id"] for x in c("GET", "/categories")}  # type: ignore[union-attr]
    now = datetime.now(ZoneInfo("Asia/Dhaka")) - timedelta(minutes=5)
    for days, kind, amount, category in [
        (0, "income", 5_000_000, "Salary"),
        (0, "expense", 125_075, "Groceries"),
        (1, "expense", 1_500_000, "Rent"),
        (2, "expense", 80_000, "Transport"),
    ]:
        c("POST", "/transactions", {
            "client_id": str(uuid.uuid4()), "type": kind, "amount_poisha": amount,
            "category_id": cats[category], "account_id": accounts["নগদ"],
            "occurred_at": (now - timedelta(days=days)).isoformat(),
        })
    c("POST", "/transactions", {
        "client_id": str(uuid.uuid4()), "type": "transfer", "amount_poisha": 50_000,
        "account_id": accounts["নগদ"], "destination_account_id": accounts["বিকাশ"],
        "occurred_at": now.isoformat(),
    })
    c("PUT", "/budgets", {"amount": "20000.00"})
    c("POST", "/recurring-rules", {
        "name": "Rent", "type": "expense", "amount": "15000", "category_id": cats["Rent"],
        "account_id": accounts["ব্যাংক"], "frequency": "monthly", "start_date": now.date().isoformat(),
    })
    print("seeded")
else:
    items = c("GET", "/transactions", None)["items"]  # type: ignore[index]
    phone = [t for t in items if t["note"] == "e2e test entry"]
    assert len(phone) == 1, f"expected exactly one phone entry, found {len(phone)}"
    assert phone[0]["amount_poisha"] == 125_075, phone[0]
    print("phone entry on the server: exactly 125075 poisha, once")
