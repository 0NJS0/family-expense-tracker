from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import api_router
from app.config import Settings, get_settings
from app.database import build_engine, build_sessionmaker
from app.errors import install_error_handlers
from app.logging_config import configure_logging, install_request_logging
from app.ratelimit import RateLimiter


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    app = FastAPI(
        title="Family Expense Tracker API",
        version="0.1.0",
        # Interactive docs are a development convenience only.
        docs_url=None if settings.is_production else "/docs",
        redoc_url=None,
        openapi_url=None if settings.is_production else "/openapi.json",
    )
    engine = build_engine(settings.database_url)
    app.state.settings = settings
    app.state.engine = engine
    app.state.sessionmaker = build_sessionmaker(engine)
    app.state.rate_limiters = {
        "login": RateLimiter(settings.login_rate_limit_per_minute),
        "refresh": RateLimiter(settings.login_rate_limit_per_minute * 3),
        "invitation": RateLimiter(settings.login_rate_limit_per_minute),
        "password": RateLimiter(settings.login_rate_limit_per_minute),
    }

    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "PATCH"],
            allow_headers=["Authorization", "Content-Type", "X-Household-ID"],
        )
    install_error_handlers(app)
    install_request_logging(app)
    app.include_router(api_router)
    return app
