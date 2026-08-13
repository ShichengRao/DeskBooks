"""A database created before a column was dropped from the models must
still accept inserts.

create_all only ever adds; it does not remove. A profile carried forward
from an older release keeps its dropped columns, and a leftover NOT NULL
column with no default breaks every insert into that table because no
model supplies a value any more. Fresh databases never had the column,
which is why this only ever bites long-lived real profiles.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy import Engine, create_engine, text
from sqlalchemy.exc import IntegrityError

from app import db as db_module

INSERT_ACCOUNT = (
    "INSERT INTO accounts (name, account_category, type, currency, "
    "sign_convention, is_closed, sort_order) VALUES "
    "('Giving Fund', 'investment', 'brokerage', 'USD', "
    "'outflow_negative', 0, 0)"
)


def _legacy_accounts_db(tmp_path: Path) -> Path:
    """A database whose accounts table still carries the dropped columns."""
    from app.models import Base

    path = tmp_path / "legacy.db"
    engine = create_engine(f"sqlite:///{path}", future=True)
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        for column in ("is_liquid", "is_taxable"):
            conn.execute(
                text(f"ALTER TABLE accounts ADD COLUMN {column} BOOLEAN NOT NULL DEFAULT 0")
            )
        # DEFAULT is what makes ADD COLUMN legal at all; strip it so the
        # column matches the real legacy shape — NOT NULL with no default,
        # which is what makes the insert fail.
        conn.execute(text("PRAGMA writable_schema = ON"))
        conn.execute(
            text(
                "UPDATE sqlite_master SET sql = replace(sql, ' DEFAULT 0', '') "
                "WHERE type = 'table' AND name = 'accounts'"
            )
        )
        conn.execute(text("PRAGMA writable_schema = OFF"))
    engine.dispose()
    return path


def _accounts_columns(engine: Engine) -> set[str]:
    with engine.begin() as conn:
        return {row[1] for row in conn.exec_driver_sql("PRAGMA table_info(accounts)")}


def test_legacy_database_rejects_inserts_without_the_migration(tmp_path):
    """Guards the premise: untouched, the legacy shape really does fail."""
    engine = create_engine(f"sqlite:///{_legacy_accounts_db(tmp_path)}", future=True)
    try:
        assert {"is_liquid", "is_taxable"} <= _accounts_columns(engine)
        with pytest.raises(IntegrityError, match="is_liquid"), engine.begin() as conn:
            conn.exec_driver_sql(INSERT_ACCOUNT)
    finally:
        engine.dispose()


def test_engine_for_drops_removed_columns_and_restores_inserts(tmp_path):
    path = _legacy_accounts_db(tmp_path)
    engine = db_module.engine_for(path)
    try:
        assert not {"is_liquid", "is_taxable"} & _accounts_columns(engine)
        with engine.begin() as conn:
            conn.exec_driver_sql(INSERT_ACCOUNT)
            names = [row[0] for row in conn.exec_driver_sql("SELECT name FROM accounts")]
        assert names == ["Giving Fund"]
    finally:
        db_module.reset_engine(path)


def test_drop_is_a_no_op_on_a_fresh_database(tmp_path):
    path = tmp_path / "fresh.db"
    try:
        engine = db_module.engine_for(path)
        assert not {"is_liquid", "is_taxable"} & _accounts_columns(engine)
        # Reopening must not trip over already-absent columns.
        db_module.reset_engine(path)
        engine = db_module.engine_for(path)
        with engine.begin() as conn:
            conn.exec_driver_sql(INSERT_ACCOUNT)
    finally:
        db_module.reset_engine(path)
