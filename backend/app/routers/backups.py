from __future__ import annotations

from fastapi import APIRouter, HTTPException

from .. import backups, schemas
from ..db import init_db, reset_engine
from ..profiles import get_active_profile

router = APIRouter(prefix="/api/backups", tags=["backups"])


@router.get("", response_model=schemas.BackupList)
def list_profile_backups():
    profile = get_active_profile()
    return {"profile_slug": profile.slug, "backups": backups.list_backups(profile)}


@router.post("", response_model=schemas.BackupOut)
def create_profile_backup():
    init_db()
    profile = get_active_profile()
    try:
        return backups.create_backup(profile)
    except OSError as exc:
        raise HTTPException(500, str(exc)) from exc


@router.post("/{name}/restore", response_model=schemas.BackupOut)
def restore_profile_backup(name: str):
    profile = get_active_profile()
    reset_engine()
    try:
        restored = backups.restore_backup(profile, name)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except FileNotFoundError as exc:
        raise HTTPException(404, "backup not found") from exc
    except (OSError, RuntimeError) as exc:
        raise HTTPException(500, str(exc)) from exc
    finally:
        reset_engine()
    init_db()
    return restored
