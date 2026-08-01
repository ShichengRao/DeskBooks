from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

# No `with` block: requests run without lifespan/init_db, and every asserted
# route below is either DB-free (/api/health) or rejected by the middleware
# before it can touch a database.
client = TestClient(app)


def test_demo_mode_blocks_mutations_and_filesystem_routes(monkeypatch):
    monkeypatch.setenv("PFA_DEMO_MODE", "1")

    assert client.get("/api/health").status_code == 200

    denied = client.post("/api/categories", json={"name": "X", "kind": "expense"})
    assert denied.status_code == 403
    assert "read-only" in denied.json()["detail"]

    assert client.delete("/api/profiles/demo").status_code == 403
    assert client.patch("/api/transactions/1", json={}).status_code == 403

    # filesystem-touching routes are blocked even for GET
    assert client.get("/api/backups").status_code == 403
    assert client.get("/api/imports/manifest").status_code == 403
    assert client.post("/api/admin/shutdown").status_code == 403


def test_demo_mode_off_is_inert(monkeypatch):
    monkeypatch.delenv("PFA_DEMO_MODE", raising=False)
    # Mutating routes reach their handlers again (this one 403s on its own
    # env guard, proving the demo middleware passed it through).
    response = client.post("/api/admin/shutdown")
    assert response.status_code == 403
    assert "run.sh" in response.json()["detail"]
    assert client.get("/api/health").status_code == 200
