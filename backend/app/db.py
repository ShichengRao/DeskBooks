from __future__ import annotations

from collections.abc import Generator
from pathlib import Path
from threading import RLock

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker

from .app_paths import DATA_DIR

_lock = RLock()
_engine: Engine | None = None
_engine_path: Path | None = None
_session_factory: sessionmaker[Session] | None = None


def _enable_sqlite_pragmas(dbapi_connection, _):
    cur = dbapi_connection.cursor()
    cur.execute("PRAGMA foreign_keys = ON")
    cur.execute("PRAGMA journal_mode = WAL")
    cur.execute("PRAGMA synchronous = NORMAL")
    cur.close()


def _active_db_path() -> Path:
    from .profiles import get_active_profile

    return get_active_profile().db_path


def get_engine() -> Engine:
    global _engine, _engine_path, _session_factory
    db_path = _active_db_path()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with _lock:
        if _engine is not None and _engine_path == db_path:
            return _engine
        if _engine is not None:
            _engine.dispose()
        engine = create_engine(
            f"sqlite:///{db_path}",
            echo=False,
            future=True,
            connect_args={"check_same_thread": False},
        )
        event.listen(engine, "connect", _enable_sqlite_pragmas)
        _engine = engine
        _engine_path = db_path
        _session_factory = sessionmaker(
            bind=engine,
            autoflush=False,
            autocommit=False,
            future=True,
        )
        return engine


def reset_engine() -> None:
    global _engine, _engine_path, _session_factory
    with _lock:
        if _engine is not None:
            _engine.dispose()
        _engine = None
        _engine_path = None
        _session_factory = None


def SessionLocal() -> Session:
    global _session_factory
    get_engine()
    assert _session_factory is not None
    return _session_factory()


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def init_db() -> None:
    from . import models  # noqa: F401  ensure models are imported

    models.Base.metadata.create_all(bind=get_engine())
