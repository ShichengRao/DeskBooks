"""Seed a fully synthetic "Demo" profile.

The persona is a 30-year-old in Philadelphia near the median wage, anchored to
public benchmarks (BLS 25-34 weekly earnings, Vanguard's median 401(k) balance
at 30, the Fed SCF under-35 median net worth, and typical 1BR rent). Every
account, merchant, and amount is invented; the RNG is seeded so output is
deterministic.

Two entry points:

- ``bootstrap_demo_data_dir(data_dir)`` — create ``profiles.json`` (demo as the
  only, active profile) plus ``profiles/demo.db`` inside ``data_dir`` if not
  already present. Used by the hosted read-only demo on cold start.
- CLI: ``python scripts/seed_demo_profile.py --data-dir <dir>`` for local use.
"""
from __future__ import annotations

import argparse
import json
import random
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from app import models

START = date(2025, 5, 1)
END = date(2026, 7, 30)
RNG_SEED = 20260731


def _d(x: float | int | str) -> Decimal:
    return Decimal(str(round(float(x), 2)))


def seed_demo_db(db_path: Path) -> dict:
    """Create the demo SQLite file at db_path and fill it. Returns counts."""
    rng = random.Random(RNG_SEED)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    engine = create_engine(f"sqlite:///{db_path}", future=True)
    models.Base.metadata.create_all(engine)
    session = Session(engine)

    accounts: dict[str, models.Account] = {}

    def account(key: str, name: str, institution: str, cat: str, typ: str) -> None:
        row = models.Account(
            name=name,
            institution=institution,
            account_category=models.AccountCategory(cat),
            type=models.AccountType(typ),
            sign_convention=models.SignConvention.outflow_negative,
        )
        session.add(row)
        session.flush()
        accounts[key] = row

    account("chk", "Everyday Checking", "Keystone Bank", "bank", "checking")
    account("sav", "High-Yield Savings", "Beacon Savings", "bank", "savings")
    account("cc", "Rewards Card", "Keystone Bank", "credit", "credit_card")
    account("k401", "401(k) at Work", "Meridian Retirement", "tax_advantaged", "retirement")
    account("roth", "Roth IRA", "Compass Brokerage", "tax_advantaged", "retirement")
    account("loan", "Grad School Loan", "Nelnet", "liability", "other")

    categories: dict[str, models.Category] = {}

    def cat(key: str, name: str, kind: str, parent: str | None = None, sort: int = 0) -> None:
        row = models.Category(
            name=name,
            kind=models.CategoryKind(kind),
            parent_id=categories[parent].id if parent else None,
            sort_order=sort,
        )
        session.add(row)
        session.flush()
        categories[key] = row

    cat("home", "Home", "expense", sort=10)
    cat("rent", "Rent", "expense", "home", 11)
    cat("utilities", "Utilities", "expense", "home", 12)
    cat("internet_phone", "Internet & Phone", "expense", "home", 13)
    cat("food", "Food & Drink", "expense", sort=20)
    cat("groceries", "Groceries", "expense", "food", 21)
    cat("restaurants", "Restaurants & Bars", "expense", "food", 22)
    cat("coffee", "Coffee & Snacks", "expense", "food", 23)
    cat("around", "Getting Around", "expense", sort=30)
    cat("transit", "Transit", "expense", "around", 31)
    cat("rideshare", "Rideshare", "expense", "around", 32)
    cat("fun", "Fun Money", "expense", sort=40)
    cat("subs", "Subscriptions", "expense", "fun", 41)
    cat("hobbies", "Hobbies & Nights Out", "expense", "fun", 42)
    cat("health", "Health & Fitness", "expense", sort=50)
    cat("gym", "Gym", "expense", "health", 51)
    cat("pharmacy", "Pharmacy & Copays", "expense", "health", 52)
    cat("stuff", "Shopping", "expense", sort=60)
    cat("personal", "Personal Care", "expense", sort=65)
    cat("travel", "Travel", "expense", sort=70)
    cat("gifts", "Gifts", "expense", sort=80)
    cat("loan_pay", "Student Loan", "expense", sort=82)
    cat("giving", "Giving", "donation", sort=85)
    cat("paycheck", "Paycheck", "income", sort=90)
    cat("interest", "Interest", "income", sort=91)
    cat("refund", "Tax Refund", "income", sort=92)
    cat("retire", "Retirement Contributions", "investment", sort=95)
    cat("transfers", "Transfers", "transfer", sort=99)
    session.commit()

    bal = {"chk": 2600.0, "sav": 8600.0, "cc": -480.0, "k401": 15400.0, "roth": 5800.0}
    loan_start = -9500.0
    loan_principal_per_month = 145.0
    cc_statement = 480.0

    txns: list[models.Transaction] = []

    def txn(acct, when, desc, amount, kind, category=None, merchant=None, user_cat=True):
        row = models.Transaction(
            account_id=accounts[acct].id,
            date=when,
            description_raw=desc,
            merchant=merchant,
            amount=_d(amount),
            category_id=categories[category].id if category else None,
            kind=models.TransactionKind(kind),
            is_user_categorized=user_cat,
        )
        bal[acct] += float(amount)
        txns.append(row)
        return row

    grocers = [
        ("TRADER JOE S #520 PHILADELPHIA PA", "Trader Joe's", 24, 78),
        ("GIANT HEIRLOOM MKT 786 PHILADELPHIA", "Giant", 30, 96),
        ("ALDI 66081 PHILADELPHIA PA", "Aldi", 18, 62),
        ("SPROUTS FARMERS MKT #612", "Sprouts", 22, 70),
    ]
    coffee = [
        ("WAWA 8021 PHILADELPHIA PA", "Wawa", 4.2, 11.5),
        ("LA COLOMBE COFFEE PHILADELPHIA", "La Colombe", 4.8, 9.5),
        ("SAXBYS PHILADELPHIA PA", "Saxbys", 5.0, 9.0),
    ]
    restaurants = [
        ("ANGELOS PIZZERIA PHILADELPHIA", "Angelo's Pizzeria", 16, 42),
        ("DALESSANDROS STEAKS PHILADELPHIA", "Dalessandro's", 14, 26),
        ("EL VEZ PHILADELPHIA PA", "El Vez", 38, 84),
        ("HAN DYNASTY UNIV CITY", "Han Dynasty", 28, 64),
        ("GRUBHUB*THAI SINGHA HOUSE", "Grubhub", 22, 47),
        ("FEDERAL DONUTS PHILADELPHIA", "Federal Donuts", 9, 21),
        ("MONKS CAFE PHILADELPHIA PA", "Monk's Cafe", 24, 58),
    ]
    shopping = [
        ("AMAZON MKTPL*RT4Y82 AMZN.COM/BILL", "Amazon", 12, 74),
        ("TARGET 00021032 PHILADELPHIA", "Target", 18, 88),
        ("UNIQLO PHILADELPHIA PA", "Uniqlo", 25, 90),
        ("BARNES & NOBLE #2624", "Barnes & Noble", 14, 38),
    ]
    hobbies = [
        ("PHILADELPHIA MUSEUM OF ART", "Philadelphia Museum of Art", 18, 30),
        ("RITZ FIVE THEATER PHILADELPHIA", "Ritz Five", 14, 34),
        ("STEAMPOWERED GAMES 425-952-2985", "Steam", 8, 40),
        ("BOWLERO SOUTH PHILADELPHIA", "Bowlero", 22, 48),
        ("PHILLIES TICKETS CITIZENS BANK PK", "Phillies", 34, 92),
    ]
    one_offs = {
        (2025, 8): [(9, "IKEA CONSHOHOCKEN PA", -263.90, "stuff", "IKEA")],
        (2025, 9): [(23, "CENTER CITY DENTAL ASSOC", -140.00, "pharmacy", "Center City Dental")],
        (2025, 10): [(11, "TICKETMASTER UNION TRANSFER", -118.50, "hobbies", "Ticketmaster")],
        (2025, 11): [(21, "AMERICAN AIR 0012383719 FORT WORTH", -318.60, "travel", "American Airlines")],
        (2025, 12): [
            (14, "ETSY.COM GIFT ORDER", -86.40, "gifts", "Etsy"),
            (18, "TARGET 00021032 HOLIDAY GIFTS", -142.75, "gifts", "Target"),
        ],
        (2026, 1): [(17, "BEST BUY #1499 PHONE UPGRADE", -429.99, "stuff", "Best Buy")],
        (2026, 2): [(8, "VYBE URGENT CARE PHILADELPHIA", -95.00, "pharmacy", "vybe urgent care")],
        (2026, 5): [
            (15, "AMTRAK .COM 855-268-7252", -98.00, "travel", "Amtrak"),
            (16, "HOTEL INDIGO OLD TOWN ALEXANDRIA", -224.30, "travel", "Hotel Indigo"),
        ],
    }

    def rand_amt(lo, hi):
        return -round(rng.uniform(lo, hi), 2)

    def month_days(y, m):
        nxt = date(y + (m == 12), (m % 12) + 1, 1)
        return (nxt - date(y, m, 1)).days

    payday = date(2025, 5, 2)
    paydays = []
    while payday <= END:
        paydays.append(payday)
        payday += timedelta(days=14)

    net_pay = 1608.42  # ~$57.5k gross, 6% 401(k), ~22% effective tax
    k401_emp = 132.69  # 6% of biweekly gross
    k401_match = 66.35  # 3% employer match
    for pd in paydays:
        txn("chk", pd, "DIRECT DEP - SCHUYLKILL DESIGN CO PAYROLL", net_pay, "income", "paycheck",
            merchant="Schuylkill Design Co")
        txn("chk", pd, "ONLINE TRANSFER TO HIGH-YIELD SAVINGS", -125, "transfer", "transfers")
        txn("sav", pd, "ONLINE TRANSFER FROM EVERYDAY CHECKING", 125, "transfer", "transfers")
        txn("k401", pd, "EMPLOYEE CONTRIBUTION", k401_emp, "investment", "retire")
        txn("k401", pd, "EMPLOYER MATCH", k401_match, "investment", "retire")

    cur = date(START.year, START.month, 1)
    while cur <= END:
        y, m = cur.year, cur.month
        ndays = month_days(y, m)

        def day(n, y=y, m=m, ndays=ndays):
            return date(y, m, min(n, ndays))

        txn("chk", day(1), "SCHUYLKILL VALLEY PROPERTY MGMT RENT", -1495, "expense", "rent",
            merchant="Schuylkill Valley Property Mgmt")
        elec = 118 if m in (6, 7, 8) else 96 if m in (12, 1, 2) else 74
        txn("chk", day(9), "PECO ENERGY UTIL PAYMENT", -round(rng.uniform(elec * 0.9, elec * 1.15), 2),
            "expense", "utilities", merchant="PECO")
        gas = 88 if m in (12, 1, 2, 3) else 24
        txn("chk", day(16), "PHILA GAS WORKS BILL PAY", -round(rng.uniform(gas * 0.85, gas * 1.2), 2),
            "expense", "utilities", merchant="Philadelphia Gas Works")
        txn("chk", day(22), "XFINITY INTERNET 800-COMCAST", -65.00, "expense", "internet_phone",
            merchant="Xfinity")
        txn("chk", day(11), "T-MOBILE*AUTO PAY", -70.00, "expense", "internet_phone", merchant="T-Mobile")
        txn("chk", day(15), "NELNET STUDENT LN PYMT", -186.00, "expense", "loan_pay", merchant="Nelnet")
        txn("cc", day(24), "LEMONADE INSURANCE RENTERS", -19.25, "expense", "home", merchant="Lemonade")
        txn("cc", day(3), "NETFLIX.COM NETFLIX.COM CA", -15.49, "expense", "subs", merchant="Netflix")
        txn("cc", day(14), "SPOTIFY USA NEW YORK NY", -11.99, "expense", "subs", merchant="Spotify")
        txn("cc", day(7), "PLANET FIT CLUB FEES PHILADELPHIA", -15.00, "expense", "gym",
            merchant="Planet Fitness")
        if (y, m) == (2025, 6):
            txn("cc", day(19), "PLANET FIT ANNUAL FEE", -49.00, "expense", "gym", merchant="Planet Fitness")

        for _ in range(rng.randint(4, 6)):
            desc, merch, lo, hi = rng.choice(grocers)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "groceries", merchant=merch)
        for _ in range(rng.randint(5, 9)):
            desc, merch, lo, hi = rng.choice(coffee)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "coffee", merchant=merch)
        for _ in range(rng.randint(4, 7)):
            desc, merch, lo, hi = rng.choice(restaurants)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "restaurants", merchant=merch)
        for _ in range(2):
            txn("cc", day(rng.choice([4, 18])), "SEPTA KEY TRAVEL WALLET RELOAD", -50.00, "expense",
                "transit", merchant="SEPTA")
        for _ in range(rng.randint(2, 4)):
            svc = rng.choice([("LYFT *RIDE", "Lyft"), ("UBER TRIP HELP.UBER.COM", "Uber")])
            txn("cc", day(rng.randint(1, ndays)), svc[0], rand_amt(11, 29), "expense", "rideshare", merchant=svc[1])
        for _ in range(rng.randint(2, 4)):
            desc, merch, lo, hi = rng.choice(shopping)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "stuff", merchant=merch)
        for _ in range(rng.randint(1, 3)):
            desc, merch, lo, hi = rng.choice(hobbies)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "hobbies", merchant=merch)
        if m % 2 == 0:
            txn("cc", day(rng.randint(6, 24)), "GREAT CLIPS PHILADELPHIA PA", rand_amt(28, 34),
                "expense", "personal", merchant="Great Clips")
        if rng.random() < 0.7:
            txn("cc", day(rng.randint(3, 26)), "CVS/PHARMACY #04125 PHILADELPHIA", rand_amt(9, 36),
                "expense", "pharmacy", merchant="CVS")

        for dom, desc, amt, ckey, merch in one_offs.get((y, m), []):
            txn("cc", day(dom), desc, amt, "expense", ckey, merchant=merch)

        txn("sav", day(ndays), "INTEREST PAYMENT", round(bal["sav"] * 0.039 / 12, 2), "income", "interest")

        if rng.random() < 0.8:
            txn("chk", day(20), "TRANSFER TO COMPASS BROKERAGE ROTH IRA", -100, "investment", "retire")
            txn("roth", day(20), "CONTRIBUTION FROM EVERYDAY CHECKING", 100, "investment", "retire")

        if cc_statement > 0:
            pay = round(cc_statement, 2)
            txn("chk", day(6), "KEYSTONE CARD AUTOPAY", -pay, "cc_payment")
            txn("cc", day(6), "AUTOPAY PAYMENT RECEIVED - THANK YOU", pay, "cc_payment")
        cc_statement = max(0.0, -bal["cc"])

        cur = date(y + (m == 12), (m % 12) + 1, 1)

    txn("chk", date(2025, 12, 30), "PHILABUNDANCE DONATION", -75.00, "donation", "giving",
        merchant="Philabundance")
    txn("chk", date(2026, 4, 14), "IRS TREAS 310 TAX REF", 512.00, "income", "refund")
    txn("chk", date(2025, 10, 9), "EXPENSE REIMB - SCHUYLKILL DESIGN CO", 94.50, "reimbursement")

    # a shared beach weekend (4 people, user paid) so the Splits view has a
    # live group with money still owed back
    shore_charges = [
        (date(2026, 6, 5), "SHORE HOUSE RENTAL OCEAN CITY NJ", -840.00, "travel", "Shore House Rentals"),
        (date(2026, 6, 6), "ACME MARKETS 7942 OCEAN CITY NJ", -96.40, "groceries", "Acme Markets"),
        (date(2026, 6, 7), "MANCO & MANCO PIZZA BOARDWALK", -54.60, "restaurants", "Manco & Manco"),
    ]
    shore_rows = [
        txn("cc", when, desc, amt, "expense", ckey, merchant=merch)
        for when, desc, amt, ckey, merch in shore_charges
    ]
    shore_rows.append(
        txn("chk", date(2026, 6, 11), "ZELLE FROM ROOMMATE - SHORE WEEKEND", 300.00, "reimbursement")
    )
    shore_rows.append(
        txn("chk", date(2026, 6, 14), "ZELLE FROM COWORKER - SHORE WEEKEND", 240.00, "reimbursement")
    )

    # an unlinked purchase/refund pair so the netting view has a suggestion
    txn("cc", date(2026, 7, 10), "GYM EQUIPMENT ORDER - RETURNED", -89.99, "expense", "stuff",
        merchant="Fitness Warehouse")
    txn("cc", date(2026, 7, 18), "REFUND - GYM EQUIPMENT ORDER", 89.99, "refund", None,
        merchant="Fitness Warehouse")

    # recent uncategorized rows so the Rules workflow has something to demo
    for when, desc, amt in [
        (date(2026, 7, 24), "SQ *REANIMATOR COFFEE ROASTERS", -6.75),
        (date(2026, 7, 25), "TST* MIDDLE CHILD PHILADELPHIA", -18.40),
        (date(2026, 7, 26), "PARKMOBILE 8774727275", -3.60),
        (date(2026, 7, 27), "SQ *WEAVERS WAY CO-OP", -41.22),
        (date(2026, 7, 28), "TST* GOLDIE FALAFEL SANSOM ST", -14.85),
        (date(2026, 7, 29), "INDEGO BIKE SHARE PHILADELPHIA", -25.00),
    ]:
        txn("cc", when, desc, amt, "uncategorized", None, user_cat=False)

    session.add_all(txns)
    session.commit()

    # split rows: user fronted the shore weekend for 4 people (25% share);
    # the Zelle inflows are reimbursements against the same group
    for row in shore_rows:
        session.add(
            models.TransactionSplit(
                transaction_id=row.id,
                group_name="Shore weekend",
                personal_share=_d("0.25") if row.amount < 0 else _d("0"),
            )
        )
    session.commit()

    # monthly snapshots (1st of each month) plus a fresh final one
    snap_dates = []
    cur = date(2025, 6, 1)
    while cur <= date(2026, 7, 1):
        snap_dates.append(cur)
        cur = date(cur.year + (cur.month == 12), (cur.month % 12) + 1, 1)
    snap_dates.append(date(2026, 7, 30))

    start_bal = {"chk": 2600.0, "sav": 8600.0, "cc": -480.0, "k401": 15400.0, "roth": 5800.0}
    key_by_account_id = {accounts[k].id: k for k in accounts if k != "loan"}
    market = {
        (2025, 6): 0.011, (2025, 7): 0.014, (2025, 8): -0.008, (2025, 9): 0.016,
        (2025, 10): 0.009, (2025, 11): 0.018, (2025, 12): -0.004, (2026, 1): 0.012,
        (2026, 2): 0.007, (2026, 3): -0.016, (2026, 4): 0.010, (2026, 5): 0.013,
        (2026, 6): 0.008, (2026, 7): 0.009,
    }
    for sd in snap_dates:
        running = dict(start_bal)
        for t in txns:
            if t.date < sd:
                running[key_by_account_id[t.account_id]] += float(t.amount)
        factor = 1.0
        for (yy, mm), drift in market.items():
            if date(yy, mm, 1) < sd:
                factor *= 1 + drift
        snap = models.NetWorthSnapshot(snapshot_date=sd)
        session.add(snap)
        session.flush()
        for key in ("chk", "sav", "cc", "k401", "roth"):
            value = running[key]
            if key in ("k401", "roth"):
                base = start_bal[key]
                contrib = running[key] - base
                value = base * factor + contrib * (1 + (factor - 1) / 2)
            session.add(models.AccountBalance(
                snapshot_id=snap.id, account_id=accounts[key].id, balance=_d(value)))
        months_elapsed = (sd.year - 2025) * 12 + sd.month - 5
        session.add(models.AccountBalance(
            snapshot_id=snap.id, account_id=accounts["loan"].id,
            balance=_d(loan_start + loan_principal_per_month * months_elapsed)))

    budgets = {
        "rent": 1495, "utilities": 165, "internet_phone": 135, "groceries": 320,
        "restaurants": 240, "coffee": 55, "transit": 100, "rideshare": 60,
        "subs": 45, "hobbies": 110, "gym": 15, "pharmacy": 40, "stuff": 200,
        "personal": 20, "travel": 90, "gifts": 30, "loan_pay": 186,
    }
    for key, amt in budgets.items():
        session.add(models.BudgetDefault(category_id=categories[key].id, amount=_d(amt)))

    rules = [
        ("Grocery stores", r"TRADER JOE|GIANT HEIRLOOM|ALDI|SPROUTS", "groceries", "expense"),
        ("Coffee shops", r"WAWA|LA COLOMBE|SAXBYS", "coffee", "expense"),
        ("SEPTA reloads", r"SEPTA", "transit", "expense"),
        ("Streaming", r"NETFLIX|SPOTIFY", "subs", "expense"),
        ("Gym dues", r"PLANET FIT", "gym", "expense"),
        ("Rideshare", r"LYFT|UBER TRIP", "rideshare", "expense"),
        ("Utilities", r"PECO ENERGY|PHILA GAS WORKS", "utilities", "expense"),
        ("Internet & phone", r"XFINITY|T-MOBILE", "internet_phone", "expense"),
        ("Paychecks", r"SCHUYLKILL DESIGN CO PAYROLL", "paycheck", "income"),
        ("Student loan", r"NELNET", "loan_pay", "expense"),
    ]
    for i, (name, pattern, ckey, kind) in enumerate(rules):
        session.add(models.Rule(
            name=name, priority=100 + i, match_description_pattern=pattern,
            set_category_id=categories[ckey].id, set_kind=models.TransactionKind(kind),
        ))

    session.add(models.Goal(
        title="Emergency fund: 6 months of rent",
        target_amount=_d(9000), target_date=date(2027, 6, 1),
        kind=models.GoalKind.savings, linked_account_ids=[accounts["sav"].id],
        notes_markdown="Cover rent + essentials if work dries up.",
    ))
    session.add(models.JournalEntry(
        entry_date=date(2026, 7, 30), title="Demo profile",
        body_markdown="Synthetic data for demos: a 30-year-old in Philadelphia "
        "near the median wage. Every merchant, account, and amount here is invented.",
    ))
    session.commit()
    counts = {"transactions": len(txns), "snapshots": len(snap_dates)}
    session.close()
    engine.dispose()
    return counts


def bootstrap_demo_data_dir(data_dir: Path, force: bool = False) -> dict:
    """Create a self-contained data dir whose only profile is the demo."""
    registry_path = data_dir / "profiles.json"
    db_path = data_dir / "profiles" / "demo.db"
    if registry_path.exists() and db_path.exists() and not force:
        return {"skipped": True}
    data_dir.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()
    counts = seed_demo_db(db_path)
    registry_path.write_text(
        json.dumps(
            {
                "active": "demo",
                "profiles": [{"slug": "demo", "name": "Demo", "db_file": "profiles/demo.db"}],
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", required=True, help="Data dir to bootstrap (registry + demo.db)")
    parser.add_argument("--force", action="store_true", help="Recreate demo.db even if present")
    args = parser.parse_args()
    result = bootstrap_demo_data_dir(Path(args.data_dir).expanduser(), force=args.force)
    print(result)


if __name__ == "__main__":
    main()
