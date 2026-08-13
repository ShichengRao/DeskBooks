"""Importer for connector-staged transactions (deskbooks.staged-transactions/v1).

API connectors in automation/ normalize provider JSON into this format so
one importer covers every connector. Not part of the CSV sniff registry —
the automation manifest names it explicitly (importer_name "staged_json"),
and .json uploads route here by extension.

Rows marked pending are skipped: pending transactions can change date and
amount when they post, which would defeat duplicate detection.
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal, InvalidOperation

from ..models import TransactionKind
from ..schemas import ImportDraftRow
from .base import draft_row, normalize_description

STAGED_TRANSACTIONS_FORMAT = "deskbooks.staged-transactions/v1"


def parse_staged_transactions_bytes(data: bytes) -> list[ImportDraftRow]:
    try:
        payload = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid staged transactions JSON: {exc}") from exc
    if not isinstance(payload, dict) or payload.get("format") != STAGED_TRANSACTIONS_FORMAT:
        raise ValueError(f"not a {STAGED_TRANSACTIONS_FORMAT} file")

    transactions = payload.get("transactions")
    if not isinstance(transactions, list):
        raise ValueError("staged transactions file has no transactions list")

    rows: list[ImportDraftRow] = []
    for index, txn in enumerate(transactions):
        if not isinstance(txn, dict):
            raise ValueError(f"transactions[{index}] is not an object")
        if txn.get("pending"):
            continue
        try:
            txn_date = date.fromisoformat(str(txn["date"]))
            amount = Decimal(str(txn["amount"]))
        except KeyError as exc:
            raise ValueError(f"transactions[{index}] missing field: {exc.args[0]}") from exc
        except (ValueError, InvalidOperation) as exc:
            raise ValueError(f"transactions[{index}] has invalid date/amount: {exc}") from exc
        post_date_raw = txn.get("post_date")
        try:
            post_date = date.fromisoformat(str(post_date_raw)) if post_date_raw else None
        except ValueError as exc:
            raise ValueError(f"transactions[{index}] has invalid post_date: {exc}") from exc

        description = str(txn.get("description") or "")
        rows.append(
            draft_row(
                row_index=index,
                date=txn_date,
                post_date=post_date,
                description_raw=description,
                description_normalized=normalize_description(description),
                merchant=txn.get("merchant") or None,
                amount=amount,
                suggested_kind=TransactionKind.uncategorized,
                # Keeps the provider transaction id available for future
                # provider-id-based dedupe without a schema change.
                raw=dict(txn),
            )
        )
    return rows
