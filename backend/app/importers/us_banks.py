"""CSV importers for common US bank and credit-card exports.

The large banks do not all publish stable CSV schemas, but their consumer
exports tend to fall into a few recurring shapes:

* signed Amount bank activity with a running balance column
* bank activity split into Debit and Credit columns
* card activity split into Debit and Credit columns
* card activity where purchases are positive and payments are negative
"""
from __future__ import annotations

from decimal import Decimal

from ..models import TransactionKind
from ..schemas import ImportDraftRow
from .base import CsvImporter, _read_dictrows, draft_row, normalize_description, register, row_value


def _header_set(header: list[str]) -> set[str]:
    return {h.strip().upper() for h in header}


def _has_all(header: list[str], *names: str) -> bool:
    hs = _header_set(header)
    return all(name.upper() in hs for name in names)


def _has_any(header: list[str], *names: str) -> bool:
    hs = _header_set(header)
    return any(name.upper() in hs for name in names)


def _clean_amount(amount: Decimal) -> Decimal:
    return amount.quantize(Decimal("0.01"))


def _signed_amount(cls: type[CsvImporter], row: dict, key: str) -> Decimal | None:
    amount = cls._parse_amount(row_value(row, key))
    return _clean_amount(amount) if amount is not None else None


def _debit_credit_amount(
    cls: type[CsvImporter],
    row: dict,
    *,
    debit_key: str = "DEBIT",
    credit_key: str = "CREDIT",
) -> Decimal | None:
    debit = cls._parse_amount(row_value(row, debit_key))
    credit = cls._parse_amount(row_value(row, credit_key))
    if debit is not None and debit != 0:
        return -abs(_clean_amount(debit))
    if credit is not None and credit != 0:
        return abs(_clean_amount(credit))
    return Decimal("0.00")


def _suggest_kind(
    description: str,
    amount: Decimal,
    *,
    credit_card: bool = False,
    extra: str = "",
) -> TransactionKind:
    haystack = f"{description} {extra}".upper()
    if "REFUND" in haystack or "RETURN" in haystack or "REVERSAL" in haystack:
        return TransactionKind.refund if amount > 0 else TransactionKind.uncategorized
    if "PAYMENT" in haystack and (credit_card or "CREDIT CARD" in haystack or "CRD" in haystack):
        return TransactionKind.cc_payment
    if "AUTOPAY" in haystack and credit_card:
        return TransactionKind.cc_payment
    if "DIRECT DEP" in haystack or "DIRECTDEP" in haystack or "PAYROLL" in haystack:
        return TransactionKind.income
    if "INTEREST" in haystack and amount > 0:
        return TransactionKind.income
    if "TAX REFUND" in haystack or "TREAS 310 TAX REF" in haystack:
        return TransactionKind.income
    if "IRS" in haystack or "USATAXPYMT" in haystack:
        return TransactionKind.tax
    if "TRANSFER" in haystack or "XFER" in haystack or "EXT TRNSFR" in haystack:
        return TransactionKind.transfer
    return TransactionKind.uncategorized


def _draft(
    *,
    cls: type[CsvImporter],
    row_index: int,
    row: dict,
    date_key: str,
    amount: Decimal | None,
    description: str,
    post_date_key: str | None = None,
    credit_card: bool = False,
    extra: str = "",
) -> ImportDraftRow | None:
    d = cls._parse_date(row_value(row, date_key))
    if not d or amount is None:
        return None
    desc = normalize_description(description)
    return draft_row(
        row_index=row_index,
        date=d,
        post_date=cls._parse_date(row_value(row, post_date_key)) if post_date_key else None,
        description_raw=description,
        description_normalized=desc,
        amount=amount,
        suggested_kind=_suggest_kind(desc, amount, credit_card=credit_card, extra=extra),
        raw=row,
    )


@register
class ChaseBankImporter(CsvImporter):
    """Chase checking/savings CSV.

    Header: Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #
    Amounts are outflow-negative.
    """

    name = "chase_bank"
    label = "Chase Bank Account"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return _has_all(header, "DETAILS", "POSTING DATE", "DESCRIPTION", "AMOUNT", "TYPE")

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _signed_amount(cls, row, "AMOUNT")
            raw_desc = row_value(row, "DESCRIPTION")
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="POSTING DATE",
                amount=amount,
                description=raw_desc,
                extra=f"{row_value(row, 'DETAILS')} {row_value(row, 'TYPE')}",
            )
            if item:
                out.append(item)
        return out


@register
class RunningBalanceBankImporter(CsvImporter):
    """Bank of America, Truist, BMO, and similar signed-amount bank CSVs.

    Header shape: Date,Description,Amount,Running Bal. / Running Balance
    Amounts are outflow-negative.
    """

    name = "running_balance_bank"
    label = "Bank CSV (Date / Description / Amount / Balance)"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        if _has_any(header, "ACTIVITY", "TRANSACTION", "DEBIT", "CREDIT", "WITHDRAWALS", "DEPOSITS"):
            return False
        return _has_all(header, "DATE", "DESCRIPTION", "AMOUNT") and _has_any(
            header, "RUNNING BAL.", "RUNNING BALANCE", "BALANCE"
        )

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _signed_amount(cls, row, "AMOUNT")
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="DATE",
                amount=amount,
                description=row_value(row, "DESCRIPTION"),
            )
            if item:
                out.append(item)
        return out


