from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app import db as app_db
from app import profiles
from app.models import Account, AccountCategory, AccountType, SignConvention


def _profile(tmp_path, slug: str) -> profiles.ProfileInfo:
    return profiles.ProfileInfo(
        slug=slug,
        name=slug.title(),
        db_file=f"{slug}.db",
        db_path=tmp_path / f"{slug}.db",
        is_active=slug == "personal",
    )


def _request(header: str | None) -> SimpleNamespace:
    headers = {} if header is None else {app_db.PROFILE_HEADER: header}
    return SimpleNamespace(headers=headers)


@pytest.fixture()
def two_profiles(tmp_path, monkeypatch):
    personal = _profile(tmp_path, "personal")
    scratch = _profile(tmp_path, "scratch")
    monkeypatch.setattr(profiles, "get_active_profile", lambda: personal)
    monkeypatch.setattr(profiles, "list_profiles", lambda: [personal, scratch])
    yield personal, scratch
    app_db.reset_engine(personal.db_path)
    app_db.reset_engine(scratch.db_path)


def test_request_profile_resolution(two_profiles):
    personal, scratch = two_profiles

    # no header (curl, CLI, older clients) → the active profile
    assert app_db.get_request_profile(_request(None)).slug == "personal"
    # a tab pinned to another profile routes there — no refusal
    assert app_db.get_request_profile(_request("scratch")).slug == "scratch"

    with pytest.raises(HTTPException) as gone:
        app_db.get_request_profile(_request("deleted-elsewhere"))
    assert gone.value.status_code == 404
    assert gone.value.detail["code"] == "profile_unknown"


def test_get_db_routes_to_the_claimed_profiles_database(two_profiles):
    personal, scratch = two_profiles

    gen = app_db.get_db(_request("personal"))
    session = next(gen)
    session.add(
        Account(
            name="Only In Personal",
            account_category=AccountCategory.bank,
            type=AccountType.checking,
            sign_convention=SignConvention.outflow_negative,
        )
    )
    session.commit()
    gen.close()

    gen = app_db.get_db(_request("scratch"))
    other = next(gen)
    # two windows, two profiles, two databases: scratch never sees
    # personal's rows
    assert other.query(Account).count() == 0
    gen.close()

    gen = app_db.get_db(_request(None))
    default = next(gen)
    assert [a.name for a in default.query(Account).all()] == ["Only In Personal"]
    gen.close()
