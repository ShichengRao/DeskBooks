from __future__ import annotations

from decimal import Decimal
from io import BytesIO
from datetime import date

import openpyxl
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app import schemas
from app.importers.amex import AmexImporter
from app.importers.amex_xlsx import parse_amex_xlsx_bytes
from app.importers.chase_credit import ChaseCreditImporter
from app.importers.contribution_history import ContributionHistoryImporter
from app.importers.wells_fargo_checking import WellsFargoCheckingImporter
from app.models import Account, AccountCategory, AccountType, Base, NetWorthSnapshot, SignConvention, TransactionKind
from app.routers import snapshots


def test_chase_credit_importer_marks_payments_and_returns():
    rows = ChaseCreditImporter.parse(
        "\n".join(
            [
                "Transaction Date,Post Date,Description,Category,Type,Amount,Memo",
                "06/01/2026,06/02/2026,ACME GROCERY,Groceries,Sale,-42.18,",
                "06/10/2026,06/10/2026,AUTOPAY PAYMENT,Payment,Payment,50.68,",
                "06/12/2026,06/13/2026,RETURN MERCHANT,Shopping,Return,12.00,",
            ]
        )
    )

    assert len(rows) == 3
    assert rows[0].amount == Decimal("-42.18")
    assert rows[0].suggested_kind == TransactionKind.uncategorized
    assert rows[1].suggested_kind == TransactionKind.cc_payment
    assert rows[2].suggested_kind == TransactionKind.refund


def test_amex_importer_flips_charges_positive_exports():
    rows = AmexImporter.parse(
        "\n".join(
            [
                "Date,Description,Amount",
                "06/01/2026,ONLINE SOFTWARE SUBSCRIPTION,19.00",
                "06/09/2026,PAYMENT RECEIVED,-33.25",
            ]
        )
    )

    assert [r.amount for r in rows] == [Decimal("-19.00"), Decimal("33.25")]
    assert rows[1].suggested_kind == TransactionKind.cc_payment


def test_amex_xlsx_importer_reads_transaction_details_sheet():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Transaction Details"
    ws.append(["Downloaded", "Example"])
    ws.append(["Date", "Description", "Amount"])
    ws.append(["06/01/2026", "ONLINE SOFTWARE SUBSCRIPTION", 19.00])
    ws.append(["06/09/2026", "PAYMENT RECEIVED", -33.25])
    buf = BytesIO()
    wb.save(buf)

    rows = parse_amex_xlsx_bytes(buf.getvalue())

    assert [r.amount for r in rows] == [Decimal("-19.00"), Decimal("33.25")]
    assert rows[1].suggested_kind == TransactionKind.cc_payment


def test_wells_fargo_importer_marks_common_local_kinds():
    rows = WellsFargoCheckingImporter.parse(
        "\n".join(
            [
                "DATE,DESCRIPTION,AMOUNT,CHECK #,STATUS",
                "06/01/2026,ACME PAYROLL,2500.00,,POSTED",
                "06/05/2026,CHASE CREDIT CRD AUTOPAY,-250.00,,POSTED",
                "06/08/2026,NEIGHBORHOOD COFFEE,-6.75,,POSTED",
            ]
        )
    )

    assert [r.suggested_kind for r in rows] == [
        TransactionKind.income,
        TransactionKind.cc_payment,
        TransactionKind.uncategorized,
    ]


def test_contribution_history_importer_skips_metadata_rows():
    rows = ContributionHistoryImporter.parse(
        "\n".join(
            [
                "CONTRIBUTION HISTORY",
                "",
                "Account,Example Fund",
                "",
                'Status,Description,Symbol,"Estimated Amount","Received Date","Contribution ID"',
                'Complete,"INDEX ETF",ETF,"$123.45","2026-05-26T00:00:00-04:00",abc-1',
            ]
        )
    )

    assert len(rows) == 1
    assert rows[0].date.isoformat() == "2026-05-26"
    assert rows[0].amount == Decimal("-123.45")
    assert rows[0].suggested_kind == TransactionKind.donation


def test_net_worth_workbook_import_uses_user_supplied_account_map(tmp_path):
    engine = create_engine("sqlite:///:memory:", future=True)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, future=True)
    db = Session()
    db.add(
        Account(
            name="Checking",
            account_category=AccountCategory.bank,
            type=AccountType.checking,
            sign_convention=SignConvention.outflow_negative,
        )
    )
    db.commit()

    wb = openpyxl.Workbook()
    dates = wb.active
    dates.title = "Dates"
    dates.cell(1, 2).value = date(2026, 6, 1)
    assets = wb.create_sheet("Assets")
    assets.cell(2, 1).value = "Bank row from arbitrary workbook"
    assets.cell(2, 2).value = 1234.56
    workbook_path = tmp_path / "net-worth.xlsx"
    wb.save(workbook_path)

    result = snapshots.import_workbook(
        schemas.NetWorthWorkbookImportRequest(
            path=str(workbook_path),
            account_map={"Assets!2": "Checking"},
        ),
        db,
    )

    assert result.imported == 1
    snap = db.scalar(select(NetWorthSnapshot))
    assert snap is not None
    assert snap.snapshot_date == date(2026, 6, 1)
    assert snap.balances[0].balance == Decimal("1234.56")
