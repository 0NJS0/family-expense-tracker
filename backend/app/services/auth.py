"""Login, rotating refresh sessions, logout and access-token verification.

See docs/security.md for the session model.
"""

import logging
import uuid
from dataclasses import dataclass
from datetime import timedelta

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.config import Settings
from app.errors import Unauthorized, ValidationFailed
from app.models import AuthSession, RefreshToken, User
from app.schemas.identity import TokenPair
from app.security import tokens
from app.security.passwords import hash_password, needs_rehash, verify_password
from app.services import audit
from app.timeutil import utcnow

logger = logging.getLogger("app.auth")

_INVALID_LOGIN = "Invalid username or password"
_INVALID_REFRESH = "Session expired or invalid; please log in again"


@dataclass(frozen=True)
class Authenticated:
    user: User
    session_id: uuid.UUID


def login(
    db: Session, settings: Settings, *, username: str, password: str, user_agent: str | None
) -> TokenPair:
    user = db.scalar(select(User).where(User.username == username))
    # verify_password runs a full hash even for unknown users (timing equalisation).
    password_ok = verify_password(user.password_hash if user else None, password)
    if user is None or not password_ok or not user.is_active:
        logger.info("login_failed")
        raise Unauthorized(_INVALID_LOGIN)
    if needs_rehash(user.password_hash):
        user.password_hash = hash_password(password)

    now = utcnow()
    session = AuthSession(
        user_id=user.id,
        user_agent=(user_agent or "")[:200] or None,
        expires_at=now + timedelta(days=settings.session_max_age_days),
    )
    db.add(session)
    db.flush()
    pair = _issue_tokens(db, settings, user.id, session)
    audit.record(
        db,
        action="auth.login",
        entity_type="user",
        entity_id=user.id,
        household_id=None,
        actor_user_id=user.id,
        details={"session_id": str(session.id)},
    )
    db.commit()
    return pair


def refresh(db: Session, settings: Settings, *, refresh_token: str) -> TokenPair:
    now = utcnow()
    token = db.scalar(
        select(RefreshToken)
        .where(RefreshToken.token_hash == tokens.hash_opaque_token(refresh_token))
        .with_for_update()
    )
    if token is None:
        raise Unauthorized(_INVALID_REFRESH)
    session = db.get(AuthSession, token.session_id, with_for_update=True)
    if session is None or session.revoked_at is not None or session.expires_at <= now:
        raise Unauthorized(_INVALID_REFRESH)
    if token.expires_at <= now:
        raise Unauthorized(_INVALID_REFRESH)

    if token.used_at is not None:
        if now - token.used_at > timedelta(seconds=settings.refresh_reuse_grace_seconds):
            # A rotated token presented long after rotation: treat as theft and
            # revoke the whole session so the attacker and victim are both logged out.
            session.revoked_at = now
            session.revoke_reason = "refresh_reuse"
            audit.record(
                db,
                action="auth.refresh_reuse_detected",
                entity_type="auth_session",
                entity_id=session.id,
                household_id=None,
                actor_user_id=session.user_id,
            )
            db.commit()
            logger.warning("refresh_token_reuse_detected")
            raise Unauthorized(_INVALID_REFRESH)
        # Retry after a lost response: invalidate the token issued by the first
        # attempt (which the client never received) and issue a fresh one.
        db.execute(
            update(RefreshToken)
            .where(RefreshToken.session_id == session.id, RefreshToken.used_at.is_(None))
            .values(used_at=now)
        )
    else:
        token.used_at = now

    user = db.get(User, session.user_id)
    if user is None or not user.is_active:
        raise Unauthorized(_INVALID_REFRESH)
    pair = _issue_tokens(db, settings, user.id, session)
    db.commit()
    return pair


def logout(db: Session, *, refresh_token: str) -> None:
    """Revoke the session owning ``refresh_token``. Unknown tokens are ignored
    so the endpoint cannot be used to probe for valid tokens."""
    token = db.scalar(
        select(RefreshToken).where(
            RefreshToken.token_hash == tokens.hash_opaque_token(refresh_token)
        )
    )
    if token is None:
        return
    session = db.get(AuthSession, token.session_id, with_for_update=True)
    if session is None or session.revoked_at is not None:
        return
    session.revoked_at = utcnow()
    session.revoke_reason = "logout"
    audit.record(
        db,
        action="auth.logout",
        entity_type="auth_session",
        entity_id=session.id,
        household_id=None,
        actor_user_id=session.user_id,
    )
    db.commit()


def change_password(
    db: Session, current: Authenticated, *, current_password: str, new_password: str
) -> None:
    """Change the caller's password and log out every other device."""
    user = current.user
    if not verify_password(user.password_hash, current_password):
        raise Unauthorized("Current password is incorrect")
    if current_password == new_password:
        raise ValidationFailed("New password must be different from the current one")
    user.password_hash = hash_password(new_password)
    revoked = db.execute(
        update(AuthSession)
        .where(
            AuthSession.user_id == user.id,
            AuthSession.id != current.session_id,
            AuthSession.revoked_at.is_(None),
        )
        .values(revoked_at=utcnow(), revoke_reason="password_changed")
    )
    audit.record(
        db,
        action="user.password_changed",
        entity_type="user",
        entity_id=user.id,
        household_id=None,
        actor_user_id=user.id,
        details={"other_sessions_revoked": revoked.rowcount},  # type: ignore[attr-defined]
    )
    db.commit()


def authenticate_access_token(db: Session, settings: Settings, token: str) -> Authenticated:
    try:
        claims = tokens.decode_access_token(settings.jwt_secret.get_secret_value(), token)
    except tokens.InvalidTokenError as exc:
        raise Unauthorized("Invalid or expired access token") from exc
    session = db.get(AuthSession, claims.session_id)
    if (
        session is None
        or session.user_id != claims.user_id
        or session.revoked_at is not None
        or session.expires_at <= utcnow()
    ):
        raise Unauthorized("Session is no longer valid")
    user = db.get(User, claims.user_id)
    if user is None or not user.is_active:
        raise Unauthorized("Session is no longer valid")
    return Authenticated(user=user, session_id=session.id)


def _issue_tokens(
    db: Session, settings: Settings, user_id: uuid.UUID, session: AuthSession
) -> TokenPair:
    now = utcnow()
    access, access_expires = tokens.create_access_token(
        settings.jwt_secret.get_secret_value(),
        user_id,
        session.id,
        timedelta(minutes=settings.access_token_ttl_minutes),
    )
    raw_refresh = tokens.new_opaque_token()
    refresh_expires = min(now + timedelta(days=settings.refresh_token_ttl_days), session.expires_at)
    db.add(
        RefreshToken(
            session_id=session.id,
            token_hash=tokens.hash_opaque_token(raw_refresh),
            expires_at=refresh_expires,
        )
    )
    return TokenPair(
        access_token=access,
        access_token_expires_at=access_expires,
        refresh_token=raw_refresh,
        refresh_token_expires_at=refresh_expires,
    )
