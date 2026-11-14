"""Households, membership, invitations and first-run bootstrap."""

import uuid
from datetime import timedelta

from sqlalchemy import func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.errors import Conflict, NotFound, ValidationFailed
from app.models import (
    Account,
    AccountKind,
    Category,
    CategoryKind,
    Household,
    Invitation,
    Membership,
    Role,
    User,
)
from app.schemas.identity import (
    HouseholdCreate,
    InvitationAccept,
    InvitationCreate,
    MemberUpdate,
)
from app.security import tokens
from app.security.passwords import hash_password
from app.services import audit
from app.services.context import HouseholdContext
from app.timeutil import utcnow

INVITATION_TTL = timedelta(days=7)
# Arbitrary constant key for pg_advisory_xact_lock serialising bootstrap.
_BOOTSTRAP_LOCK_KEY = 7_340_211

# (name_en, name_bn)
DEFAULT_EXPENSE_CATEGORIES = [
    ("Groceries", "বাজার"),
    ("Food & dining", "খাবার"),
    ("Transport", "যাতায়াত"),
    ("Utilities", "বিদ্যুৎ, গ্যাস ও পানি"),
    ("Rent", "বাড়ি ভাড়া"),
    ("Health & medicine", "চিকিৎসা ও ওষুধ"),
    ("Education", "শিক্ষা"),
    ("Mobile & internet", "মোবাইল ও ইন্টারনেট"),
    ("Clothing", "কাপড়চোপড়"),
    ("Household items", "গৃহস্থালি জিনিস"),
    ("Gifts & donations", "উপহার ও দান"),
    ("Family support", "পারিবারিক সহায়তা"),
    ("Other expense", "অন্যান্য খরচ"),
]
DEFAULT_INCOME_CATEGORIES = [
    ("Salary", "বেতন"),
    ("Pension", "পেনশন"),
    ("Business", "ব্যবসা"),
    ("Rental income", "ভাড়া আয়"),
    ("Gifts received", "প্রাপ্ত উপহার"),
    ("Interest & profit", "মুনাফা"),
    ("Other income", "অন্যান্য আয়"),
]


# (name, kind, provider). Shared household accounts with no opening balance;
# no account numbers or other bank details are ever stored. Admins can rename
# them (e.g. "সোনালী ব্যাংক"), set a provider, a starting balance, or archive.
DEFAULT_ACCOUNTS = [
    ("নগদ", AccountKind.CASH, None),
    ("বিকাশ", AccountKind.MOBILE_WALLET, "bKash"),
    ("ব্যাংক", AccountKind.BANK, None),
]


class AlreadyBootstrapped(Exception):
    pass


def create_household(db: Session, user: User, data: HouseholdCreate) -> Household:
    household = Household(name=data.name, timezone=data.timezone, week_start=data.week_start)
    db.add(household)
    db.flush()
    db.add(Membership(household_id=household.id, user_id=user.id, role=Role.ADMIN.value))
    _seed_default_categories(db, household.id)
    db.flush()
    _seed_default_accounts(db, household.id)
    audit.record(
        db,
        action="household.created",
        entity_type="household",
        entity_id=household.id,
        household_id=household.id,
        actor_user_id=user.id,
        details={
            "name": household.name,
            "timezone": household.timezone,
            "default_accounts": [name for name, _, _ in DEFAULT_ACCOUNTS],
        },
    )
    db.commit()
    return household


def bootstrap(
    db: Session,
    *,
    username: str,
    display_name: str,
    password: str,
    household_name: str,
    timezone: str,
) -> tuple[User, Household]:
    """Create the first admin user and household. Refuses to run once any user
    exists, so repeating it cannot create a second admin or reset a password."""
    db.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": _BOOTSTRAP_LOCK_KEY})
    if db.scalar(select(func.count()).select_from(User)):
        raise AlreadyBootstrapped("users already exist; bootstrap has already been run")
    user = User(username=username, display_name=display_name, password_hash=hash_password(password))
    db.add(user)
    db.flush()
    audit.record(
        db,
        action="user.bootstrapped",
        entity_type="user",
        entity_id=user.id,
        household_id=None,
        actor_user_id=user.id,
        details={"username": username},
    )
    household = create_household(db, user, HouseholdCreate(name=household_name, timezone=timezone))
    return user, household


def list_members(db: Session, ctx: HouseholdContext) -> list[Membership]:
    return list(
        db.scalars(
            select(Membership)
            .where(Membership.household_id == ctx.household_id)
            .order_by(Membership.created_at)
        ).all()
    )


def is_member(db: Session, household_id: uuid.UUID, user_id: uuid.UUID) -> bool:
    return (
        db.scalar(
            select(Membership.id).where(
                Membership.household_id == household_id, Membership.user_id == user_id
            )
        )
        is not None
    )


def is_active_member(db: Session, household_id: uuid.UUID, user_id: uuid.UUID) -> bool:
    return (
        db.scalar(
            select(Membership.id).where(
                Membership.household_id == household_id,
                Membership.user_id == user_id,
                Membership.deactivated_at.is_(None),
            )
        )
        is not None
    )


