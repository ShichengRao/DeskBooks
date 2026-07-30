from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException

from .. import backups, schemas
from ..db import engine_for, get_request_profile, reset_engine
from ..profiles import ProfileInfo

router = APIRouter(prefix="/api/backups", tags=["backups"])

# Backups act on the profile the requesting tab is pinned to, so two
# windows on two profiles each manage their own backups.
RequestProfile = Annotated[ProfileInfo, Depends(get_request_profile)]


@router.get("", response_model=schemas.BackupList)
def list_profile_backups(profile: RequestProfile):
    return {"profile_slug": profile.slug, "backups": backups.list_backups(profile)}


@router.post("", response_model=schemas.BackupOut)
def create_profile_backup(profile: RequestProfile):
    engine_for(profile.db_path)
    try:
        return backups.create_backup(profile)
    except OSError as exc:
        raise HTTPException(500, str(exc)) from exc


@router.post("/{name}/restore", response_model=schemas.BackupOut)
def restore_profile_backup(name: str, profile: RequestProfile):
    reset_engine(profile.db_path)
    try:
        restored = backups.restore_backup(profile, name)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except FileNotFoundError as exc:
        raise HTTPException(404, "backup not found") from exc
    except (OSError, RuntimeError) as exc:
        raise HTTPException(500, str(exc)) from exc
    finally:
        reset_engine(profile.db_path)
    engine_for(profile.db_path)
    return restored


@router.delete("/{name}", response_model=schemas.BackupOut)
def delete_profile_backup(name: str, profile: RequestProfile):
    try:
        return backups.delete_backup(profile, name)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except FileNotFoundError as exc:
        raise HTTPException(404, "backup not found") from exc
    except OSError as exc:
        raise HTTPException(500, str(exc)) from exc
