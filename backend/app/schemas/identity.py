import uuid
from datetime import datetime
from typing import Annotated
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import AfterValidator, Field

from app.models import Language, Role
from app.schemas.common import ApiModel, DisplayName, Password, ShortText, Username


def _valid_timezone(value: str) -> str:
    try:
        ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ValueError("unknown timezone") from exc
    return value


class LoginRequest(ApiModel):
    username: Username
    password: Annotated[str, Field(min_length=1, max_length=256)]
    device_name: Annotated[str | None, Field(max_length=100)] = None


class RefreshRequest(ApiModel):
    refresh_token: Annotated[str, Field(min_length=1, max_length=200)]


class LogoutRequest(ApiModel):
    refresh_token: Annotated[str, Field(min_length=1, max_length=200)]


class TokenPair(ApiModel):
    token_type: str = "bearer"  # noqa: S105 - OAuth token type, not a secret
    access_token: str
    access_token_expires_at: datetime
    refresh_token: str
    refresh_token_expires_at: datetime


class UserOut(ApiModel):
    id: uuid.UUID
    username: str
    display_name: str
    preferred_language: Language


class MembershipSummary(ApiModel):
    household_id: uuid.UUID
    household_name: str
    role: Role


class MeOut(ApiModel):
    user: UserOut
    memberships: list[MembershipSummary]


class HouseholdCreate(ApiModel):
    name: ShortText
    timezone: Annotated[str, AfterValidator(_valid_timezone)] = "Asia/Dhaka"
    # Monday = 0 ... Sunday = 6. Default Saturday, the first day of the
    # week on Bangladeshi calendars. Configurable per household.
    week_start: Annotated[int, Field(ge=0, le=6)] = 5


class HouseholdOut(ApiModel):
    id: uuid.UUID
    name: str
    timezone: str
    currency: str
    week_start: int
    created_at: datetime


class MemberOut(ApiModel):
    user_id: uuid.UUID
    username: str
    display_name: str
    role: Role
    is_active: bool
    joined_at: datetime


class MemberUpdate(ApiModel):
    role: Role | None = None
    is_active: bool | None = None


class PasswordChange(ApiModel):
    current_password: Annotated[str, Field(min_length=1, max_length=256)]
    new_password: Password


class InvitationCreate(ApiModel):
    display_name: DisplayName
    role: Role = Role.MEMBER


class InvitationCreated(ApiModel):
    id: uuid.UUID
    role: Role
    display_name: str
    expires_at: datetime
    # Shown once. Only a hash is stored on the server.
    token: str


class InvitationAccept(ApiModel):
    token: Annotated[str, Field(min_length=1, max_length=200)]
    username: Username
    password: Password
    preferred_language: Language = Language.BANGLA
