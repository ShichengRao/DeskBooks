package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.CsvDrafts.debitCredit;
import static com.deskbooks.backend.imports.CsvDrafts.draft;
import static com.deskbooks.backend.imports.CsvColumns.ACTIVITY;
import static com.deskbooks.backend.imports.CsvColumns.AMOUNT;
import static com.deskbooks.backend.imports.CsvColumns.BALANCE;
import static com.deskbooks.backend.imports.CsvColumns.CREDIT;
import static com.deskbooks.backend.imports.CsvColumns.DATE;
import static com.deskbooks.backend.imports.CsvColumns.DEBIT;
import static com.deskbooks.backend.imports.CsvColumns.DESCRIPTION;
import static com.deskbooks.backend.imports.CsvColumns.MEMO;
import static com.deskbooks.backend.imports.CsvColumns.NAME;
import static com.deskbooks.backend.imports.CsvColumns.STATUS;
import static com.deskbooks.backend.imports.CsvColumns.TRANSACTION;
import static com.deskbooks.backend.imports.CsvRows.hasAll;
import static com.deskbooks.backend.imports.CsvRows.hasAny;
import static com.deskbooks.backend.imports.CsvRows.value;
import static com.deskbooks.backend.imports.ImportParsing.normalize;
import static com.deskbooks.backend.imports.ImportParsing.parseAmount;
import static com.deskbooks.backend.imports.ImportParsing.parseDate;

import com.deskbooks.backend.imports.ImportController.ImportDraftRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WellsFargoCheckingImporter extends DictCsvImporter {
    @Override
    public String name() {
        return "wells_fargo_checking";
    }

    @Override
    public String label() {
        return "Wells Fargo Checking";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, DATE, DESCRIPTION, AMOUNT, STATUS);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        LocalDate date = parseDate(value(row, DATE));
        BigDecimal amount = parseAmount(value(row, AMOUNT));
        if (date == null || amount == null) {
            return null;
        }
        String raw = value(row, DESCRIPTION);
        WellsFargoSuggestion suggestion = suggestWellsFargoKind(normalize(raw).toUpperCase(Locale.ROOT));
        return draft(index, date, null, raw, amount, suggestion.kind(), suggestion.merchant(), row);
    }

    private WellsFargoSuggestion suggestWellsFargoKind(String upper) {
        if (upper.contains("PAYROLL")) {
            return new WellsFargoSuggestion("income", "Salary");
        }
        if (upper.contains("CHASE CREDIT CRD") || upper.contains("AMEX EPAYMENT")) {
            return new WellsFargoSuggestion("cc_payment", null);
        }
        if (upper.contains("IRS") && upper.contains("USATAXPYMT")) {
            return new WellsFargoSuggestion("tax", null);
        }
        if (upper.contains("NYSTTAXRFD") || upper.contains("TAX REFUND")) {
            return new WellsFargoSuggestion("income", "Tax Refund");
        }
        if (isTransferDescription(upper)) {
            return new WellsFargoSuggestion("transfer", null);
        }
        return new WellsFargoSuggestion("uncategorized", null);
    }

    private boolean isTransferDescription(String upper) {
        return upper.contains("FID BKG SVC")
                || upper.contains("GOLDMAN SACHS BA")
                || upper.contains("JPMORGAN CHASE   EXT TRNSFR")
                || upper.contains("MSPBNA");
    }
}

record WellsFargoSuggestion(String kind, String merchant) {
}

final class ChaseBankImporter extends BankImporter {
    @Override
    public String name() {
        return "chase_bank";
    }

    @Override
    public String label() {
        return "Chase Bank Account";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, "DETAILS", "POSTING DATE", "DESCRIPTION", "AMOUNT", "TYPE");
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        return bankDraft(
                index,
                row,
                "POSTING DATE",
                null,
                parseAmount(value(row, AMOUNT)),
                value(row, DESCRIPTION),
                value(row, "DETAILS") + " " + value(row, "TYPE"),
                false);
    }
}

final class RunningBalanceBankImporter extends BankImporter {
    @Override
    public String name() {
        return "running_balance_bank";
    }

    @Override
    public String label() {
        return "Bank CSV (Date / Description / Amount / Balance)";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        if (hasAny(header, ACTIVITY, TRANSACTION, DEBIT, CREDIT, "WITHDRAWALS", "DEPOSITS")) {
            return false;
        }
        return hasAll(header, DATE, DESCRIPTION, AMOUNT)
                && hasAny(header, "RUNNING BAL.", "RUNNING BALANCE", BALANCE);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        return bankDraft(index, row, DATE, null, parseAmount(value(row, AMOUNT)), value(row, DESCRIPTION), "", false);
    }
}

final class PncBankImporter extends BankImporter {
    @Override
    public String name() {
        return "pnc_bank";
    }

    @Override
    public String label() {
        return "PNC Bank Account";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, DATE, DESCRIPTION, "WITHDRAWALS", "DEPOSITS", BALANCE);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        return bankDraft(
                index,
                row,
                DATE,
                null,
                debitCredit(row, "WITHDRAWALS", "DEPOSITS"),
                value(row, DESCRIPTION),
                "",
                false);
    }
}

final class DebitCreditBankImporter extends BankImporter {
    @Override
    public String name() {
        return "debit_credit_bank";
    }

    @Override
    public String label() {
        return "Bank CSV (Debit / Credit)";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return !hasAny(header, STATUS, "CARD NO.") && hasAll(header, DATE, DESCRIPTION, DEBIT, CREDIT);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        return bankDraft(index, row, DATE, null, debitCredit(row, DEBIT, CREDIT), value(row, DESCRIPTION), "", false);
    }
}

final class UsBankImporter extends BankImporter {
    @Override
    public String name() {
        return "us_bank";
    }

    @Override
    public String label() {
        return "U.S. Bank Account";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, DATE, TRANSACTION, NAME, MEMO, AMOUNT);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        String description = String.join(" ", List.of(value(row, NAME), value(row, TRANSACTION), value(row, MEMO)))
                .replaceAll("\\s+", " ")
                .trim();
        return bankDraft(
                index,
                row,
                DATE,
                null,
                parseAmount(value(row, AMOUNT)),
                description,
                value(row, TRANSACTION),
                false);
    }
}

final class ActivityBankImporter extends BankImporter {
    @Override
    public String name() {
        return "activity_bank";
    }

    @Override
    public String label() {
        return "Activity Bank CSV (Marcus / Morgan Stanley)";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, DATE, ACTIVITY, DESCRIPTION, AMOUNT, BALANCE);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        String description = (value(row, ACTIVITY) + " " + value(row, DESCRIPTION)).trim();
        return bankDraft(
                index,
                row,
                DATE,
                null,
                parseAmount(value(row, AMOUNT)),
                description,
                value(row, ACTIVITY),
                false);
    }
}
