from __future__ import annotations

import json

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
