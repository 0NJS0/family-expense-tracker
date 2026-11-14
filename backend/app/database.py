from collections.abc import Iterator

from fastapi import Request
from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker


def build_engine(database_url: str) -> Engine:
    # hide_parameters: database errors are logged, and their values can be notes or hashes.
    return create_engine(
        database_url, pool_pre_ping=True, pool_size=5, max_overflow=5, hide_parameters=True
    )


def build_sessionmaker(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def get_db(request: Request) -> Iterator[Session]:
    """Yield a session per request. Services commit explicitly; anything
    uncommitted when the request ends is rolled back."""
    factory: sessionmaker[Session] = request.app.state.sessionmaker
    with factory() as session:
        try:
            yield session
        finally:
            session.rollback()
