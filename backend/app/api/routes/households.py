import uuid

from fastapi import APIRouter, status

from app.api.deps import Ctx, CurrentAuth, DbSession, rate_limited
from app.schemas.identity import (
    HouseholdCreate,
    HouseholdOut,
    InvitationAccept,
    InvitationCreate,
    InvitationCreated,
    MemberOut,
    MemberUpdate,
    UserOut,
)
from app.schemas.notifications import NotificationPreferencesOut, NotificationPreferencesUpdate
from app.services import households, notifications

router = APIRouter(tags=["households"])


@router.post("/households", response_model=HouseholdOut, status_code=status.HTTP_201_CREATED)
def create_household(body: HouseholdCreate, current: CurrentAuth, db: DbSession) -> HouseholdOut:
    household = households.create_household(db, current.user, body)
    return HouseholdOut.model_validate(household)


@router.get("/households/current", response_model=HouseholdOut)
def current_household(ctx: Ctx) -> HouseholdOut:
    return HouseholdOut.model_validate(ctx.household)


@router.get("/members", response_model=list[MemberOut])
def list_members(ctx: Ctx, db: DbSession) -> list[MemberOut]:
    return [
        MemberOut(
            user_id=m.user_id,
            username=m.user.username,
            display_name=m.user.display_name,
            role=m.role,
            is_active=m.is_active,
            joined_at=m.created_at,
        )
        for m in households.list_members(db, ctx)
    ]


@router.patch("/members/{user_id}", response_model=MemberOut)
def update_member(user_id: uuid.UUID, body: MemberUpdate, ctx: Ctx, db: DbSession) -> MemberOut:
    m = households.update_member(db, ctx, user_id, body)
    return MemberOut(
        user_id=m.user_id,
        username=m.user.username,
        display_name=m.user.display_name,
        role=m.role,
        is_active=m.is_active,
        joined_at=m.created_at,
    )


@router.post(
    "/members/invitations", response_model=InvitationCreated, status_code=status.HTTP_201_CREATED
)
def create_invitation(body: InvitationCreate, ctx: Ctx, db: DbSession) -> InvitationCreated:
    invitation, token = households.create_invitation(db, ctx, body)
    return InvitationCreated(
        id=invitation.id,
        role=invitation.role,
        display_name=invitation.display_name,
        expires_at=invitation.expires_at,
        token=token,
    )


@router.post(
    "/invitations/accept",
    response_model=UserOut,
    status_code=status.HTTP_201_CREATED,
    dependencies=[rate_limited("invitation")],
)
def accept_invitation(body: InvitationAccept, db: DbSession) -> UserOut:
    return UserOut.model_validate(households.accept_invitation(db, body))


@router.get("/me/notifications", response_model=NotificationPreferencesOut)
def get_notifications(ctx: Ctx, db: DbSession) -> NotificationPreferencesOut:
    """This person's reminder choices in the current household (defaults if never set)."""
    return notifications.get_preferences(db, ctx)


@router.put("/me/notifications", response_model=NotificationPreferencesOut)
def put_notifications(
    body: NotificationPreferencesUpdate, ctx: Ctx, db: DbSession
) -> NotificationPreferencesOut:
    return notifications.update_preferences(db, ctx, body)
