from __future__ import annotations

from contextlib import asynccontextmanager
import os
import signal
import threading

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

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
    snapshots,
    transactions,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="DeskBooks", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    # Allow any localhost dev-server port. Production builds don't go through
    # CORS because the frontend and API are same-origin behind the launcher.
    allow_origin_regex=r"^http://(localhost|127\.0\.0\.1):([1-9][0-9]{0,4})$",
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
