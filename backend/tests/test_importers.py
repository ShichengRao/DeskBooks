from __future__ import annotations

from decimal import Decimal

from app.importers.amex import AmexImporter
from app.importers.chase_credit import ChaseCreditImporter
from app.importers.wells_fargo_checking import WellsFargoCheckingImporter
from app.models import TransactionKind


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
