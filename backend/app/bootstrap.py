from __future__ import annotations

from sqlalchemy import select

from .db import SessionLocal, init_db
from .models import Account, Category
from .onboarding import seed_starter_data
from .profiles import get_active_profile


def bootstrap() -> dict:
    active = get_active_profile()
    init_db()
    db = SessionLocal()
    try:
        has_existing_data = bool(
            db.execute(select(Account.id).limit(1)).first()
            or db.execute(select(Category.id).limit(1)).first()
        )
        if has_existing_data:
            summary = {"starter_seed_skipped": "existing data found"}
        else:
            summary = seed_starter_data(db)
    finally:
        db.close()
    return {"mode": "starter", "profile": active.slug, **summary}


if __name__ == "__main__":
    summary = bootstrap()
    print("bootstrap complete:")
    for key, value in summary.items():
        print(f"  {key}: {value}")
