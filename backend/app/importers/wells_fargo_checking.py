"""Wells Fargo checking CSV.

Header: "DATE","DESCRIPTION","AMOUNT","CHECK #","STATUS"
Amounts are outflow-negative.
"""
from __future__ import annotations

from ..models import TransactionKind
from ..schemas import ImportDraftRow
from .base import CsvImporter, _read_dictrows, guess_merchant, normalize_description, register


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
            # tolerate different header casings
            getv = lambda k: next((v for kk, v in r.items() if kk.upper() == k), "")
            d = cls._parse_date(getv("DATE"))
            if not d:
                continue
            amt = cls._parse_amount(getv("AMOUNT"))
            if amt is None:
                continue
            desc = normalize_description(getv("DESCRIPTION"))
            # heuristics: payroll → income; CC EPAY → cc_payment; tax / IRS → tax
            upper = desc.upper()
            kind = TransactionKind.uncategorized
            if "PAYROLL" in upper:
                kind = TransactionKind.income
            elif "CHASE CREDIT CRD" in upper or "AMEX EPAYMENT" in upper:
                kind = TransactionKind.cc_payment
            elif "IRS" in upper and "USATAXPYMT" in upper:
                kind = TransactionKind.tax
            elif "NYSTTAXRFD" in upper or "TAX REFUND" in upper:
                kind = TransactionKind.income  # tax refund treated as inflow
            elif "ZELLE" in upper or "VENMO" in upper or "PAYPAL" in upper:
                kind = TransactionKind.uncategorized
            elif "FID BKG SVC" in upper or "GOLDMAN SACHS BA" in upper or "JPMORGAN CHASE   EXT TRNSFR" in upper or "MSPBNA" in upper:
                kind = TransactionKind.transfer

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
