"""Wells Fargo checking CSV.

Header: "DATE","DESCRIPTION","AMOUNT","CHECK #","STATUS"
Amounts are outflow-negative.
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
class WellsFargoCheckingImporter(CsvImporter):
    name = "wells_fargo_checking"
    label = "Wells Fargo Checking"

    # WF checking exports include STATUS (and usually CHECK #). Requiring STATUS
    # disambiguates from a bare 3-column Amex export.
    REQUIRED = {"DATE", "DESCRIPTION", "AMOUNT", "STATUS"}

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return cls.REQUIRED.issubset({h.upper() for h in header})

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
            raw_desc = row_value(r, "DESCRIPTION")
            desc = normalize_description(raw_desc)
            # heuristics: payroll → income; CC EPAY → cc_payment; tax / IRS → tax
            upper = desc.upper()
            kind = TransactionKind.uncategorized
            merchant = None
            if "PAYROLL" in upper:
                kind = TransactionKind.income
                merchant = "Salary"
            elif "CHASE CREDIT CRD" in upper or "AMEX EPAYMENT" in upper:
                kind = TransactionKind.cc_payment
            elif "IRS" in upper and "USATAXPYMT" in upper:
                kind = TransactionKind.tax
            elif "NYSTTAXRFD" in upper or "TAX REFUND" in upper:
                kind = TransactionKind.income  # tax refund treated as inflow
                merchant = "Tax Refund"
            elif "ZELLE" in upper or "VENMO" in upper or "PAYPAL" in upper:
                kind = TransactionKind.uncategorized
            elif "FID BKG SVC" in upper or "GOLDMAN SACHS BA" in upper or "JPMORGAN CHASE   EXT TRNSFR" in upper or "MSPBNA" in upper:
                kind = TransactionKind.transfer

            out.append(
                draft_row(
                    row_index=i,
                    date=d,
                    description_raw=raw_desc,
                    description_normalized=desc,
                    merchant=merchant,
                    amount=amt,
                    suggested_kind=kind,
                    raw=r,
                )
            )
        return out
