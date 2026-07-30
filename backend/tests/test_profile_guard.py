from __future__ import annotations

from app.main import profile_guard_conflict


def test_profile_guard_blocks_only_stale_tabs():
    # matching claim, or no claim at all (curl, older clients) → pass
    assert profile_guard_conflict("/api/transactions", "personal", "personal") is None
    assert profile_guard_conflict("/api/transactions", None, "personal") is None
    assert profile_guard_conflict("/api/transactions", "", "personal") is None

    conflict = profile_guard_conflict("/api/snapshots", "personal", "scratch")
    assert conflict is not None
    assert conflict["code"] == "profile_mismatch"
    assert conflict["expected_profile"] == "personal"
    assert conflict["active_profile"] == "scratch"
    assert "'personal'" in conflict["detail"] and "'scratch'" in conflict["detail"]


def test_profile_guard_exempts_profile_routes_and_non_api_paths():
    # a stale tab must still be able to list profiles and switch back
    assert profile_guard_conflict("/api/profiles", "personal", "scratch") is None
    assert profile_guard_conflict("/api/profiles/active", "personal", "scratch") is None
    # static assets and the SPA shell are not profile-scoped
    assert profile_guard_conflict("/", "personal", "scratch") is None
    assert profile_guard_conflict("/assets/app.js", "personal", "scratch") is None
