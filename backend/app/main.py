from __future__ import annotations

import os
import signal
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from . import models  # noqa: F401
from .db import init_db
from .profiles import get_active_profile
from .routers import (
    accounts,
    analytics,
    backups,
    budgets,
    categories,
    goals,
    imports,
    journal,
    profiles,
    rules,
    snapshots,
    transactions,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="DeskBooks", version="0.1.0", lifespan=lifespan)

# The active profile is server-side state shared by every browser tab, so a
# tab that switched profiles silently redirects every other tab's reads and
# writes. Tabs therefore declare which profile they believe they're on; a
# mismatch is refused instead of touching the wrong database.
PROFILE_HEADER = "X-DeskBooks-Profile"


def profile_guard_conflict(path: str, claimed: str | None, active: str) -> dict | None:
    """The 409 payload for a stale tab, or None when the request may pass.

    Requests without the header pass (curl, older clients); the profile
    endpoints themselves pass so a stale tab can still list profiles and
    switch back."""
    if not claimed or not path.startswith("/api/") or path.startswith("/api/profiles"):
        return None
    if claimed == active:
        return None
    return {
        "code": "profile_mismatch",
        "detail": (
            f"this tab is on profile '{claimed}' but the app's active profile "
            f"is now '{active}' (switched in another tab?)"
        ),
        "expected_profile": claimed,
        "active_profile": active,
    }


@app.middleware("http")
async def enforce_profile_header(request, call_next):
    claimed = request.headers.get(PROFILE_HEADER)
    if claimed:
        conflict = profile_guard_conflict(request.url.path, claimed, get_active_profile().slug)
        if conflict is not None:
            return JSONResponse(status_code=409, content=conflict)
    return await call_next(request)


def _cors_origins() -> list[str]:
    configured = os.environ.get("PFA_CORS_ORIGINS")
    if configured:
        return [origin.strip() for origin in configured.split(",") if origin.strip()]
    frontend_port = os.environ.get("FRONTEND_PORT", "5173")
    return [
        f"http://localhost:{frontend_port}",
        f"http://127.0.0.1:{frontend_port}",
    ]


app.add_middleware(
    CORSMiddleware,
    # Keep CORS scoped to the launched frontend port. Production builds don't
    # go through CORS because the frontend and API are same-origin behind the
    # launcher/proxy.
    allow_origins=_cors_origins(),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health():
    return {"ok": True}


@app.post("/api/admin/shutdown")
def shutdown():
    if os.environ.get("PFA_ALLOW_SHUTDOWN") != "1":
        raise HTTPException(403, "shutdown is only enabled from ./run.sh")

    def _stop() -> None:
        # Let the HTTP response leave first, then stop uvicorn. With
        # --reload, the parent is the reloader; without it, killing the parent
        # shell lets run.sh's cleanup stop the frontend too.
        os.kill(os.getppid(), signal.SIGTERM)
        os.kill(os.getpid(), signal.SIGTERM)

    threading.Timer(0.2, _stop).start()
    return {"status": "stopping"}


app.include_router(accounts.router)
app.include_router(profiles.router)
app.include_router(backups.router)
app.include_router(budgets.router)
app.include_router(categories.router)
app.include_router(transactions.router)
app.include_router(rules.router)
app.include_router(snapshots.router)
app.include_router(goals.router)
app.include_router(journal.router)
app.include_router(imports.router)
app.include_router(analytics.router)
