from __future__ import annotations

import os
import signal
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from . import models  # noqa: F401
from .db import init_db
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
    settings,
    snapshots,
    transactions,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="DeskBooks", version="0.1.0", lifespan=lifespan)


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


# Routes that read or touch the host filesystem beyond the profile database;
# they have no place on a public read-only deployment.
_DEMO_BLOCKED_PREFIXES = ("/api/admin", "/api/backups", "/api/imports")


@app.middleware("http")
async def demo_read_only_guard(request: Request, call_next):
    # PFA_DEMO_MODE=1 turns the API read-only for public demo hosting: any
    # mutating method and any filesystem-touching route gets a 403. Checked
    # per-request so tests can toggle it without rebuilding the app.
    if os.environ.get("PFA_DEMO_MODE") == "1":
        if request.method not in ("GET", "HEAD", "OPTIONS"):
            return JSONResponse({"detail": "This deployment is a read-only demo."}, status_code=403)
        if request.url.path.startswith(_DEMO_BLOCKED_PREFIXES):
            return JSONResponse({"detail": "Not available in the read-only demo."}, status_code=403)
    return await call_next(request)


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
app.include_router(settings.router)
app.include_router(snapshots.router)
app.include_router(goals.router)
app.include_router(journal.router)
app.include_router(imports.router)
app.include_router(analytics.router)
