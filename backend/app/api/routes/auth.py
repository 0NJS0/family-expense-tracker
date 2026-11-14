from typing import Annotated

from fastapi import APIRouter, Header, Response, status
from sqlalchemy import select

from app.api.deps import AppSettings, CurrentAuth, DbSession, rate_limited
from app.models import Membership
from app.schemas.identity import (
    LoginRequest,
    LogoutRequest,
    MembershipSummary,
    MeOut,
    PasswordChange,
    RefreshRequest,
    TokenPair,
    UserOut,
)
from app.services import auth

router = APIRouter(tags=["auth"])


@router.post("/auth/login", response_model=TokenPair, dependencies=[rate_limited("login")])
def login(
    body: LoginRequest,
    db: DbSession,
    settings: AppSettings,
    user_agent: Annotated[str | None, Header()] = None,
) -> TokenPair:
    return auth.login(
        db,
        settings,
        username=body.username,
        password=body.password,
        user_agent=body.device_name or user_agent,
    )


@router.post("/auth/refresh", response_model=TokenPair, dependencies=[rate_limited("refresh")])
def refresh(body: RefreshRequest, db: DbSession, settings: AppSettings) -> TokenPair:
    return auth.refresh(db, settings, refresh_token=body.refresh_token)


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(body: LogoutRequest, db: DbSession) -> Response:
    auth.logout(db, refresh_token=body.refresh_token)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/me/password",
    status_code=status.HTTP_204_NO_CONTENT,
    dependencies=[rate_limited("password")],
)
def change_password(body: PasswordChange, current: CurrentAuth, db: DbSession) -> Response:
    auth.change_password(
        db, current, current_password=body.current_password, new_password=body.new_password
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/me", response_model=MeOut)
def me(current: CurrentAuth, db: DbSession) -> MeOut:
    memberships = db.scalars(
        select(Membership).where(
            Membership.user_id == current.user.id, Membership.deactivated_at.is_(None)
        )
    ).all()
    return MeOut(
        user=UserOut.model_validate(current.user),
        memberships=[
            MembershipSummary(
                household_id=m.household_id,
                household_name=m.household.name,
                role=m.role,
            )
            for m in memberships
        ],
    )
