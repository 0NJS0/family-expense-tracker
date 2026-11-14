import uuid

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.errors import Conflict, NotFound, ValidationFailed
from app.models import Category, CategoryKind, Role
from app.schemas.finance import CategoryCreate, CategoryUpdate
from app.services import audit
from app.services.context import HouseholdContext


def list_categories(
    db: Session, ctx: HouseholdContext, *, kind: CategoryKind | None, include_archived: bool
) -> list[Category]:
    query = select(Category).where(Category.household_id == ctx.household_id)
    if kind is not None:
        query = query.where(Category.kind == kind.value)
    if not include_archived:
        query = query.where(Category.is_archived.is_(False))
    return list(db.scalars(query.order_by(Category.kind, Category.created_at, Category.name_en)))


def get_category(db: Session, ctx: HouseholdContext, category_id: uuid.UUID) -> Category:
    category = db.scalar(
        select(Category).where(
            Category.id == category_id, Category.household_id == ctx.household_id
        )
    )
    if category is None:
        raise NotFound("Category not found")
    return category


def create_category(db: Session, ctx: HouseholdContext, data: CategoryCreate) -> Category:
    ctx.require(Role.ADMIN)
    category = Category(
        household_id=ctx.household_id,
        kind=data.kind.value,
        name_en=data.name_en,
        name_bn=data.name_bn,
    )
    db.add(category)
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()
        raise Conflict("A category with this English name already exists") from exc
    audit.record(
        db,
        action="category.created",
        entity_type="category",
        entity_id=category.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"kind": category.kind, "name_en": category.name_en, "name_bn": category.name_bn},
    )
    db.commit()
    return category


def update_category(
    db: Session, ctx: HouseholdContext, category_id: uuid.UUID, data: CategoryUpdate
) -> Category:
    """Rename or archive. Kind cannot change, because existing transactions
    depend on it; archive and create a new category instead."""
    ctx.require(Role.ADMIN)
    category = get_category(db, ctx, category_id)
    changes: dict[str, dict[str, object]] = {}
    for field in ("name_en", "name_bn", "is_archived"):
        if field in data.model_fields_set:
            new = getattr(data, field)
            if new is None:
                raise ValidationFailed(f"{field} cannot be null")
            old = getattr(category, field)
            if new != old:
                changes[field] = {"from": old, "to": new}
                setattr(category, field, new)
    if not changes:
        return category
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()
        raise Conflict("A category with this English name already exists") from exc
    audit.record(
        db,
        action="category.updated",
        entity_type="category",
        entity_id=category.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"changes": changes},
    )
    db.commit()
    return category
