from __future__ import annotations

import re
import shutil
import sqlite3
from datetime import datetime
from pathlib import Path

from .app_paths import DATA_DIR
from .profiles import ProfileInfo

BACKUP_NAME_RE = re.compile(r"^[a-z0-9-]+-\d{8}-\d{6}(?:-[a-z0-9-]+)?\.db$")


def backup_dir(profile_slug: str) -> Path:
    return DATA_DIR / "backups" / profile_slug


def _metadata(path: Path, profile_slug: str) -> dict:
    stat = path.stat()
    return {
        "name": path.name,
        "profile_slug": profile_slug,
        "size_bytes": stat.st_size,
        "created_at": datetime.fromtimestamp(stat.st_mtime),
        "path": str(path),
    }


def list_backups(profile: ProfileInfo) -> list[dict]:
    root = backup_dir(profile.slug)
    if not root.exists():
        return []
    backups = [
        _metadata(path, profile.slug)
        for path in root.glob("*.db")
        if path.is_file() and BACKUP_NAME_RE.match(path.name)
    ]
    return sorted(backups, key=lambda b: b["created_at"], reverse=True)


def _backup_name(profile_slug: str, label: str | None = None) -> str:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    suffix = f"-{label}" if label else ""
    return f"{profile_slug}-{stamp}{suffix}.db"


def _copy_sqlite(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(str(source)) as src:
        with sqlite3.connect(str(destination)) as dst:
            src.backup(dst)


def _assert_sqlite_ok(path: Path) -> None:
    with sqlite3.connect(str(path)) as conn:
        result = conn.execute("PRAGMA integrity_check").fetchone()
    if result is None or result[0] != "ok":
        raise RuntimeError(f"backup failed SQLite integrity check: {path}")


def create_backup(profile: ProfileInfo, label: str | None = None) -> dict:
    if not profile.db_path.exists():
        raise FileNotFoundError(f"active database does not exist: {profile.db_path}")
    root = backup_dir(profile.slug)
    destination = root / _backup_name(profile.slug, label)
    if destination.exists():
        destination = root / f"{profile.slug}-{datetime.now().strftime('%Y%m%d-%H%M%S-%f')}.db"
    _copy_sqlite(profile.db_path, destination)
    _assert_sqlite_ok(destination)
    return _metadata(destination, profile.slug)


def resolve_backup(profile: ProfileInfo, name: str) -> Path:
    if not BACKUP_NAME_RE.match(name):
        raise ValueError("invalid backup name")
    path = (backup_dir(profile.slug) / name).resolve()
    root = backup_dir(profile.slug).resolve()
    if root not in path.parents or not path.exists() or not path.is_file():
        raise FileNotFoundError(name)
    return path


def restore_backup(profile: ProfileInfo, name: str) -> dict:
    source = resolve_backup(profile, name)
    _assert_sqlite_ok(source)
    target = profile.db_path
    target.parent.mkdir(parents=True, exist_ok=True)

    if target.exists():
        create_backup(profile, label="pre-restore")

    temp_target = target.with_name(f".{target.name}.restore-tmp")
    if temp_target.exists():
        temp_target.unlink()
    shutil.copy2(source, temp_target)
    _assert_sqlite_ok(temp_target)

    for sidecar in _sidecars(target):
        if sidecar.exists():
            sidecar.unlink()
    temp_target.replace(target)
    for sidecar in _sidecars(target):
        if sidecar.exists():
            sidecar.unlink()
    return _metadata(source, profile.slug)


def delete_backup(profile: ProfileInfo, name: str) -> dict:
    path = resolve_backup(profile, name)
    deleted = _metadata(path, profile.slug)
    path.unlink()
    return deleted


def _sidecars(path: Path) -> list[Path]:
    return [
        path.with_name(f"{path.name}-wal"),
        path.with_name(f"{path.name}-shm"),
        path.with_name(f"{path.name}-journal"),
    ]
