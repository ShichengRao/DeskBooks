from __future__ import annotations

import ast
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_backend_app_does_not_import_network_clients():
    forbidden_modules = {
        "aiohttp",
        "boto3",
        "google",
        "httpx",
        "openai",
        "requests",
        "smtplib",
        "socket",
        "urllib",
    }
    offenders: list[str] = []
    for path in (ROOT / "backend" / "app").rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names = [alias.name.split(".", 1)[0] for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module.split(".", 1)[0]]
            else:
                continue
            for name in names:
                if name in forbidden_modules:
                    offenders.append(f"{path.relative_to(ROOT)} imports {name}")

    assert offenders == []


def test_frontend_fetches_only_relative_api_paths():
    offenders: list[str] = []
    fetch_call = re.compile(r"fetch\(([^,\n)]+)")
    for path in (ROOT / "frontend" / "src").rglob("*"):
        if path.suffix not in {".ts", ".tsx"}:
            continue
        text = path.read_text(encoding="utf-8")
        for match in fetch_call.finditer(text):
            expr = match.group(1).strip()
            if expr in {"BASE + path"}:
                continue
            if expr.startswith(('" /api', "'/api", "`/api", '"/api')):
                continue
            offenders.append(f"{path.relative_to(ROOT)} uses fetch({expr})")

    assert offenders == []


def test_cors_origins_are_exact_frontend_origins(monkeypatch):
    from app.main import _cors_origins

    monkeypatch.delenv("PFA_CORS_ORIGINS", raising=False)
    monkeypatch.setenv("FRONTEND_PORT", "5172")

    assert _cors_origins() == [
        "http://localhost:5172",
        "http://127.0.0.1:5172",
    ]

    monkeypatch.setenv(
        "PFA_CORS_ORIGINS",
        "http://localhost:3000, http://127.0.0.1:3000,",
    )

    assert _cors_origins() == [
        "http://localhost:3000",
        "http://127.0.0.1:3000",
    ]
