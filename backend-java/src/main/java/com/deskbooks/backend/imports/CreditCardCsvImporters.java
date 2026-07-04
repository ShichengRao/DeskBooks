package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.CsvDrafts.debitCredit;
import static com.deskbooks.backend.imports.CsvDrafts.draft;
import static com.deskbooks.backend.imports.CsvColumns.AMOUNT;
import static com.deskbooks.backend.imports.CsvColumns.CATEGORY;
import static com.deskbooks.backend.imports.CsvColumns.CREDIT;
import static com.deskbooks.backend.imports.CsvColumns.DATE;
import static com.deskbooks.backend.imports.CsvColumns.DEBIT;
import static com.deskbooks.backend.imports.CsvColumns.DESCRIPTION;
import static com.deskbooks.backend.imports.CsvColumns.STATUS;
import static com.deskbooks.backend.imports.CsvRows.hasAll;
import static com.deskbooks.backend.imports.CsvRows.headerSet;
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
import java.util.Set;

final class ChaseCreditImporter extends DictCsvImporter {
    private static final String PAYMENT_TYPE = "payment";
    private static final String RETURN_TYPE = "return";

    @Override
    public String name() {
        return "chase_credit";
    }

    @Override
    public String label() {
        return "Chase Credit Card";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, "Transaction Date", "Post Date", "Description", "Type", "Amount");
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        LocalDate date = parseDate(row.get("Transaction Date"));
        BigDecimal amount = parseAmount(row.get("Amount"));
        if (date == null || amount == null) {
            return null;
        }
        String type = value(row, "Type").toLowerCase(Locale.ROOT);
        String kind = "uncategorized";
        if (PAYMENT_TYPE.equals(type)) {
            kind = "cc_payment";
        } else if (RETURN_TYPE.equals(type)) {
            kind = "refund";
        }
        return draft(index, date, parseDate(row.get("Post Date")), row.get("Description"), amount, kind, row);
    }
}

final class AmexImporter extends DictCsvImporter {
    @Override
    public String name() {
        return "amex";
    }

    @Override
    public String label() {
        return "Amex";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return headerSet(header).equals(Set.of(DATE, DESCRIPTION, AMOUNT));
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        LocalDate date = parseDate(value(row, DATE));
        BigDecimal amount = parseAmount(value(row, AMOUNT));
        if (date == null || amount == null) {
            return null;
        }
        amount = amount.negate();
        String raw = value(row, DESCRIPTION);
        String upper = normalize(raw).toUpperCase(Locale.ROOT);
        String kind = upper.contains("AUTOPAY PAYMENT")
                || upper.contains("PAYMENT - THANK YOU")
                || upper.contains("PAYMENT RECEIVED")
                        ? "cc_payment"
                        : "uncategorized";
        return draft(index, date, null, raw, amount, kind, row);
    }
}

final class CapitalOneCreditImporter extends BankImporter {
    @Override
    public String name() {
        return "capital_one_credit";
    }

    @Override
    public String label() {
        return "Capital One Credit Card";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, "TRANSACTION DATE", "POSTED DATE", "CARD NO.", DESCRIPTION, CATEGORY, DEBIT, CREDIT);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        return bankDraft(
                index,
                row,
                "TRANSACTION DATE",
                "POSTED DATE",
                debitCredit(row, DEBIT, CREDIT),
                value(row, DESCRIPTION),
                value(row, CATEGORY),
                true);
    }
}

final class CitiCreditImporter extends BankImporter {
    @Override
    public String name() {
        return "citi_credit";
    }

    @Override
    public String label() {
        return "Citi Credit Card";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, STATUS, DATE, DESCRIPTION, DEBIT, CREDIT);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        return bankDraft(
                index,
                row,
                DATE,
                null,
                debitCredit(row, DEBIT, CREDIT),
                value(row, DESCRIPTION),
                value(row, STATUS),
                true);
    }
}

final class DiscoverCreditImporter extends BankImporter {
    @Override
    public String name() {
        return "discover_credit";
    }

    @Override
    public String label() {
        return "Discover Credit Card";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return hasAll(header, "TRANS. DATE", "POST DATE", DESCRIPTION, AMOUNT, CATEGORY);
    }

    @Override
    ImportDraftRow parseRow(int index, Map<String, String> row) {
        BigDecimal amount = parseAmount(value(row, AMOUNT));
        return bankDraft(
                index,
                row,
                "TRANS. DATE",
                "POST DATE",
                amount == null ? null : amount.negate(),
                value(row, DESCRIPTION),
                value(row, CATEGORY),
                true);
    }
}