@register
class PncBankImporter(CsvImporter):
    """PNC bank CSV.

    Header: Date,Description,Withdrawals,Deposits,Balance
    Withdrawals become negative; deposits become positive.
    """

    name = "pnc_bank"
    label = "PNC Bank Account"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return _has_all(header, "DATE", "DESCRIPTION", "WITHDRAWALS", "DEPOSITS", "BALANCE")

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _debit_credit_amount(
                cls, row, debit_key="WITHDRAWALS", credit_key="DEPOSITS"
            )
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="DATE",
                amount=amount,
                description=row_value(row, "DESCRIPTION"),
            )
            if item:
                out.append(item)
        return out


@register
class DebitCreditBankImporter(CsvImporter):
    """TD, BMO, custodial, and similar bank CSVs with Debit/Credit columns.

    Header shape: Date,Description,Debit,Credit[,Balance]
    Debits become negative; credits become positive.
    """

    name = "debit_credit_bank"
    label = "Bank CSV (Debit / Credit)"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        if _has_any(header, "STATUS", "CARD NO."):
            return False
        return _has_all(header, "DATE", "DESCRIPTION", "DEBIT", "CREDIT")

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _debit_credit_amount(cls, row)
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="DATE",
                amount=amount,
                description=row_value(row, "DESCRIPTION"),
            )
            if item:
                out.append(item)
        return out


@register
class UsBankImporter(CsvImporter):
    """U.S. Bank CSV.

    Header shape: Date,Transaction,Name,Memo,Amount
    Amounts are outflow-negative.
    """

    name = "us_bank"
    label = "U.S. Bank Account"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return _has_all(header, "DATE", "TRANSACTION", "NAME", "MEMO", "AMOUNT")

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _signed_amount(cls, row, "AMOUNT")
            description = " ".join(
                part
                for part in [
                    row_value(row, "NAME"),
                    row_value(row, "TRANSACTION"),
                    row_value(row, "MEMO"),
                ]
                if part
            )
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="DATE",
                amount=amount,
                description=description,
                extra=row_value(row, "TRANSACTION"),
            )
            if item:
                out.append(item)
        return out


@register
class MarcusMorganStanleyBankImporter(CsvImporter):
    """Goldman Sachs Marcus and Morgan Stanley Private Bank style CSVs.

    Header shape: Date,Activity,Description,Amount,Balance
    Amounts are outflow-negative.
    """

    name = "activity_bank"
    label = "Activity Bank CSV (Marcus / Morgan Stanley)"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return _has_all(header, "DATE", "ACTIVITY", "DESCRIPTION", "AMOUNT", "BALANCE")

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _signed_amount(cls, row, "AMOUNT")
            description = " ".join(
                part
                for part in [row_value(row, "ACTIVITY"), row_value(row, "DESCRIPTION")]
                if part
            )
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="DATE",
                amount=amount,
                description=description,
                extra=row_value(row, "ACTIVITY"),
            )
            if item:
                out.append(item)
        return out


@register
class CapitalOneCreditImporter(CsvImporter):
    """Capital One credit card CSV.

    Header: Transaction Date,Posted Date,Card No.,Description,Category,Debit,Credit
    Debits are purchases and become negative; credits become positive.
    """

    name = "capital_one_credit"
    label = "Capital One Credit Card"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return _has_all(
            header,
            "TRANSACTION DATE",
            "POSTED DATE",
            "CARD NO.",
            "DESCRIPTION",
            "CATEGORY",
            "DEBIT",
            "CREDIT",
        )

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _debit_credit_amount(cls, row)
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="TRANSACTION DATE",
                post_date_key="POSTED DATE",
                amount=amount,
                description=row_value(row, "DESCRIPTION"),
                credit_card=True,
                extra=row_value(row, "CATEGORY"),
            )
            if item:
                out.append(item)
        return out


@register
class CitiCreditImporter(CsvImporter):
    """Citi credit card CSV.

    Header: Status,Date,Description,Debit,Credit
    Debits are purchases and become negative; credits become positive.
    """

    name = "citi_credit"
    label = "Citi Credit Card"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return _has_all(header, "STATUS", "DATE", "DESCRIPTION", "DEBIT", "CREDIT")

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _debit_credit_amount(cls, row)
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="DATE",
                amount=amount,
                description=row_value(row, "DESCRIPTION"),
                credit_card=True,
                extra=row_value(row, "STATUS"),
            )
            if item:
                out.append(item)
        return out


@register
class DiscoverCreditImporter(CsvImporter):
    """Discover credit card CSV.

    Header: Trans. Date,Post Date,Description,Amount,Category
    Purchases are positive in the source and are inverted to outflow-negative.
    """

    name = "discover_credit"
    label = "Discover Credit Card"

    @classmethod
    def can_handle(cls, header: list[str]) -> bool:
        return _has_all(header, "TRANS. DATE", "POST DATE", "DESCRIPTION", "AMOUNT", "CATEGORY")

    @classmethod
    def parse(cls, csv_text: str) -> list[ImportDraftRow]:
        _, rows = _read_dictrows(csv_text)
        out: list[ImportDraftRow] = []
        for i, row in enumerate(rows):
            amount = _signed_amount(cls, row, "AMOUNT")
            if amount is not None:
                amount = -amount
            item = _draft(
                cls=cls,
                row_index=i,
                row=row,
                date_key="TRANS. DATE",
                post_date_key="POST DATE",
                amount=amount,
                description=row_value(row, "DESCRIPTION"),
                credit_card=True,
                extra=row_value(row, "CATEGORY"),
            )
            if item:
                out.append(item)
        return out
