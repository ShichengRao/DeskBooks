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

    session.add(models.FireSettings(
        annual_retirement_spending=_d(45000),
        birth_year=1996,
        retirement_age=65,
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


def seed_family_db(db_path: Path) -> dict:
    """Second persona: a family of three in Philadelphia — two earners in
    their mid-30s, a toddler in daycare, a rowhouse with a mortgage, two
    401(k)s, a 529, and a car loan. Anchored loosely to median family
    figures; everything is invented."""
    rng = random.Random(19910403)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    engine = create_engine(f"sqlite:///{db_path}", future=True)
    models.Base.metadata.create_all(engine)
    session = Session(engine)

    accounts: dict[str, models.Account] = {}

    def account(key, name, institution, cat, typ):
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

    account("chk", "Joint Checking", "Keystone Bank", "bank", "checking")
    account("sav", "Emergency Savings", "Beacon Savings", "bank", "savings")
    account("cc", "Family Rewards Card", "Keystone Bank", "credit", "credit_card")
    account("k401a", "401(k) — Riley", "Meridian Retirement", "tax_advantaged", "retirement")
    account("k401b", "403(b) — Jordan", "Horizon Retirement", "tax_advantaged", "retirement")
    account("plan529", "529 College Plan", "Keystone 529", "tax_advantaged", "college")
    account("house", "Rowhouse on Ritner St", None, "property", "other")
    account("mort", "Home Mortgage", "Keystone Mortgage", "liability", "other")
    account("car", "Auto Loan", "Toyota Financial", "liability", "other")

    categories: dict[str, models.Category] = {}

    def cat(key, name, kind, parent=None, sort=0):
        row = models.Category(
            name=name,
            kind=models.CategoryKind(kind),
            parent_id=categories[parent].id if parent else None,
            sort_order=sort,
        )
        session.add(row)
        session.flush()
        categories[key] = row

    cat("housing", "Housing", "expense", sort=10)
    cat("mortgage", "Mortgage", "expense", "housing", 11)
    cat("utilities", "Utilities", "expense", "housing", 12)
    cat("internet_phone", "Internet & Phone", "expense", "housing", 13)
    cat("home", "Home & Garden", "expense", "housing", 14)
    cat("kids", "Kids", "expense", sort=20)
    cat("daycare", "Daycare", "expense", "kids", 21)
    cat("kid_gear", "Kid Gear & Clothes", "expense", "kids", 22)
    cat("activities", "Activities & Classes", "expense", "kids", 23)
    cat("food", "Food", "expense", sort=30)
    cat("groceries", "Groceries", "expense", "food", 31)
    cat("takeout", "Takeout & Restaurants", "expense", "food", 32)
    cat("cars", "Cars", "expense", sort=40)
    cat("car_payment", "Car Payment", "expense", "cars", 41)
    cat("gas", "Gas", "expense", "cars", 42)
    cat("car_ins", "Insurance & Tolls", "expense", "cars", 43)
    cat("health", "Health", "expense", sort=50)
    cat("medical", "Medical & Copays", "expense", "health", 51)
    cat("life_ins", "Life Insurance", "expense", "health", 52)
    cat("fun", "Fun & Leisure", "expense", sort=60)
    cat("streaming", "Streaming", "expense", "fun", 61)
    cat("outings", "Family Outings", "expense", "fun", 62)
    cat("stuff", "Shopping", "expense", sort=70)
    cat("personal", "Personal Care", "expense", sort=75)
    cat("travel", "Travel", "expense", sort=80)
    cat("gifts", "Gifts", "expense", sort=85)
    cat("giving", "Giving", "donation", sort=88)
    cat("paycheck", "Paychecks", "income", sort=90)
    cat("interest", "Interest", "income", sort=91)
    cat("refund", "Tax Refund", "income", sort=92)
    cat("retire", "Retirement & College", "investment", sort=95)
    cat("transfers", "Transfers", "transfer", sort=99)
    session.commit()

    bal = {"chk": 5800.0, "sav": 15500.0, "cc": -880.0, "k401a": 52000.0,
           "k401b": 31500.0, "plan529": 5400.0}
    HOUSE_START, HOUSE_DRIFT = 302000.0, 950.0
    MORT_START, MORT_PRINCIPAL = -254800.0, 450.0
    CAR_START, CAR_PRINCIPAL = -12400.0, 300.0
    cc_statement = 880.0

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
        ("GIANT 6084 PHILADELPHIA PA", "Giant", 45, 165),
        ("ALDI 66012 PHILADELPHIA PA", "Aldi", 30, 95),
        ("TARGET 00021032 GROCERY", "Target", 28, 110),
        ("WEGMANS #084 CHERRY HILL NJ", "Wegmans", 60, 170),
    ]
    takeout = [
        ("HONEYGROW PHILADELPHIA PA", "Honeygrow", 24, 46),
        ("CHICK-FIL-A #02444", "Chick-fil-A", 21, 38),
        ("SANTUCCI S SQUARE PIZZA", "Santucci's", 26, 52),
        ("TACONELLI S PIZZERIA", "Tacconelli's", 30, 58),
        ("GRUBHUB*SANG KEE NOODLE", "Grubhub", 34, 66),
    ]
    kid_stuff = [
        ("CARTERS #1123 PHILADELPHIA", "Carter's", 18, 54),
        ("AMAZON MKTPL*DIAPERS SUBSCRIBE", "Amazon", 32, 58),
        ("TARGET 00021032 KIDS", "Target", 14, 68),
        ("ONCE UPON A CHILD", "Once Upon a Child", 12, 40),
    ]
    outings = [
        ("PLEASE TOUCH MUSEUM", "Please Touch Museum", 21, 42),
        ("PHILADELPHIA ZOO", "Philadelphia Zoo", 24, 58),
        ("FRANKLIN SQUARE MINI GOLF", "Franklin Square", 16, 34),
        ("SMITH MEMORIAL PLAYGROUND GIFT SHOP", "Smith Playground", 8, 22),
    ]
    shopping = [
        ("AMAZON MKTPL*RT7Q21 AMZN.COM/BILL", "Amazon", 14, 82),
        ("OLD NAVY US 5641", "Old Navy", 22, 74),
        ("TARGET 00021032 PHILADELPHIA", "Target", 20, 96),
        ("HOME DEPOT #4109", "Home Depot", 18, 120),
    ]

    def rand_amt(lo, hi):
        return -round(rng.uniform(lo, hi), 2)

    def month_days(y, m):
        nxt = date(y + (m == 12), (m % 12) + 1, 1)
        return (nxt - date(y, m, 1)).days

    pay_a = date(2025, 5, 2)
    pays_a = []
    while pay_a <= END:
        pays_a.append(pay_a)
        pay_a += timedelta(days=14)
    pays_b = [d + timedelta(days=7) for d in pays_a if d + timedelta(days=7) <= END]

    NET_A = 1742.55   # ~$67k gross, 6% 401(k)
    NET_B = 1394.20   # ~$51k gross, 4% 403(b)
    for pd in pays_a:
        txn("chk", pd, "DIRECT DEP - LIBERTY BRIDGE ENG PAYROLL", NET_A, "income", "paycheck",
            merchant="Liberty Bridge Engineering")
        txn("chk", pd, "ONLINE TRANSFER TO EMERGENCY SAVINGS", -100, "transfer", "transfers")
        txn("sav", pd, "ONLINE TRANSFER FROM JOINT CHECKING", 100, "transfer", "transfers")
        txn("k401a", pd, "EMPLOYEE CONTRIBUTION", 154.62, "investment", "retire")
        txn("k401a", pd, "EMPLOYER MATCH", 77.31, "investment", "retire")
    for pd in pays_b:
        txn("chk", pd, "DIRECT DEP - SCHUYLKILL CHARTER SCHOOL", NET_B, "income", "paycheck",
            merchant="Schuylkill Charter School")
        txn("k401b", pd, "EMPLOYEE CONTRIBUTION", 78.46, "investment", "retire")
        txn("k401b", pd, "EMPLOYER MATCH", 39.23, "investment", "retire")

    one_offs = {
        (2025, 6): [("cc", 14, "POCONO CABIN RENTAL GETAWAY", -412.00, "travel", "Pocono Cabins")],
        (2025, 9): [("chk", 8, "PHILADELPHIA ZOO MEMBERSHIP", -199.00, "outings", "Philadelphia Zoo")],
        (2025, 10): [("cc", 17, "CENTER CITY DENTAL FAMILY VISIT", -240.00, "medical", "Center City Dental")],
        (2025, 11): [("cc", 22, "AMERICAN AIR 0012399811 FORT WORTH", -778.20, "travel", "American Airlines")],
        (2025, 12): [
            ("cc", 13, "TARGET 00021032 HOLIDAY TOYS", -186.44, "gifts", "Target"),
            ("cc", 19, "BARNES & NOBLE KIDS GIFTS", -74.85, "gifts", "Barnes & Noble"),
            ("chk", 29, "PHILABUNDANCE YEAR-END GIFT", -120.00, "giving", "Philabundance"),
        ],
        (2026, 1): [("cc", 11, "VYBE URGENT CARE PHILADELPHIA", -125.00, "medical", "vybe urgent care")],
        (2026, 6): [("cc", 20, "LBI BEACH HOUSE WEEK RENTAL", -1450.00, "travel", "LBI Rentals")],
    }

    cur = date(START.year, START.month, 1)
    while cur <= END:
        y, m = cur.year, cur.month
        ndays = month_days(y, m)

        def day(n, y=y, m=m, ndays=ndays):
            return date(y, m, min(n, ndays))

        txn("chk", day(1), "KEYSTONE MORTGAGE SVC PAYMENT", -1568.00, "expense", "mortgage",
            merchant="Keystone Mortgage")
        txn("chk", day(3), "BRIGHT BEGINNINGS EARLY LEARNING CTR", -1285.00, "expense", "daycare",
            merchant="Bright Beginnings")
        elec = 172 if m in (6, 7, 8) else 138 if m in (12, 1, 2) else 105
        txn("chk", day(9), "PECO ENERGY UTIL PAYMENT", -round(rng.uniform(elec * 0.9, elec * 1.15), 2),
            "expense", "utilities", merchant="PECO")
        gas_bill = 128 if m in (12, 1, 2, 3) else 32
        txn("chk", day(16), "PHILA GAS WORKS BILL PAY", -round(rng.uniform(gas_bill * 0.85, gas_bill * 1.2), 2),
            "expense", "utilities", merchant="Philadelphia Gas Works")
        txn("chk", day(12), "PHILA WATER DEPT AUTOPAY", -68.00, "expense", "utilities",
            merchant="Philadelphia Water Dept")
        txn("chk", day(22), "VERIZON FIOS AUTOPAY", -74.99, "expense", "internet_phone",
            merchant="Verizon Fios")
        txn("chk", day(11), "T-MOBILE FAMILY PLAN AUTOPAY", -125.00, "expense", "internet_phone",
            merchant="T-Mobile")
        txn("chk", day(15), "TOYOTA FINANCIAL SVC PAYMENT", -318.00, "expense", "car_payment",
            merchant="Toyota Financial")
        txn("chk", day(6), "GEICO AUTO PREMIUM", -172.40, "expense", "car_ins", merchant="GEICO")
        txn("chk", day(18), "HAVEN LIFE TERM PREMIUM", -41.50, "expense", "life_ins",
            merchant="Haven Life")
        txn("chk", day(19), "HAVEN LIFE TERM PREMIUM", -28.75, "expense", "life_ins",
            merchant="Haven Life")
        if m % 2 == 0:
            txn("chk", day(24), "E-ZPASS PA AUTO REPLENISH", -35.00, "expense", "car_ins",
                merchant="E-ZPass")
        txn("cc", day(4), "NETFLIX.COM NETFLIX.COM CA", -15.49, "expense", "streaming", merchant="Netflix")
        txn("cc", day(8), "DISNEY PLUS BURBANK CA", -9.99, "expense", "streaming", merchant="Disney+")
        txn("cc", day(14), "SPOTIFY FAMILY NEW YORK NY", -16.99, "expense", "streaming", merchant="Spotify")
        txn("cc", day(10), "THE LITTLE GYM OF PHILADELPHIA", -89.00, "expense", "activities",
            merchant="The Little Gym")

        big = rng.choice([("COSTCO WHSE #1224 PHILADELPHIA", "Costco", 185, 320)])
        txn("cc", day(rng.randint(2, 9)), big[0], rand_amt(big[2], big[3]), "expense", "groceries",
            merchant=big[1])
        for _ in range(rng.randint(7, 10)):
            desc, merch, lo, hi = rng.choice(grocers)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "groceries", merchant=merch)
        for _ in range(rng.randint(4, 7)):
            desc, merch, lo, hi = rng.choice(takeout)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "takeout", merchant=merch)
        for _ in range(rng.randint(3, 5)):
            desc, merch, lo, hi = rng.choice(kid_stuff)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "kid_gear", merchant=merch)
        for _ in range(rng.randint(3, 5)):
            svc = rng.choice([("WAWA FUEL 8021 PHILADELPHIA", "Wawa"), ("SUNOCO 0334 PASSYUNK AVE", "Sunoco")])
            txn("cc", day(rng.randint(1, ndays)), svc[0], rand_amt(28, 52), "expense", "gas", merchant=svc[1])
        for _ in range(rng.randint(1, 3)):
            desc, merch, lo, hi = rng.choice(outings)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "outings", merchant=merch)
        for _ in range(rng.randint(3, 6)):
            desc, merch, lo, hi = rng.choice(shopping)
            txn("cc", day(rng.randint(1, ndays)), desc, rand_amt(lo, hi), "expense", "stuff", merchant=merch)
        if rng.random() < 0.8:
            txn("cc", day(rng.randint(3, 26)), "CHOP PEDIATRIC ASSOC COPAY", rand_amt(25, 45),
                "expense", "medical", merchant="CHOP Pediatrics")
        if rng.random() < 0.6:
            txn("cc", day(rng.randint(3, 26)), "CVS/PHARMACY #04125 PHILADELPHIA", rand_amt(9, 34),
                "expense", "medical", merchant="CVS")
        if m % 2 == 1:
            txn("cc", day(rng.randint(6, 24)), "GREAT CLIPS PHILADELPHIA PA", rand_amt(52, 68),
                "expense", "personal", merchant="Great Clips")

        for acct, dom, desc, amt, ckey, merch in one_offs.get((y, m), []):
            txn(acct, day(dom), desc, amt, "expense" if ckey != "giving" else "donation",
                ckey, merchant=merch)

        txn("sav", day(ndays), "INTEREST PAYMENT", round(bal["sav"] * 0.039 / 12, 2), "income", "interest")
        txn("chk", day(20), "TRANSFER TO KEYSTONE 529 PLAN", -150, "investment", "retire")
        txn("plan529", day(20), "CONTRIBUTION FROM JOINT CHECKING", 150, "investment", "retire")

        if cc_statement > 0:
            pay = round(cc_statement, 2)
            txn("chk", day(7), "KEYSTONE CARD AUTOPAY", -pay, "cc_payment")
            txn("cc", day(7), "AUTOPAY PAYMENT RECEIVED - THANK YOU", pay, "cc_payment")
        cc_statement = max(0.0, -bal["cc"])

        cur = date(y + (m == 12), (m % 12) + 1, 1)

    txn("chk", date(2026, 4, 14), "IRS TREAS 310 TAX REF", 1860.00, "income", "refund")
    # an unlinked purchase/refund pair for the netting view
    txn("cc", date(2026, 7, 8), "TARGET 00021032 RETURNED BOOSTER SEAT", -64.99, "expense", "kid_gear",
        merchant="Target")
    txn("cc", date(2026, 7, 15), "REFUND - TARGET RETURN", 64.99, "refund", None, merchant="Target")

    for when, desc, amt in [
        (date(2026, 7, 23), "SQ *MIGHTY BREAD CO", -9.50),
        (date(2026, 7, 25), "TST* EL PURO ROSTIZADO", -47.20),
        (date(2026, 7, 27), "PARKMOBILE 8774727275", -4.10),
        (date(2026, 7, 28), "SQ *LITTLE SUNFLOWERS CRAFT FAIR", -22.00),
        (date(2026, 7, 29), "INDEGO BIKE SHARE PHILADELPHIA", -25.00),
    ]:
        txn("cc", when, desc, amt, "uncategorized", None, user_cat=False)

    session.add_all(txns)
    session.commit()

    # snapshots: monthly + fresh final; house/mortgage/car are snapshot-only
    snap_dates = []
    cur = date(2025, 6, 1)
    while cur <= date(2026, 7, 1):
        snap_dates.append(cur)
        cur = date(cur.year + (cur.month == 12), (cur.month % 12) + 1, 1)
    snap_dates.append(date(2026, 7, 30))

    start_bal = {"chk": 5800.0, "sav": 15500.0, "cc": -880.0, "k401a": 52000.0,
                 "k401b": 31500.0, "plan529": 5400.0}
    key_by_account_id = {accounts[k].id: k for k in start_bal}
    market = {
        (2025, 6): 0.011, (2025, 7): 0.014, (2025, 8): -0.008, (2025, 9): 0.016,
        (2025, 10): 0.009, (2025, 11): 0.018, (2025, 12): -0.004, (2026, 1): 0.012,
        (2026, 2): 0.007, (2026, 3): -0.016, (2026, 4): 0.010, (2026, 5): 0.013,
        (2026, 6): 0.008, (2026, 7): 0.009,
    }
    for sd in snap_dates:
        running = dict(start_bal)
        for t in txns:
            if t.date < sd and t.account_id in key_by_account_id:
                running[key_by_account_id[t.account_id]] += float(t.amount)
        factor = 1.0
        for (yy, mm), drift in market.items():
            if date(yy, mm, 1) < sd:
                factor *= 1 + drift
        months_elapsed = (sd.year - 2025) * 12 + sd.month - 5
        snap = models.NetWorthSnapshot(snapshot_date=sd)
        session.add(snap)
        session.flush()
        for key in start_bal:
            value = running[key]
            if key in ("k401a", "k401b", "plan529"):
                base = start_bal[key]
                contrib = running[key] - base
                value = base * factor + contrib * (1 + (factor - 1) / 2)
            session.add(models.AccountBalance(
                snapshot_id=snap.id, account_id=accounts[key].id, balance=_d(value)))
        house_wiggle = rng.uniform(-1200, 1200)
        session.add(models.AccountBalance(
            snapshot_id=snap.id, account_id=accounts["house"].id,
            balance=_d(HOUSE_START + HOUSE_DRIFT * months_elapsed + house_wiggle)))
        session.add(models.AccountBalance(
            snapshot_id=snap.id, account_id=accounts["mort"].id,
            balance=_d(MORT_START + MORT_PRINCIPAL * months_elapsed)))
        session.add(models.AccountBalance(
            snapshot_id=snap.id, account_id=accounts["car"].id,
            balance=_d(CAR_START + CAR_PRINCIPAL * months_elapsed)))

    budgets = {
        "mortgage": 1568, "daycare": 1285, "groceries": 950, "takeout": 250,
        "utilities": 330, "internet_phone": 200, "home": 120, "kid_gear": 150,
        "activities": 150, "car_payment": 318, "gas": 165, "car_ins": 210,
        "medical": 120, "life_ins": 71, "streaming": 43, "outings": 90,
        "stuff": 280, "personal": 60, "travel": 220, "gifts": 80,
    }
    for key, amt in budgets.items():
        session.add(models.BudgetDefault(category_id=categories[key].id, amount=_d(amt)))

    rules = [
        ("Mortgage", r"KEYSTONE MORTGAGE", "mortgage", "expense"),
        ("Daycare", r"BRIGHT BEGINNINGS", "daycare", "expense"),
        ("Grocery stores", r"GIANT|ALDI|WEGMANS|COSTCO", "groceries", "expense"),
        ("Gas stations", r"WAWA FUEL|SUNOCO", "gas", "expense"),
        ("Car insurance & tolls", r"GEICO|E-ZPASS", "car_ins", "expense"),
        ("Car payment", r"TOYOTA FINANCIAL", "car_payment", "expense"),
        ("Utilities", r"PECO ENERGY|PHILA GAS WORKS|PHILA WATER", "utilities", "expense"),
        ("Internet & phone", r"VERIZON FIOS|T-MOBILE", "internet_phone", "expense"),
        ("Streaming", r"NETFLIX|DISNEY PLUS|SPOTIFY", "streaming", "expense"),
        ("Pediatrics & pharmacy", r"CHOP PEDIATRIC|CVS", "medical", "expense"),
        ("Life insurance", r"HAVEN LIFE", "life_ins", "expense"),
        ("Paychecks", r"PAYROLL|CHARTER SCHOOL", "paycheck", "income"),
    ]
    for i, (name, pattern, ckey, kind) in enumerate(rules):
        session.add(models.Rule(
            name=name, priority=100 + i, match_description_pattern=pattern,
            set_category_id=categories[ckey].id, set_kind=models.TransactionKind(kind),
        ))

    session.add(models.FireSettings(
        annual_retirement_spending=_d(70000),
        birth_year=1991,
        retirement_age=65,
    ))
    session.add(models.Goal(
        title="529: cover half of in-state tuition",
        target_amount=_d(40000), target_date=date(2040, 8, 1),
        kind=models.GoalKind.savings, linked_account_ids=[accounts["plan529"].id],
        notes_markdown="Rough target: half of four years at a PA state school.",
    ))
    session.add(models.JournalEntry(
        entry_date=date(2026, 7, 30), title="Demo profile",
        body_markdown="Synthetic data for demos: a family of three in Philadelphia. "
        "Every person, merchant, account, and amount here is invented.",
    ))
    session.commit()
    counts = {"transactions": len(txns), "snapshots": len(snap_dates)}
    session.close()
    engine.dispose()
    return counts


def bootstrap_demo_data_dir(data_dir: Path, force: bool = False) -> dict:
    """Create a self-contained data dir holding only the demo profiles."""
    registry_path = data_dir / "profiles.json"
    demo_path = data_dir / "profiles" / "demo.db"
    family_path = data_dir / "profiles" / "demo-family.db"
    if registry_path.exists() and demo_path.exists() and family_path.exists() and not force:
        return {"skipped": True}
    data_dir.mkdir(parents=True, exist_ok=True)
    for path in (demo_path, family_path):
        if path.exists():
            path.unlink()
    counts = {
        "demo": seed_demo_db(demo_path),
        "demo-family": seed_family_db(family_path),
    }
    registry_path.write_text(
        json.dumps(
            {
                "active": "demo",
                "profiles": [
                    {"slug": "demo", "name": "Demo", "db_file": "profiles/demo.db"},
                    {"slug": "demo-family", "name": "Demo Family", "db_file": "profiles/demo-family.db"},
                ],
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
