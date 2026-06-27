"""Chase credit card CSV.

Header: Transaction Date,Post Date,Description,Category,Type,Amount,Memo
Amounts are outflow-negative already.
"""
from __future__ import annotations

from decimal import Decimal

from ..models import TransactionKind
from ..schemas import ImportDraftRow
from .base import CsvImporter, _read_dictrows, guess_merchant, normalize_description, register


@register
class ChaseCreditImporter(CsvImporter):
    name = "chase_credit"
    label = "Chase Credit Card"

    REQUIRED = {"Transaction Date", "Post Date", "Description", "Type", "Amount"}

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return cls.REQUIRED.issubset(set(header))

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, r in enumerate(rows):
            d = cls._parse_date(r.get("Transaction Date", ""))
            if not d:
                continue
            amt = cls._parse_amount(r.get("Amount", ""))
            if amt is None:
                continue
            desc = normalize_description(r.get("Description", ""))
            tx_type = (r.get("Type") or "").strip().lower()
            # Chase signs: charges are negative, payments positive.
            kind = TransactionKind.uncategorized
            if tx_type == "payment":
                kind = TransactionKind.cc_payment
            elif tx_type == "return":
                kind = TransactionKind.refund
            out.append(
                ImportDraftRow(
                    row_index=i,
                    date=d,
                    post_date=cls._parse_date(r.get("Post Date", "")),
                    description_raw=r.get("Description", ""),
                    description_normalized=desc,
                    merchant=guess_merchant(desc),
                    amount=Decimal(amt),
                    suggested_kind=kind,
                    raw=dict(r),
                )
            )
        return out
