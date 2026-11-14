import pytest
from pydantic import SecretStr, ValidationError

from app.config import INSECURE_DEV_SECRET, Environment, Settings
from app.database import build_engine

DB = "postgresql+psycopg://u:p@localhost/db"
STRONG = SecretStr("s" * 48)


def test_production_rejects_default_secret() -> None:
    with pytest.raises(ValidationError, match="FET_JWT_SECRET"):
        Settings(environment=Environment.PRODUCTION, database_url=DB)


def test_production_rejects_explicit_dev_secret() -> None:
    with pytest.raises(ValidationError, match="FET_JWT_SECRET"):
        Settings(
            environment=Environment.PRODUCTION,
            database_url=DB,
            jwt_secret=SecretStr(INSECURE_DEV_SECRET),
        )


def test_production_rejects_short_secret() -> None:
    with pytest.raises(ValidationError, match="FET_JWT_SECRET"):
        Settings(environment=Environment.PRODUCTION, database_url=DB, jwt_secret=SecretStr("short"))


def test_production_rejects_wildcard_cors() -> None:
    with pytest.raises(ValidationError, match="CORS"):
        Settings(
            environment=Environment.PRODUCTION,
            database_url=DB,
            jwt_secret=STRONG,
            cors_origins=["*"],
        )


def test_production_rejects_plain_http_cors() -> None:
    with pytest.raises(ValidationError, match="https"):
        Settings(
            environment=Environment.PRODUCTION,
            database_url=DB,
            jwt_secret=STRONG,
            cors_origins=["http://dashboard.home"],
        )


def test_production_accepts_safe_configuration() -> None:
    settings = Settings(
        environment=Environment.PRODUCTION,
        database_url=DB,
        jwt_secret=STRONG,
        cors_origins=["https://money.home.arpa"],
    )
    assert settings.is_production


def test_database_url_is_required(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("FET_DATABASE_URL", raising=False)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)  # type: ignore[call-arg]


def test_cors_origins_parsed_from_comma_separated_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FET_DATABASE_URL", DB)
    monkeypatch.setenv("FET_CORS_ORIGINS", "https://a.home, https://b.home")
    assert Settings(_env_file=None).cors_origins == ["https://a.home", "https://b.home"]  # type: ignore[call-arg]


def test_unknown_timezone_rejected() -> None:
    with pytest.raises(ValidationError, match="timezone"):
        Settings(database_url=DB, default_timezone="Mars/Olympus")


def test_production_hides_api_docs(settings: Settings) -> None:
    from fastapi.testclient import TestClient

    from app.main import create_app

    prod = settings.model_copy(update={"environment": Environment.PRODUCTION, "jwt_secret": STRONG})
    app = create_app(prod)
    try:
        client = TestClient(app)
        assert client.get("/docs").status_code == 404
        assert client.get("/openapi.json").status_code == 404
    finally:
        app.state.engine.dispose()


def test_database_errors_do_not_carry_values_into_logs() -> None:
    # Error text is logged; with parameters it could contain notes or password hashes.
    engine = build_engine("postgresql+psycopg://user@127.0.0.1:1/none")
    assert engine.hide_parameters is True
