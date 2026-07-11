PORT ?= $(or $(FRONTEND_PORT),5173)
API_PORT ?= $(or $(BACKEND_PORT),$(if $(filter 5173,$(PORT)),8765,8766))
JAVA_GRADLE ?= gradle
JAVA_GRADLE_ENV = $(if $(JAVA_GRADLE_USER_HOME),GRADLE_USER_HOME="$(JAVA_GRADLE_USER_HOME)")

.PHONY: dev dev-java dev-python backend backend-java backend-python frontend open bootstrap install install-automation fetch-preview fetch-apply automation-import-java automation-import-python schedule-fetch-preview schedule-fetch-apply unschedule-fetch test test-java java-metrics parity-java typecheck build clean reset-db

dev:
	./run.sh --port "$(PORT)" --api-port "$(API_PORT)" $(if $(DATA_DIR),--data-dir "$(DATA_DIR)")

dev-java:
	./run.sh --backend java --port "$(PORT)" --api-port "$(API_PORT)" $(if $(DATA_DIR),--data-dir "$(DATA_DIR)")

dev-python:
	./run.sh --backend python --port "$(PORT)" --api-port "$(API_PORT)" $(if $(DATA_DIR),--data-dir "$(DATA_DIR)")

install:
	cd backend && uv venv --python 3.11 .venv && uv pip install -e .
	cd frontend && npm install

install-automation:
	cd automation && npm install && npx playwright install chromium

fetch-preview:
	automation/bin/run-scheduled-fetch.sh

fetch-apply:
	DESKBOOKS_IMPORT_APPLY=1 automation/bin/run-scheduled-fetch.sh

automation-import-java:
	cd backend-java && $(JAVA_GRADLE_ENV) PFA_SEED_STARTER_DATA="0" $(JAVA_GRADLE) automationImport

automation-import-python:
	cd backend && uv run python -m app.automation_import

schedule-fetch-preview:
	automation/bin/install-launchd.sh

schedule-fetch-apply:
	DESKBOOKS_IMPORT_APPLY=1 automation/bin/install-launchd.sh

unschedule-fetch:
	automation/bin/uninstall-launchd.sh

bootstrap:
	cd backend && uv run python -m app.bootstrap

test:
	cd backend && uv run pytest

backend:
	cd backend-java && $(JAVA_GRADLE_ENV) BACKEND_PORT="$(API_PORT)" PFA_SEED_STARTER_DATA="1" PFA_CORS_ORIGINS="http://localhost:$(PORT),http://127.0.0.1:$(PORT)" $(JAVA_GRADLE) bootRun

backend-java:
	cd backend-java && $(JAVA_GRADLE_ENV) BACKEND_PORT="$(API_PORT)" PFA_SEED_STARTER_DATA="1" PFA_CORS_ORIGINS="http://localhost:$(PORT),http://127.0.0.1:$(PORT)" $(JAVA_GRADLE) bootRun

backend-python:
	cd backend && PFA_CORS_ORIGINS="http://localhost:$(PORT),http://127.0.0.1:$(PORT)" uv run uvicorn app.main:app --host 127.0.0.1 --port "$(API_PORT)" --log-level warning --reload --reload-dir app

frontend:
	cd frontend && PFA_API_TARGET="http://127.0.0.1:$(API_PORT)" npm run dev -- --host 127.0.0.1 --port "$(PORT)" --strictPort

test-java:
	cd backend-java && $(JAVA_GRADLE_ENV) $(JAVA_GRADLE) test

java-metrics:
	cd backend-java && $(JAVA_GRADLE_ENV) $(JAVA_GRADLE) javaMetrics

parity-java:
	$(JAVA_GRADLE_ENV) node scripts/parity-smoke.mjs

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
