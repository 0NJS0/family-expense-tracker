"""Access tokens (short-lived JWT) and opaque random secrets."""

import hashlib
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

import jwt

from app.timeutil import utcnow

_ALGORITHM = "HS256"
_ISSUER = "family-expense-tracker"


@dataclass(frozen=True)
class AccessClaims:
    user_id: uuid.UUID
    session_id: uuid.UUID


class InvalidTokenError(Exception):
    pass


def create_access_token(
    secret: str, user_id: uuid.UUID, session_id: uuid.UUID, ttl: timedelta
) -> tuple[str, datetime]:
    now = utcnow()
    expires_at = now + ttl
    payload = {
        "iss": _ISSUER,
        "sub": str(user_id),
        "sid": str(session_id),
        "typ": "access",
        "iat": int(now.timestamp()),
        "exp": int(expires_at.timestamp()),
    }
    return jwt.encode(payload, secret, algorithm=_ALGORITHM), expires_at


def decode_access_token(secret: str, token: str) -> AccessClaims:
    try:
        payload = jwt.decode(
            token,
            secret,
            algorithms=[_ALGORITHM],
            issuer=_ISSUER,
            options={"require": ["exp", "iat", "sub", "sid", "typ", "iss"]},
        )
        if payload["typ"] != "access":
            raise InvalidTokenError("wrong token type")
        return AccessClaims(user_id=uuid.UUID(payload["sub"]), session_id=uuid.UUID(payload["sid"]))
    except (jwt.PyJWTError, ValueError, KeyError) as exc:
        raise InvalidTokenError("invalid access token") from exc


def new_opaque_token() -> str:
    return secrets.token_urlsafe(32)


def hash_opaque_token(token: str) -> str:
    """Opaque tokens carry 256 bits of entropy, so a fast hash is sufficient."""
    return hashlib.sha256(token.encode()).hexdigest()
