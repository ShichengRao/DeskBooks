from __future__ import annotations

from collections.abc import Generator
from pathlib import Path
from threading import RLock

from fastapi import HTTPException, Request
from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker

# Every tab states which profile it's on (the layout sets this header on
# all API calls). Requests route to that profile's database, so two
# windows can live on two profiles at once; the registry's "active"
# profile is only the default — for tabs that haven't loaded yet, for
# curl, and for the automation CLI.
PROFILE_HEADER = "X-DeskBooks-Profile"

_lock = RLock()
_engines: dict[Path, Engine] = {}
_factories: dict[Path, sessionmaker[Session]] = {}


def _enable_sqlite_pragmas(dbapi_connection, _):
    cur = dbapi_connection.cursor()
    cur.execute("PRAGMA foreign_keys = ON")
    cur.execute("PRAGMA journal_mode = WAL")
    cur.execute("PRAGMA synchronous = NORMAL")
    cur.close()


def _active_db_path() -> Path:
    from .profiles import get_active_profile

    return get_active_profile().db_path


# create_all only creates missing TABLES; columns added to an existing model
# need an explicit ALTER. Additive, constant-default columns only — anything
# fancier deserves a real migration tool.
_ADDITIVE_COLUMNS: tuple[tuple[str, str, str], ...] = (
    ("fire_settings", "birth_year", "INTEGER"),
    ("fire_settings", "retirement_age", "INTEGER NOT NULL DEFAULT 65"),
    ("fire_settings", "growth_property", "NUMERIC NOT NULL DEFAULT 0.0100"),
    ("transactions", "budget_date", "DATE"),
    ("transactions", "kind_before_pair", "VARCHAR"),
    ("rules", "set_is_excluded_from_totals", "BOOLEAN"),
    ("rules", "pair_with_account_id", "INTEGER"),
    ("rules", "pair_within_days", "INTEGER"),
)


def _apply_additive_columns(engine: Engine) -> None:
    with engine.begin() as conn:
        for table, column, ddl in _ADDITIVE_COLUMNS:
            existing = {row[1] for row in conn.exec_driver_sql(f"PRAGMA table_info({table})")}
            if existing and column not in existing:
                conn.exec_driver_sql(f"ALTER TABLE {table} ADD COLUMN {column} {ddl}")


# The mirror image: columns dropped from the models. create_all never
# removes anything, so a database created before the removal keeps them —
# and a leftover NOT NULL column with no default makes every INSERT into
# that table fail, since nothing supplies a value any more. Databases
# created after the removal never had the column, so this is a no-op for
# them. Only list columns no model or query references.
_DROPPED_COLUMNS: tuple[tuple[str, str], ...] = (
    ("accounts", "is_liquid"),
    ("accounts", "is_taxable"),
)


def _drop_removed_columns(engine: Engine) -> None:
    with engine.begin() as conn:
        for table, column in _DROPPED_COLUMNS:
            existing = {row[1] for row in conn.exec_driver_sql(f"PRAGMA table_info({table})")}
            if column in existing:
                conn.exec_driver_sql(f"ALTER TABLE {table} DROP COLUMN {column}")


def engine_for(db_path: Path) -> Engine:
    """One cached engine per database file; tables ensured on first use."""
    from . import models  # noqa: F401  ensure models are imported

    with _lock:
        engine = _engines.get(db_path)
        if engine is not None:
            return engine
        db_path.parent.mkdir(parents=True, exist_ok=True)
        engine = create_engine(
            f"sqlite:///{db_path}",
            echo=False,
            future=True,
            connect_args={"check_same_thread": False},
        )
        event.listen(engine, "connect", _enable_sqlite_pragmas)
        # create_all stays inside the lock so a second request can't grab
        # the engine before its tables exist.
        models.Base.metadata.create_all(bind=engine)
        _apply_additive_columns(engine)
        _drop_removed_columns(engine)
        _engines[db_path] = engine
        _factories[db_path] = sessionmaker(
            bind=engine,
            autoflush=False,
            autocommit=False,
            future=True,
        )
        return engine


def get_engine() -> Engine:
    return engine_for(_active_db_path())


def reset_engine(db_path: Path | None = None) -> None:
    """Dispose one profile's engine (after a restore replaced its file), or
    every engine when no path is given."""
    with _lock:
        paths = [db_path] if db_path is not None else list(_engines)
        for path in paths:
            engine = _engines.pop(path, None)
            _factories.pop(path, None)
            if engine is not None:
                engine.dispose()


def SessionLocal() -> Session:
    """Session on the active profile — the CLI and startup path."""
    db_path = _active_db_path()
    engine_for(db_path)
    return _factories[db_path]()


def get_request_profile(request: Request):
    """The profile a request belongs to: the tab's header claim when
    present (404 if that profile no longer exists), else the active one."""
    from .profiles import get_active_profile, list_profiles

    slug = request.headers.get(PROFILE_HEADER)
    if not slug:
        return get_active_profile()
    for profile in list_profiles():
        if profile.slug == slug:
            return profile
    raise HTTPException(
        404,
        {
            "code": "profile_unknown",
            "detail": f"profile '{slug}' no longer exists (deleted in another tab?)",
            "expected_profile": slug,
        },
    )


def get_db(request: Request) -> Generator[Session, None, None]:
    profile = get_request_profile(request)
    engine_for(profile.db_path)
    db = _factories[profile.db_path]()
    try:
        yield db
    finally:
        db.close()


def init_db() -> None:
    get_engine()
