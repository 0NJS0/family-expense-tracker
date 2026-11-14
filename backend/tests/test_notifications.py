"""Reminder choices: gentle defaults, per person and household, audited."""

from typing import Any

from tests.conftest import Actor


def test_defaults_do_not_nag(admin: Actor) -> None:
    prefs = admin.get("/me/notifications").json()
    assert prefs == {
        "daily_reminder": False,
        "missing_entry_reminder": True,
        "reminder_time": "20:00:00",
        "recurring_due": True,
        "budget_alerts": True,
    }


def test_each_person_sets_their_own(admin: Actor, join: Any) -> None:
    amma = join(admin, "amma", "member")
    viewer = join(admin, "nana", "viewer")
    changed = amma.client.put(
        "/api/v1/me/notifications",
        headers=amma.headers,
        json={"daily_reminder": True, "reminder_time": "19:30"},
    )
    assert changed.status_code == 200, changed.text
    assert changed.json()["reminder_time"] == "19:30:00"
    assert amma.get("/me/notifications").json()["daily_reminder"] is True
    assert admin.get("/me/notifications").json()["daily_reminder"] is False
    assert (
        viewer.client.put(
            "/api/v1/me/notifications", headers=viewer.headers, json={"recurring_due": False}
        ).status_code
        == 200
    )


def test_invalid_values_are_refused(admin: Actor) -> None:
    def put(body: dict[str, Any]) -> Any:
        return admin.client.put("/api/v1/me/notifications", headers=admin.headers, json=body)

    assert put({"reminder_time": "25:00"}).status_code == 422
    assert put({"daily_reminder": None}).status_code == 422
    assert put({"surprise": True}).status_code == 422
