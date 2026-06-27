"""Amex (or any 3-column Date/Description/Amount) CSV.

Header: Date,Description,Amount

Convention note: Amex web exports list **charges as positive**. We invert
to outflow-negative so the database is consistent regardless of source.
"""
from __future__ import annotations

from decimal import Decimal

from ..models import TransactionKind
from ..schemas import ImportDraftRow
from .base import CsvImporter, _read_dictrows, guess_merchant, normalize_description, register


@register
class AmexImporter(CsvImporter):
    name = "amex"
    label = "Amex (charges-positive)"

    REQUIRED = {"DATE", "DESCRIPTION", "AMOUNT"}

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        hs = {h.upper() for h in header}
        # 3-col exact match, distinguishes from WF (which has more cols)
        return hs == cls.REQUIRED

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, r in enumerate(rows):
            getv = lambda k: next((v for kk, v in r.items() if kk.upper() == k), "")
            d = cls._parse_date(getv("DATE"))
            if not d:
                continue
            amt = cls._parse_amount(getv("AMOUNT"))
            if amt is None:
                continue
            # invert: Amex charges-positive -> outflow-negative
            amt = -amt
            desc = normalize_description(getv("DESCRIPTION"))
            kind = TransactionKind.uncategorized
            if (
                "AUTOPAY PAYMENT" in desc.upper()
                or "PAYMENT - THANK YOU" in desc.upper()
                or "PAYMENT RECEIVED" in desc.upper()
            ):
                kind = TransactionKind.cc_payment
            out.append(
                ImportDraftRow(
                    row_index=i,
                    date=d,
                    post_date=None,
                    description_raw=getv("DESCRIPTION"),
                    description_normalized=desc,
                    merchant=guess_merchant(desc),
                    amount=amt,
                    suggested_kind=kind,
                    raw=dict(r),
                )
            )
        return out
