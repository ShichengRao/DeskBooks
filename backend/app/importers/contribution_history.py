from __future__ import annotations

import csv
from decimal import Decimal, InvalidOperation
from io import StringIO

from dateutil import parser as dateparser

from ..models import TransactionKind
from .base import CsvImporter, draft_row, normalize_description, register


@register
class ContributionHistoryImporter(CsvImporter):
    name = "contribution_history"
    label = "Contribution History"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return "Contribution ID" in header and "Estimated Amount" in header

    @classmethod
    def parse(cls, csv_text: str):
        reader = csv.reader(StringIO(csv_text.lstrip("\ufeff")))
        rows = list(reader)
        header_idx = next(
            (
                i
                for i, row in enumerate(rows)
                if "Contribution ID" in row and "Estimated Amount" in row
            ),
            None,
        )
        if header_idx is None:
            return []
        header = [h.strip() for h in rows[header_idx]]
        out = []
        for idx, row in enumerate(rows[header_idx + 1 :]):
            if not any(c.strip() for c in row):
                continue
            record = {header[i]: row[i] if i < len(row) else "" for i in range(len(header))}
            d = _parse_date(record.get("Received Date") or record.get("Submitted Date"))
            amount = _parse_money(record.get("Estimated Amount") or record.get("Net Proceeds"))
            if d is None or amount is None:
                continue
            desc = normalize_description(record.get("Description") or "Contribution")
            symbol = normalize_description(record.get("Symbol") or "")
            raw_desc = f"{symbol} {desc}".strip()
            out.append(
                draft_row(
                    row_index=idx,
                    date=d,
                    description_raw=raw_desc,
                    description_normalized=raw_desc,
                    merchant="Contribution",
                    amount=-amount,
                    suggested_kind=TransactionKind.donation,
                    raw=record,
                )
            )
        return out


def _parse_date(value: str | None):
    if not value:
        return None
    try:
        return dateparser.parse(value).date()
    except (ValueError, OverflowError):
        return None


def _parse_money(value: str | None) -> Decimal | None:
    if not value:
        return None
    cleaned = value.strip().replace("$", "").replace(",", "")
    try:
        return Decimal(cleaned).quantize(Decimal("0.01"))
    except InvalidOperation:
        return None
