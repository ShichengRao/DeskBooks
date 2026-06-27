from __future__ import annotations

from fastapi import APIRouter, HTTPException

from .. import profiles, schemas
from ..db import SessionLocal, init_db, reset_engine
from ..onboarding import seed_starter_data

router = APIRouter(prefix="/api/profiles", tags=["profiles"])


def _profile_out(p: profiles.ProfileInfo) -> schemas.ProfileOut:
    return schemas.ProfileOut(
        slug=p.slug,
        name=p.name,
        db_file=p.db_file,
        is_active=p.is_active,
    )


def _profile_list() -> schemas.ProfileList:
    rows = profiles.list_profiles()
    active = next((p.slug for p in rows if p.is_active), rows[0].slug if rows else "")
    return schemas.ProfileList(
        profiles=[_profile_out(p) for p in rows],
        active_slug=active,
    )


@router.get("", response_model=schemas.ProfileList)
def list_profiles():
    return _profile_list()


@router.post("", response_model=schemas.ProfileList)
def create_profile(body: schemas.ProfileCreate):
    created = profiles.create_profile(body.name)
    profiles.set_active_profile(created.slug)
    reset_engine()
    init_db()
    db = SessionLocal()
    try:
        seed_starter_data(db)
    finally:
        db.close()
    return _profile_list()


@router.post("/active", response_model=schemas.ProfileList)
def activate_profile(body: schemas.ProfileActivate):
    try:
        profiles.set_active_profile(body.slug)
    except KeyError:
        raise HTTPException(404, "profile not found")
    reset_engine()
    init_db()
    return _profile_list()
