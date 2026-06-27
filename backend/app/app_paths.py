from __future__ import annotations

import os
from pathlib import Path
import sys

APP_NAME = "DeskBooks"
APP_DIR_NAME = "deskbooks"
ROOT = Path(__file__).resolve().parent.parent.parent


def default_data_dir() -> Path:
    override = os.environ.get("PFA_DATA_DIR")
    if override:
        return Path(override).expanduser()

    home = Path.home()
    if sys.platform == "darwin":
        return home / "Library" / "Application Support" / APP_NAME
    if os.name == "nt":
        base = os.environ.get("APPDATA")
        return Path(base) / APP_NAME if base else home / "AppData" / "Roaming" / APP_NAME

    xdg_data_home = os.environ.get("XDG_DATA_HOME")
    base = Path(xdg_data_home).expanduser() if xdg_data_home else home / ".local" / "share"
    return base / APP_DIR_NAME


DATA_DIR = default_data_dir()
