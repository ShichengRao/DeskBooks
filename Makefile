PORT ?= $(or $(FRONTEND_PORT),5173)
API_PORT ?= $(or $(BACKEND_PORT),$(if $(filter 5173,$(PORT)),8765,8766))

.PHONY: dev backend frontend open bootstrap install test typecheck build clean reset-db

dev:
	./run.sh --port "$(PORT)" --api-port "$(API_PORT)" $(if $(DATA_DIR),--data-dir "$(DATA_DIR)")

install:
	cd backend && uv venv --python 3.11 .venv && uv pip install -e .
	cd frontend && npm install

bootstrap:
	cd backend && uv run python -m app.bootstrap

test:
	cd backend && uv run pytest

backend:
	cd backend && uv run uvicorn app.main:app --host 127.0.0.1 --port "$(API_PORT)" --log-level warning --reload --reload-dir app

frontend:
	cd frontend && PFA_API_TARGET="http://127.0.0.1:$(API_PORT)" npm run dev -- --host 127.0.0.1 --port "$(PORT)" --strictPort

open:
	open "http://localhost:$(PORT)"

typecheck:
	cd frontend && npm run typecheck

build:
	cd frontend && npm run build

# Drops the repo-local development SQLite state and rebuilds starter data.
# Prompts because this is unrecoverable.
reset-db:
	@read -p "Delete repo-local backend/data app state and rebuild starter data? [y/N] " ans; \
	case "$$ans" in y|Y|yes) ;; *) echo "aborted"; exit 1;; esac
	rm -f backend/data/app.db backend/data/app.db-* backend/data/profiles.json
	cd backend && PFA_DATA_DIR="$(CURDIR)/backend/data" uv run python -m app.bootstrap

clean:
	rm -rf backend/.venv frontend/node_modules frontend/dist
