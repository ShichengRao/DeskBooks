from __future__ import annotations

import sqlite3

from app import backups
from app.profiles import ProfileInfo


def _write_marker(db_path, value: str) -> None:
    with sqlite3.connect(str(db_path)) as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS marker (value TEXT NOT NULL)")
        conn.execute("DELETE FROM marker")
        conn.execute("INSERT INTO marker (value) VALUES (?)", (value,))


def _read_marker(db_path) -> str:
    with sqlite3.connect(str(db_path)) as conn:
        return conn.execute("SELECT value FROM marker").fetchone()[0]


def test_backup_restore_replaces_active_profile_database_and_keeps_safety_copy(tmp_path, monkeypatch):
    monkeypatch.setattr(backups, "DATA_DIR", tmp_path)
    db_path = tmp_path / "app.db"
    profile = ProfileInfo(
        slug="personal",
        name="Personal",
        db_file="app.db",
        db_path=db_path,
        is_active=True,
    )

    _write_marker(db_path, "clean")
    created = backups.create_backup(profile)
    _write_marker(db_path, "broken")

    restored = backups.restore_backup(profile, created["name"])

    assert restored["name"] == created["name"]
    assert _read_marker(db_path) == "clean"
    names = [row["name"] for row in backups.list_backups(profile)]
    assert created["name"] in names
    assert any(name.endswith("-pre-restore.db") for name in names)
