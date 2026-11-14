"""Receipts: type from the bytes, metadata stripped, private, idempotent, audited."""

import io
from pathlib import Path
from typing import Any

from PIL import Image
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config import Settings
from tests.conftest import Actor, at

WHEN = at("2026-10-15T12:00:00")


def _photo(color: str = "red", exif_gps: bool = True, size: tuple[int, int] = (64, 48)) -> bytes:
    image = Image.new("RGB", size, color)
    out = io.BytesIO()
    exif = Image.Exif()
    if exif_gps:
        exif[0x010F] = "PhoneMaker"  # camera make
        exif[0x8825] = {2: (23.0, 48.0, 0.0), 4: (90.0, 24.0, 0.0)}  # GPS: Dhaka
    image.save(out, format="JPEG", exif=exif)
    return out.getvalue()


def _entry(actor: Actor, account: str | None = None) -> dict[str, Any]:
    return actor.create_tx(
        type="expense",
        amount_poisha=12_050,
        category_id=actor.category_id("Groceries"),
        account_id=account or actor.create_account(f"Cash {actor.username}"),
        occurred_at=WHEN,
    )


def _upload(actor: Actor, tx_id: str, data: bytes, expect: int = 201) -> dict[str, Any]:
    response = actor.client.post(
        f"/api/v1/transactions/{tx_id}/receipts",
        headers={**actor.headers, "Content-Type": "application/octet-stream"},
        content=data,
    )
    assert response.status_code == expect, response.text
    result: dict[str, Any] = response.json()
    return result


def test_photo_is_stored_privately_without_metadata(admin: Actor, settings: Settings) -> None:
    tx = _entry(admin)
    original = _photo()
    assert b"PhoneMaker" in original
    receipt = _upload(admin, tx["id"], original)
    assert receipt["content_type"] == "image/jpeg" and receipt["ocr_status"] == "pending"
    response = admin.get(f"/receipts/{receipt['id']}/file")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "private, no-store"
    assert response.headers["x-content-type-options"] == "nosniff"
    stored = response.content
    assert b"PhoneMaker" not in stored and b"Exif" not in stored  # location and camera data gone
    with Image.open(io.BytesIO(stored)) as image:
        assert image.size == (64, 48)
    files = list(Path(settings.receipts_dir).rglob("*.jpg"))
    assert any(f.read_bytes() == stored for f in files)


def test_type_is_decided_by_the_bytes(admin: Actor) -> None:
    tx = _entry(admin)
    _upload(admin, tx["id"], b"<html><script>alert(1)</script></html>", 422)
    _upload(admin, tx["id"], b"\xff\xd8\xff not really a jpeg", 422)
    _upload(admin, tx["id"], b"", 422)
    pdf = b"%PDF-1.4\n% receipt\n%%EOF\n"
    receipt = _upload(admin, tx["id"], pdf)
    assert receipt["content_type"] == "application/pdf"
    response = admin.get(f"/receipts/{receipt['id']}/file")
    assert response.content == pdf
    assert "sandbox" in response.headers["content-security-policy"]


def test_too_large_is_refused(admin: Actor, settings: Settings) -> None:
    tx = _entry(admin)
    _upload(admin, tx["id"], b"%PDF-" + b"0" * settings.max_receipt_bytes, 422)


def test_the_same_file_twice_is_one_receipt(admin: Actor, db: Session) -> None:
    tx = _entry(admin)
    data = _photo()
    first = _upload(admin, tx["id"], data)
    again = _upload(admin, tx["id"], data, expect=200)
    assert first["id"] == again["id"]
    assert len(admin.get(f"/transactions/{tx['id']}/receipts").json()) == 1
    assert (
        db.execute(
            text("SELECT count(*) FROM audit_events WHERE action = 'receipt.added'")
        ).scalar_one()
        == 1
    )


def test_who_may_add_see_and_remove(admin: Actor, join: Any) -> None:
    amma = join(admin, "amma", "member")
    viewer = join(admin, "nana", "viewer")
    mine = _entry(amma, admin.create_account("Shared cash"))
    theirs = _entry(admin)
    receipt = _upload(amma, mine["id"], _photo())
    _upload(amma, theirs["id"], _photo("blue"), 403)
    _upload(viewer, mine["id"], _photo("green"), 403)
    assert viewer.get(f"/receipts/{receipt['id']}/file").status_code == 200  # viewers can look
    assert (
        viewer.client.delete(
            f"/api/v1/receipts/{receipt['id']}", headers=viewer.headers
        ).status_code
        == 403
    )
    assert (
        amma.client.delete(f"/api/v1/receipts/{receipt['id']}", headers=amma.headers).status_code
        == 204
    )
    assert admin.get(f"/receipts/{receipt['id']}/file").status_code == 404


def test_private_account_receipts_are_hidden(admin: Actor, join: Any) -> None:
    abba = join(admin, "abba", "member")
    amma = join(admin, "amma", "member")
    secret = admin.create_account("Abba wallet", owner_user_id=str(abba.user_id), is_private=True)
    tx = abba.create_tx(
        type="expense",
        amount_poisha=100,
        category_id=admin.category_id("Groceries"),
        account_id=secret,
        occurred_at=WHEN,
    )
    receipt = _upload(abba, tx["id"], _photo())
    assert amma.get(f"/receipts/{receipt['id']}/file").status_code == 404
    assert amma.get(f"/transactions/{tx['id']}/receipts").status_code == 404


def test_other_households_cannot_reach_a_receipt(admin: Actor, make_actor: Any) -> None:
    receipt = _upload(admin, _entry(admin)["id"], _photo())
    other = make_actor("neighbour", "Next door")
    assert other.get(f"/receipts/{receipt['id']}/file").status_code == 404


def test_voided_entries_take_no_new_receipts(admin: Actor) -> None:
    tx = _entry(admin)
    admin.post(f"/transactions/{tx['id']}/void", {"version": 1, "reason": "x"})
    _upload(admin, tx["id"], _photo(), 409)


def test_tampered_file_is_detected(admin: Actor, settings: Settings, db: Session) -> None:
    receipt = _upload(admin, _entry(admin)["id"], _photo())
    key = db.execute(
        text("SELECT storage_key FROM receipts WHERE id = :i"), {"i": receipt["id"]}
    ).scalar_one()
    (Path(settings.receipts_dir) / key).write_bytes(b"changed")
    assert admin.get(f"/receipts/{receipt['id']}/file").status_code == 409
