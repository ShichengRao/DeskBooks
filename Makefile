.PHONY: dev backend frontend open bootstrap install test typecheck build clean reset-db

dev:
	./run.sh

install:
	cd backend && uv venv --python 3.11 .venv && uv pip install -e .
	cd frontend && npm install

bootstrap:
	cd backend && uv run python -m app.bootstrap

test:
	cd backend && uv run pytest

backend:
	cd backend && uv run uvicorn app.main:app --host 127.0.0.1 --port 8765 --log-level warning --reload --reload-dir app

frontend:
	cd frontend && npm run dev

open:
	open http://localhost:5173

typecheck:
	cd frontend && npm run typecheck

build:
	cd frontend && npm run build

# Drops the repo-local development SQLite file and rebuilds starter data.
# Prompts because this is unrecoverable.
reset-db:
	@read -p "Delete repo-local backend/data/app.db and re-seed? [y/N] " ans; \
	case "$$ans" in y|Y|yes) ;; *) echo "aborted"; exit 1;; esac
	rm -f backend/data/app.db backend/data/app.db-*
	cd backend && PFA_DATA_DIR="$(CURDIR)/backend/data" uv run python -m app.bootstrap

clean:
	rm -rf backend/.venv frontend/node_modules frontend/dist
