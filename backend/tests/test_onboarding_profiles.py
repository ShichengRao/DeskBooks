from __future__ import annotations

import json
import sqlite3

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app import profiles
from app.models import Account, Base, Category
from app.onboarding import seed_starter_data


def test_starter_onboarding_uses_generic_accounts_and_categories():
    engine = create_engine("sqlite:///:memory:", future=True)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, future=True)
    db = Session()
    try:
        result = seed_starter_data(db)
        account_names = {a.name for a in db.scalars(select(Account)).all()}
        category_names = {c.name for c in db.scalars(select(Category)).all()}

        assert result["accounts_added"] == 3
        assert {"Checking", "Savings", "Credit Card"}.issubset(account_names)
        assert {"Housing", "Food", "Income", "Credit Card Payment"}.issubset(
            category_names
        )
        assert account_names == {"Checking", "Savings", "Credit Card"}
        assert category_names.issuperset(
            {
                "Housing",
                "Rent",
                "Utilities",
                "Food",
                "Groceries",
                "Restaurants",
                "Income",
                "Paycheck",
                "Other Income",
            }
        )
    finally:
        db.close()


def test_profiles_map_to_separate_sqlite_files(tmp_path, monkeypatch):
    monkeypatch.setattr(profiles, "DATA_DIR", tmp_path)
    monkeypatch.setattr(profiles, "REGISTRY_PATH", tmp_path / "profiles.json")
    monkeypatch.setattr(profiles, "PROFILES_DIR", tmp_path / "profiles")
    (tmp_path / "profiles.json").write_text(
        json.dumps(
            {
                "active": "personal",
                "profiles": [
                    {"slug": "personal", "name": "Personal", "db_file": "app.db"}
                ],
            }
        ),
        encoding="utf-8",
    )

    personal = profiles.get_active_profile()
    household = profiles.create_profile("Household")
    profiles.set_active_profile(household.slug)
    active = profiles.get_active_profile()

    assert personal.slug == "personal"
    assert household.slug == "household"
    assert active.slug == "household"
    assert personal.db_path != household.db_path
    assert household.db_path.name == "household.db"


def test_profiles_create_default_registry_on_first_run(tmp_path, monkeypatch):
    monkeypatch.setattr(profiles, "DATA_DIR", tmp_path)
    monkeypatch.setattr(profiles, "REGISTRY_PATH", tmp_path / "profiles.json")
    monkeypatch.setattr(profiles, "PROFILES_DIR", tmp_path / "profiles")

    active = profiles.get_active_profile()

    assert active.slug == "personal"
    assert active.name == "Personal"
    assert active.db_path == tmp_path / "app.db"
    registry = json.loads((tmp_path / "profiles.json").read_text(encoding="utf-8"))
    assert registry == {
        "active": "personal",
        "profiles": [
            {"db_file": "app.db", "name": "Personal", "slug": "personal"}
        ],
    }


def test_profile_create_schema_can_request_blank_profile():
    from app.schemas import ProfileCreate

    default_body = ProfileCreate(name="Demo")
    blank_body = ProfileCreate(name="Mirror", seed_starter_data=False)

    assert default_body.seed_starter_data is True
    assert blank_body.seed_starter_data is False


def test_duplicate_active_profile_copies_sqlite_database(tmp_path, monkeypatch):
    monkeypatch.setattr(profiles, "DATA_DIR", tmp_path)
    monkeypatch.setattr(profiles, "REGISTRY_PATH", tmp_path / "profiles.json")
    monkeypatch.setattr(profiles, "PROFILES_DIR", tmp_path / "profiles")
    (tmp_path / "profiles.json").write_text(
        json.dumps(
            {
                "active": "personal",
                "profiles": [
                    {"slug": "personal", "name": "Personal", "db_file": "app.db"}
                ],
            }
        ),
        encoding="utf-8",
    )
    with sqlite3.connect(tmp_path / "app.db") as conn:
        conn.execute("CREATE TABLE marker (value TEXT NOT NULL)")
        conn.execute("INSERT INTO marker (value) VALUES ('copied')")

    duplicate = profiles.duplicate_active_profile("Copied Profile")

    assert duplicate.slug == "copied-profile"
    assert duplicate.db_path == tmp_path / "profiles" / "copied-profile.db"
    assert profiles.get_active_profile().slug == "copied-profile"
    with sqlite3.connect(duplicate.db_path) as conn:
        assert conn.execute("SELECT value FROM marker").fetchone()[0] == "copied"


def test_duplicate_profile_can_copy_selected_source_profile(tmp_path, monkeypatch):
    monkeypatch.setattr(profiles, "DATA_DIR", tmp_path)
    monkeypatch.setattr(profiles, "REGISTRY_PATH", tmp_path / "profiles.json")
    monkeypatch.setattr(profiles, "PROFILES_DIR", tmp_path / "profiles")
    (tmp_path / "profiles.json").write_text(
        json.dumps(
            {
                "active": "personal",
                "profiles": [
                    {"slug": "personal", "name": "Personal", "db_file": "app.db"},
                    {"slug": "demo", "name": "Demo", "db_file": "profiles/demo.db"},
                ],
            }
        ),
        encoding="utf-8",
    )
    (tmp_path / "profiles").mkdir()
    with sqlite3.connect(tmp_path / "profiles" / "demo.db") as conn:
        conn.execute("CREATE TABLE marker (value TEXT NOT NULL)")
        conn.execute("INSERT INTO marker (value) VALUES ('demo-source')")

    duplicate = profiles.duplicate_profile("Copied Demo", "demo")

    assert duplicate.slug == "copied-demo"
    with sqlite3.connect(duplicate.db_path) as conn:
        assert conn.execute("SELECT value FROM marker").fetchone()[0] == "demo-source"
