"""CSV importer infrastructure.

An importer:
  1. Decides whether it can_handle a given header row.
  2. Parses rows into normalized ImportDraftRow records (outflow-negative).
  3. Optionally suggests a kind hint per row (e.g. CC "Payment" = cc_payment).

Account selection is left to the caller — multiple Chase cards can share
the same importer.
"""
from __future__ import annotations

import csv
import io
import re
from abc import ABC, abstractmethod
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import ClassVar

from dateutil import parser as dateparser

from ..models import SignConvention, TransactionKind
from ..schemas import ImportDraftRow


class CsvImporter(ABC):
    name: ClassVar[str]
    label: ClassVar[str]
    default_sign_convention: ClassVar[SignConvention] = SignConvention.outflow_negative

    @classmethod
    @abstractmethod
    def can_handle(cls, header: list[str]) -> bool: ...

    @classmethod
    @abstractmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]: ...

    @staticmethod
    def _parse_date(s: str) -> date | None:
        if not s:
            return None
        s = s.strip().strip('"')
        if not s:
            return None
        try:
            return dateparser.parse(s, dayfirst=False).date()
        except (ValueError, OverflowError):
            return None

    @staticmethod
    def _parse_amount(s: str) -> Decimal | None:
        if s is None:
            return None
        s = str(s).strip().strip('"').replace("$", "").replace(",", "")
        if not s:
            return None
        # Some CSVs use parentheses for negatives.
        if s.startswith("(") and s.endswith(")"):
            s = "-" + s[1:-1]
        try:
            return Decimal(s)
        except InvalidOperation:
            return None


_WS_RE = re.compile(r"\s+")


def normalize_description(raw: str) -> str:
    if not raw:
        return ""
    return _WS_RE.sub(" ", raw).strip()


def guess_merchant(raw: str) -> str:
    """Very light merchant extraction. Better-than-nothing for sniffed display.

    We strip common prefixes (DD *, TST*, SQ *), trailing dates/refs, and
    collapse whitespace. The rule engine is the long-term mapping layer; this
    is just for the import preview UI.
    """
    s = normalize_description(raw)
    # Strip leading prefixes used by aggregators / payment processors.
    s = re.sub(r"^(DD \*|TST\*|SQ \*|SP \*|PY \*|PAYPAL \*|VENMO \*?)", "", s, flags=re.I)
    # Strip trailing transaction numbers / refs / state codes.
    s = re.sub(r"\s+[A-Z]{2}\s*$", "", s)
    s = re.sub(r"\s+\d{6,}\s*$", "", s)
    s = re.sub(r"\s+#\d+", "", s)
    return s.strip().title() if s else s


def _read_dictrows(csv_text: str) -> tuple[list[str], list[dict]]:
    f = io.StringIO(csv_text.lstrip("﻿"))
    reader = csv.reader(f)
    rows = list(reader)
    if not rows:
        return [], []
    header = [h.strip().strip('"') for h in rows[0]]
    dict_rows = []
    for r in rows[1:]:
        if not any(c.strip() for c in r):
            continue
        d = {header[i] if i < len(header) else f"col{i}": (r[i] if i < len(r) else "") for i in range(len(r))}
        dict_rows.append(d)
    return header, dict_rows


# --- registry helpers ---

_REGISTRY: list[type[CsvImporter]] = []


def register(cls: type[CsvImporter]) -> type[CsvImporter]:
    _REGISTRY.append(cls)
    return cls


def all_importers() -> list[type[CsvImporter]]:
    return list(_REGISTRY)


def sniff(csv_text: str) -> list[type[CsvImporter]]:
    header, _ = _read_dictrows(csv_text)
    matches = [imp for imp in _REGISTRY if imp.can_handle(header)]
    return matches


def get_by_name(name: str) -> type[CsvImporter] | None:
    for imp in _REGISTRY:
        if imp.name == name:
            return imp
    return None


__all__ = [
    "CsvImporter",
    "ImportDraftRow",
    "normalize_description",
    "guess_merchant",
    "TransactionKind",
    "register",
    "all_importers",
    "sniff",
    "get_by_name",
    "_read_dictrows",
]
