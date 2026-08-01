"""Vercel serverless entrypoint for the hosted read-only demo.

Serves the real FastAPI app against a synthetic "Demo" profile that is seeded
into /tmp on cold start, so every new instance begins from the same clean,
fully invented dataset and nothing persists. PFA_DEMO_MODE turns the API
read-only (see the middleware in app.main).
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_REPO_ROOT / "backend"))

# Environment must be set before app modules import (app_paths reads it once).
os.environ.setdefault("PFA_DATA_DIR", "/tmp/deskbooks-demo")
os.environ.setdefault("PFA_DEMO_MODE", "1")

from scripts.seed_demo_profile import bootstrap_demo_data_dir  # noqa: E402

bootstrap_demo_data_dir(Path(os.environ["PFA_DATA_DIR"]))

from app.main import app  # noqa: E402, F401
