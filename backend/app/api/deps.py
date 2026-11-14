import uuid
from typing import Annotated

from fastapi import Depends, Header, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.config import Settings
from app.database import get_db
from app.errors import TooManyRequests, Unauthorized
from app.ratelimit import RateLimiter
from app.services import auth, households
from app.services.context import HouseholdContext

_bearer = HTTPBearer(auto_error=False)

DbSession = Annotated[Session, Depends(get_db)]


def get_settings(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


AppSettings = Annotated[Settings, Depends(get_settings)]


def get_current_auth(
    db: DbSession,
    settings: AppSettings,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> auth.Authenticated:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise Unauthorized("Authentication required")
    return auth.authenticate_access_token(db, settings, credentials.credentials)


CurrentAuth = Annotated[auth.Authenticated, Depends(get_current_auth)]


def get_household_context(
    db: DbSession,
    current: CurrentAuth,
    x_household_id: Annotated[uuid.UUID | None, Header()] = None,
) -> HouseholdContext:
    return households.resolve_context(db, current.user, x_household_id)


Ctx = Annotated[HouseholdContext, Depends(get_household_context)]


def rate_limited(name: str):  # type: ignore[no-untyped-def]
    """Dependency enforcing the app's limiter named ``name`` per client address."""

    def _check(request: Request) -> None:
        limiter: RateLimiter = request.app.state.rate_limiters[name]
        client = request.client.host if request.client else "unknown"
        if not limiter.allow(client):
            raise TooManyRequests("Too many attempts; please wait a minute and try again")

    return Depends(_check)
