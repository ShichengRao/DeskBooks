#!/usr/bin/env python3
from __future__ import annotations

import argparse
import difflib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "docs" / "contracts" / "python-openapi.json"


def rendered_contract() -> str:
    sys.path.insert(0, str(ROOT / "backend"))
    from app.main import app

    return json.dumps(app.openapi(), indent=2, sort_keys=True, ensure_ascii=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Export the legacy Python API contract.")
    parser.add_argument("--check", action="store_true", help="fail if the committed contract is stale")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    output = args.output.resolve()
    rendered = rendered_contract()
    if args.check:
        if not output.is_file():
            print(f"missing Python API contract: {output}", file=sys.stderr)
            return 1
        current = output.read_text(encoding="utf-8")
        if current == rendered:
            print(f"Python API contract is current: {output}")
            return 0
        diff = difflib.unified_diff(
            current.splitlines(),
            rendered.splitlines(),
            fromfile=str(output),
            tofile="generated OpenAPI",
            lineterm="",
        )
        print("\n".join(list(diff)[:200]), file=sys.stderr)
        print("Python API contract is stale; run make api-contract-python", file=sys.stderr)
        return 1

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(rendered, encoding="utf-8")
    print(f"wrote Python API contract: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
