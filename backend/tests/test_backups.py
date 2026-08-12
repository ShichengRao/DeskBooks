from __future__ import annotations

import sqlite3

import pytest

from app import backups
from app.profiles import ProfileInfo


def _write_marker(db_path, value: str) -> None:
    with sqlite3.connect(str(db_path)) as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS marker (value TEXT NOT NULL)")
        conn.execute("DELETE FROM marker")
        conn.execute("INSERT INTO marker (value) VALUES (?)", (value,))


def _profile(tmp_path, monkeypatch) -> ProfileInfo:
    monkeypatch.setattr(backups, "DATA_DIR", tmp_path)
    db_path = tmp_path / "app.db"
    _write_marker(db_path, "clean")
    return ProfileInfo(
        slug="personal",
        name="Personal",
        db_file="app.db",
        db_path=db_path,
        is_active=True,
    )


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


def test_delete_backup_removes_profile_backup(tmp_path, monkeypatch):
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
    backup_path = tmp_path / "backups" / profile.slug / created["name"]

    deleted = backups.delete_backup(profile, created["name"])

    assert deleted["name"] == created["name"]
    assert not backup_path.exists()
    assert backups.list_backups(profile) == []


@pytest.mark.parametrize(
    "name,expected",
    [
        ("personal-20260812-143307-pre-restore.db", "pre-restore"),
        ("personal-20260812-143307-pre-staged-import.db", "pre-staged-import"),
        ("personal-20260812-143307.db", None),
        # The collision fallback appends microseconds, not a reason.
        ("personal-20260812-143307-482913.db", None),
        # A slug containing hyphens must not be mistaken for a label.
        ("demo-family-20260812-143307.db", None),
        ("demo-family-20260812-143307-pre-restore.db", "pre-restore"),
    ],
)
def test_parse_label_reads_the_reason_from_the_filename(name, expected):
    profile_slug = "demo-family" if name.startswith("demo-family") else "personal"
    assert backups._parse_label(name, profile_slug) == expected


def test_listed_backups_carry_their_label(tmp_path, monkeypatch):
    profile = _profile(tmp_path, monkeypatch)
    backups.create_backup(profile)
    backups.create_backup(profile, label="pre-restore")

    listed = {b["name"]: b["label"] for b in backups.list_backups(profile)}

    assert sorted(listed.values(), key=lambda v: (v is None, v)) == ["pre-restore", None]


@pytest.mark.parametrize(
    "typed,expected",
    [
        ("Before tax cleanup", "before-tax-cleanup"),
        ("  Quarterly   review!  ", "quarterly-review"),
        ("Pre-2027 planning", "pre-2027-planning"),
        ("", None),
        ("   ", None),
        ("!!!", None),
        (None, None),
        # An all-digits label would be read back as the collision suffix.
        ("2027", "note-2027"),
    ],
)
def test_slugify_label_makes_typed_text_filename_safe(typed, expected):
    assert backups.slugify_label(typed) == expected


def test_slugify_label_truncates_without_a_trailing_hyphen():
    slug = backups.slugify_label("a" * 40 + " " + "b" * 40)

    assert len(slug) <= backups.MAX_LABEL_LENGTH
    assert not slug.endswith("-")
    assert backups.BACKUP_NAME_RE.match(f"personal-20260812-143307-{slug}.db")


def test_typed_name_round_trips_through_the_filename(tmp_path, monkeypatch):
    profile = _profile(tmp_path, monkeypatch)

    created = backups.create_backup(profile, label=backups.slugify_label("Before tax cleanup"))

    assert created["label"] == "before-tax-cleanup"
    assert created["name"].endswith("-before-tax-cleanup.db")
    assert backups.list_backups(profile)[0]["label"] == "before-tax-cleanup"