def update_member(
    db: Session, ctx: HouseholdContext, user_id: uuid.UUID, data: MemberUpdate
) -> Membership:
    """Change a member's role or (de)activate them in this household."""
    ctx.require(Role.ADMIN)
    # Lock the household row: serialises concurrent membership changes so two
    # admins demoting each other cannot leave the household without an admin.
    db.get(Household, ctx.household_id, with_for_update=True)
    membership = db.scalar(
        select(Membership).where(
            Membership.household_id == ctx.household_id, Membership.user_id == user_id
        )
    )
    if membership is None:
        raise NotFound("Member not found")

    changes: dict[str, dict[str, object]] = {}
    if data.role is not None and data.role.value != membership.role:
        changes["role"] = {"from": membership.role, "to": data.role.value}
        membership.role = data.role.value
    if data.is_active is not None and data.is_active != membership.is_active:
        changes["is_active"] = {"from": membership.is_active, "to": data.is_active}
        membership.deactivated_at = None if data.is_active else utcnow()
    if not changes:
        return membership

    db.flush()
    active_admins = db.scalar(
        select(func.count())
        .select_from(Membership)
        .where(
            Membership.household_id == ctx.household_id,
            Membership.role == Role.ADMIN.value,
            Membership.deactivated_at.is_(None),
        )
    )
    if not active_admins:
        db.rollback()
        raise Conflict("The household must keep at least one active admin")

    audit.record(
        db,
        action="membership.updated",
        entity_type="membership",
        entity_id=membership.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"user_id": str(user_id), "changes": changes},
    )
    db.commit()
    return membership


def create_invitation(
    db: Session, ctx: HouseholdContext, data: InvitationCreate
) -> tuple[Invitation, str]:
    ctx.require(Role.ADMIN)
    raw_token = tokens.new_opaque_token()
    invitation = Invitation(
        household_id=ctx.household_id,
        role=data.role.value,
        display_name=data.display_name,
        token_hash=tokens.hash_opaque_token(raw_token),
        created_by_user_id=ctx.user_id,
        expires_at=utcnow() + INVITATION_TTL,
    )
    db.add(invitation)
    db.flush()
    audit.record(
        db,
        action="invitation.created",
        entity_type="invitation",
        entity_id=invitation.id,
        household_id=ctx.household_id,
        actor_user_id=ctx.user_id,
        details={"role": invitation.role, "display_name": invitation.display_name},
    )
    db.commit()
    return invitation, raw_token


def accept_invitation(db: Session, data: InvitationAccept) -> User:
    """Create a new user from a valid, unused, unexpired invitation."""
    invitation = db.scalar(
        select(Invitation)
        .where(Invitation.token_hash == tokens.hash_opaque_token(data.token))
        .with_for_update()
    )
    if invitation is None or invitation.accepted_at is not None:
        raise NotFound("Invitation is invalid or has already been used")
    if invitation.expires_at <= utcnow():
        raise NotFound("Invitation has expired")
    if db.scalar(select(User.id).where(User.username == data.username)) is not None:
        raise Conflict("That username is already taken")

    user = User(
        username=data.username,
        display_name=invitation.display_name,
        password_hash=hash_password(data.password),
        preferred_language=data.preferred_language.value,
    )
    db.add(user)
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()
        raise Conflict("That username is already taken") from exc
    db.add(Membership(household_id=invitation.household_id, user_id=user.id, role=invitation.role))
    invitation.accepted_at = utcnow()
    invitation.accepted_by_user_id = user.id
    audit.record(
        db,
        action="invitation.accepted",
        entity_type="membership",
        entity_id=user.id,
        household_id=invitation.household_id,
        actor_user_id=user.id,
        details={"role": invitation.role, "username": user.username},
    )
    db.commit()
    return user


def resolve_context(
    db: Session, user: User, requested_household_id: uuid.UUID | None
) -> HouseholdContext:
    """Pick the active household: the requested one if the user belongs to it,
    otherwise the user's only household."""
    query = select(Membership).where(
        Membership.user_id == user.id, Membership.deactivated_at.is_(None)
    )
    if requested_household_id is not None:
        query = query.where(Membership.household_id == requested_household_id)
    memberships = db.scalars(query).all()
    if not memberships:
        # Same response whether the household does not exist or the user is
        # not a member, so household IDs cannot be probed.
        raise NotFound("Household not found")
    if len(memberships) > 1:
        raise ValidationFailed("You belong to several households; send the X-Household-ID header")
    membership = memberships[0]
    return HouseholdContext(user=user, household=membership.household, role=Role(membership.role))


def _seed_default_accounts(db: Session, household_id: uuid.UUID) -> None:
    for name, kind, provider in DEFAULT_ACCOUNTS:
        db.add(Account(household_id=household_id, name=name, kind=kind.value, institution=provider))


def _seed_default_categories(db: Session, household_id: uuid.UUID) -> None:
    for kind, defaults in (
        (CategoryKind.EXPENSE, DEFAULT_EXPENSE_CATEGORIES),
        (CategoryKind.INCOME, DEFAULT_INCOME_CATEGORIES),
    ):
        for name_en, name_bn in defaults:
            db.add(
                Category(
                    household_id=household_id,
                    kind=kind.value,
                    name_en=name_en,
                    name_bn=name_bn,
                    is_default=True,
                )
            )
