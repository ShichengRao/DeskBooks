from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import sqlite3

from .app_paths import DATA_DIR

DEFAULT_DB_FILE = os.environ.get("PFA_DB_FILE", "app.db")
REGISTRY_PATH = DATA_DIR / "profiles.json"
PROFILES_DIR = DATA_DIR / "profiles"


@dataclass(frozen=True)
class ProfileInfo:
    slug: str
    name: str
    db_file: str
    db_path: Path
    is_active: bool


def slugify_profile_name(name: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", name.strip().lower()).strip("-")
    return slug or "profile"


def _default_db_file(slug: str) -> str:
    if slug == "personal" and "PFA_PROFILE" not in os.environ:
        return DEFAULT_DB_FILE
    return str(Path("profiles") / f"{slug}.db")


def _unique_slug(registry: dict, name: str) -> str:
    base_slug = slugify_profile_name(name)
    existing = {str(row["slug"]) for row in registry["profiles"]}
    slug = base_slug
    suffix = 2
    while slug in existing:
        slug = f"{base_slug}-{suffix}"
        suffix += 1
    return slug


def ensure_profile_registry() -> None:
    if REGISTRY_PATH.exists():
        return
    _write_registry(
        {
            "active": "personal",
            "profiles": [
                {"slug": "personal", "name": "Personal", "db_file": DEFAULT_DB_FILE}
            ],
        }
    )


def _read_registry() -> dict:
    ensure_profile_registry()
    if not REGISTRY_PATH.exists():
        raise FileNotFoundError(f"profile registry not found: {REGISTRY_PATH}")
    try:
        with REGISTRY_PATH.open("r", encoding="utf-8") as f:
            registry = json.load(f)
    except (json.JSONDecodeError, OSError):
        raise RuntimeError(f"profile registry is invalid: {REGISTRY_PATH}")
    if not registry.get("profiles"):
        raise RuntimeError(f"profile registry has no profiles: {REGISTRY_PATH}")
    return registry


def _write_registry(registry: dict) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with REGISTRY_PATH.open("w", encoding="utf-8") as f:
        json.dump(registry, f, indent=2, sort_keys=True)
        f.write("\n")


def _resolve_db_path(db_file: str) -> Path:
    raw = Path(db_file)
    path = raw if raw.is_absolute() else DATA_DIR / raw
    return path.resolve()


def _profile_from_row(row: dict, active_slug: str) -> ProfileInfo:
    slug = str(row["slug"])
    db_file = str(row["db_file"])
    return ProfileInfo(
        slug=slug,
        name=str(row["name"]),
        db_file=db_file,
        db_path=_resolve_db_path(db_file),
        is_active=slug == active_slug,
    )


def list_profiles() -> list[ProfileInfo]:
    registry = _read_registry()
    active = str(registry.get("active") or "")
    return [_profile_from_row(row, active) for row in registry["profiles"]]


def get_active_profile() -> ProfileInfo:
    registry = _read_registry()
    active = str(registry.get("active") or "")
    for row in registry["profiles"]:
        if row.get("slug") == active:
            return _profile_from_row(row, active)
    fallback = registry["profiles"][0]
    registry["active"] = fallback["slug"]
    _write_registry(registry)
    return _profile_from_row(fallback, fallback["slug"])


def create_profile(name: str) -> ProfileInfo:
    registry = _read_registry()
    slug = _unique_slug(registry, name)
    row = {
        "slug": slug,
        "name": name.strip() or slug,
        "db_file": _default_db_file(slug),
    }
    registry["profiles"].append(row)
    _write_registry(registry)
    return _profile_from_row(row, str(registry.get("active") or ""))


def duplicate_profile(name: str, source_slug: str | None = None) -> ProfileInfo:
    registry = _read_registry()
    active_slug = str(registry.get("active") or "")
    source = None
    for source_row in registry["profiles"]:
        if source_row.get("slug") == (source_slug or active_slug):
            source = _profile_from_row(source_row, active_slug)
            break
    if source is None:
        raise KeyError(source_slug or active_slug)
    slug = _unique_slug(registry, name)
    row = {
        "slug": slug,
        "name": name.strip() or slug,
        "db_file": _default_db_file(slug),
    }
    target_path = _resolve_db_path(row["db_file"])
    target_path.parent.mkdir(parents=True, exist_ok=True)
    if source.db_path.exists():
        with sqlite3.connect(str(source.db_path)) as src, sqlite3.connect(str(target_path)) as dst:
            src.backup(dst)
    else:
        target_path.touch()
    registry["profiles"].append(row)
    registry["active"] = slug
    _write_registry(registry)
    return _profile_from_row(row, slug)


def duplicate_active_profile(name: str) -> ProfileInfo:
    return duplicate_profile(name)


def set_active_profile(slug: str) -> ProfileInfo:
    registry = _read_registry()
    for row in registry["profiles"]:
        if row.get("slug") == slug:
            registry["active"] = slug
            _write_registry(registry)
            return _profile_from_row(row, slug)
    raise KeyError(slug)
