"""Fidelity Charitable contribution-history CSV exports.

The export starts with a short metadata preamble, then a normal CSV header
beginning with `Status,Description,...`. Completed contributions are modeled
as donation outflows from the linked investment account.
"""
from __future__ import annotations

import csv
import re
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from io import StringIO

from dateutil import parser as dateparser

from ..models import TransactionKind
from ..schemas import ImportDraftRow
from .base import normalize_description


def _money(value: str | None) -> Decimal | None:
    if value is None:
        return None
    cleaned = re.sub(r"[^0-9().-]", "", str(value))
    if not cleaned:
        return None
    if cleaned.startswith("(") and cleaned.endswith(")"):
        cleaned = "-" + cleaned[1:-1]
    try:
        return Decimal(cleaned).quantize(Decimal("0.01"))
    except InvalidOperation:
        return None


def _date(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return dateparser.parse(str(value)).date()
    except (ValueError, OverflowError):
        return None


def parse_contribution_history_csv(csv_text: str) -> list[ImportDraftRow]:
    lines = csv_text.lstrip("\ufeff").splitlines()
    header_idx = next(
        (i for i, line in enumerate(lines) if line.startswith("Status,Description,")),
        None,
    )
    if header_idx is None:
        return []

    rows = csv.DictReader(StringIO("\n".join(lines[header_idx:])))
    out: list[ImportDraftRow] = []
    for i, row in enumerate(rows):
        if (row.get("Status") or "").strip().lower() != "complete":
            continue
        amount = _money(row.get("Net Proceeds")) or _money(row.get("Estimated Amount"))
        d = _date(row.get("Received Date")) or _date(row.get("Submitted Date"))
        if amount is None or d is None:
            continue
        security = normalize_description(row.get("Description") or "Fidelity Charitable contribution")
        contribution_id = normalize_description(row.get("Contribution ID") or "")
        desc = f"Fidelity Charitable contribution - {security}"
        if contribution_id:
            desc = f"{desc} ({contribution_id})"
        out.append(
            ImportDraftRow(
                row_index=i,
                date=d,
                post_date=None,
                description_raw=desc,
                description_normalized=desc,
                merchant="Fidelity Charitable",
                amount=-amount,
                suggested_kind=TransactionKind.donation,
                raw=dict(row),
            )
        )
    return out
