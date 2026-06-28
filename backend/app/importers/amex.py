"""Amex (or any 3-column Date/Description/Amount) CSV.

Header: Date,Description,Amount

Convention note: Amex web exports list **charges as positive**. We invert
to outflow-negative so the database is consistent regardless of source.
"""
from __future__ import annotations

from ..models import TransactionKind
from ..schemas import ImportDraftRow
from .base import (
    CsvImporter,
    _read_dictrows,
    draft_row,
    normalize_description,
    register,
    row_value,
)


@register
class AmexImporter(CsvImporter):
    name = "amex"
    label = "Amex"

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
            d = cls._parse_date(row_value(r, "DATE"))
            if not d:
                continue
            amt = cls._parse_amount(row_value(r, "AMOUNT"))
            if amt is None:
                continue
            # invert: Amex charges-positive -> outflow-negative
            amt = -amt
            raw_desc = row_value(r, "DESCRIPTION")
            desc = normalize_description(raw_desc)
            kind = TransactionKind.uncategorized
            if (
                "AUTOPAY PAYMENT" in desc.upper()
                or "PAYMENT - THANK YOU" in desc.upper()
                or "PAYMENT RECEIVED" in desc.upper()
            ):
                kind = TransactionKind.cc_payment
            out.append(
                draft_row(
                    row_index=i,
                    date=d,
                    description_raw=raw_desc,
                    description_normalized=desc,
                    amount=amt,
                    suggested_kind=kind,
                    raw=r,
                )
            )
        return out
