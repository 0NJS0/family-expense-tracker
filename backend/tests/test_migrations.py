import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import URL
from sqlalchemy.exc import DBAPIError

from app.models import Base
from tests import pgutil


def test_upgrade_downgrade_upgrade_on_clean_database() -> None:
    with pgutil.temporary_database() as url:
        pgutil.migrate(url)
        pgutil.migrate(url, "base", downgrade=True)
        engine = create_engine(url)
        with engine.connect() as conn:
            remaining = set(inspect(conn).get_table_names()) - {"alembic_version"}
        assert remaining == set()
        pgutil.migrate(url)
        with engine.connect() as conn:
            assert "transactions" in inspect(conn).get_table_names()
        engine.dispose()


def test_models_match_migrations(database_url: URL) -> None:
    """Fails if a model change was made without a matching migration."""
    engine = create_engine(database_url)
    with engine.connect() as conn:
        context = MigrationContext.configure(conn, opts={"compare_type": True})
        diff = compare_metadata(context, Base.metadata)
    engine.dispose()
    assert diff == []


def test_audit_events_are_append_only(database_url: URL) -> None:
    engine = create_engine(database_url)
    with engine.begin() as conn:
        conn.execute(
            text(
                "INSERT INTO audit_events (id, action, entity_type, details) "
                "VALUES (gen_random_uuid(), 'test', 'test', '{}')"
            )
        )
    for statement in ("UPDATE audit_events SET action = 'x'", "DELETE FROM audit_events"):
        with pytest.raises(DBAPIError, match="append-only"), engine.begin() as conn:
            conn.execute(text(statement))
    engine.dispose()
