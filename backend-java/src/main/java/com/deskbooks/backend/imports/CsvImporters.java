package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.ImportParsing.guessMerchant;
import static com.deskbooks.backend.imports.ImportParsing.money;
import static com.deskbooks.backend.imports.ImportParsing.moneyString;
import static com.deskbooks.backend.imports.ImportParsing.normalize;
import static com.deskbooks.backend.imports.ImportParsing.parseAmount;
import static com.deskbooks.backend.imports.ImportParsing.parseDate;

import com.deskbooks.backend.imports.ImportController.ImportDraftRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CsvImporters {
    private static final List<CsvImporter> IMPORTERS = List.of(
            new ChaseCreditImporter(),
            new WellsFargoCheckingImporter(),
            new AmexImporter(),
            new ContributionHistoryImporter(),
            new ChaseBankImporter(),
            new CitiCreditImporter(),
            new RunningBalanceBankImporter(),
            new PncBankImporter(),
            new DebitCreditBankImporter(),
            new UsBankImporter(),
            new ActivityBankImporter(),
            new CapitalOneCreditImporter(),
            new DiscoverCreditImporter());

    private CsvImporters() {
    }

    static List<CsvImporter> all() {
        return IMPORTERS;
    }

    private abstract static class HeaderCsvImporter implements CsvImporter {
        @Override
        public boolean canHandle(String csvText) {
            return canHandleHeader(header(csvText));
        }

        abstract boolean canHandleHeader(List<String> header);
    }

    private abstract static class DictCsvImporter extends HeaderCsvImporter {
        @Override
        public List<ImportDraftRow> parse(String csvText) {
            CsvData data = readDictRows(csvText);
            List<ImportDraftRow> out = new ArrayList<>();
            for (int i = 0; i < data.rows().size(); i++) {
                ImportDraftRow row = parseRow(i, data.rows().get(i));
                if (row != null) {
                    out.add(row);
                }
            }
            return out;
        }

        abstract ImportDraftRow parseRow(int index, Map<String, String> row);
    }

    private static final class ChaseCreditImporter extends DictCsvImporter {
        @Override public String name() { return "chase_credit"; }
        @Override public String label() { return "Chase Credit Card"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "Transaction Date", "Post Date", "Description", "Type", "Amount");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            LocalDate date = parseDate(row.get("Transaction Date"));
            BigDecimal amount = parseAmount(row.get("Amount"));
            if (date == null || amount == null) return null;
            String type = value(row, "Type").toLowerCase(Locale.ROOT);
            String kind = "uncategorized";
            if ("payment".equals(type)) kind = "cc_payment";
            else if ("return".equals(type)) kind = "refund";
            return draft(index, date, parseDate(row.get("Post Date")), row.get("Description"), amount, kind, row);
        }
    }

    private static final class WellsFargoCheckingImporter extends DictCsvImporter {
        @Override public String name() { return "wells_fargo_checking"; }
        @Override public String label() { return "Wells Fargo Checking"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "DATE", "DESCRIPTION", "AMOUNT", "STATUS");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            LocalDate date = parseDate(value(row, "DATE"));
            BigDecimal amount = parseAmount(value(row, "AMOUNT"));
            if (date == null || amount == null) return null;
            String raw = value(row, "DESCRIPTION");
            String desc = normalize(raw);
            String upper = desc.toUpperCase(Locale.ROOT);
            String kind = "uncategorized";
            String merchant = null;
            if (upper.contains("PAYROLL")) {
                kind = "income";
                merchant = "Salary";
            } else if (upper.contains("CHASE CREDIT CRD") || upper.contains("AMEX EPAYMENT")) {
                kind = "cc_payment";
            } else if (upper.contains("IRS") && upper.contains("USATAXPYMT")) {
                kind = "tax";
            } else if (upper.contains("NYSTTAXRFD") || upper.contains("TAX REFUND")) {
                kind = "income";
                merchant = "Tax Refund";
            } else if (upper.contains("FID BKG SVC") || upper.contains("GOLDMAN SACHS BA") || upper.contains("JPMORGAN CHASE   EXT TRNSFR") || upper.contains("MSPBNA")) {
                kind = "transfer";
            }
            return draft(index, date, null, raw, amount, kind, merchant, row);
        }
    }

    private static final class AmexImporter extends DictCsvImporter {
        @Override public String name() { return "amex"; }
        @Override public String label() { return "Amex"; }
        @Override boolean canHandleHeader(List<String> header) {
            return headerSet(header).equals(Set.of("DATE", "DESCRIPTION", "AMOUNT"));
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            LocalDate date = parseDate(value(row, "DATE"));
            BigDecimal amount = parseAmount(value(row, "AMOUNT"));
            if (date == null || amount == null) return null;
            amount = amount.negate();
            String raw = value(row, "DESCRIPTION");
            String upper = normalize(raw).toUpperCase(Locale.ROOT);
            String kind = upper.contains("AUTOPAY PAYMENT") || upper.contains("PAYMENT - THANK YOU") || upper.contains("PAYMENT RECEIVED")
                    ? "cc_payment"
                    : "uncategorized";
            return draft(index, date, null, raw, amount, kind, row);
        }
    }

    private static final class ContributionHistoryImporter extends HeaderCsvImporter {
        @Override public String name() { return "contribution_history"; }
        @Override public String label() { return "Fidelity Charitable Contribution History"; }
        @Override boolean canHandleHeader(List<String> header) {
            return header.contains("Contribution ID") && header.contains("Estimated Amount");
        }
        @Override public boolean canHandle(String csvText) {
            return headerIndex(readCsv(csvText)) >= 0;
        }
        @Override public List<ImportDraftRow> parse(String csvText) {
            List<List<String>> rows = readCsv(csvText);
            int headerIndex = headerIndex(rows);
            if (headerIndex < 0) return List.of();
            List<String> header = rows.get(headerIndex).stream().map(String::trim).toList();
            List<ImportDraftRow> out = new ArrayList<>();
            int rowIndex = 0;
            for (int i = headerIndex + 1; i < rows.size(); i++) {
                List<String> cells = rows.get(i);
                if (cells.stream().noneMatch(cell -> !cell.trim().isEmpty())) continue;
                Map<String, String> row = rowMap(header, cells);
                LocalDate date = parseDate(row.getOrDefault("Received Date", row.get("Submitted Date")));
                BigDecimal amount = parseAmount(row.getOrDefault("Estimated Amount", row.get("Net Proceeds")));
                if (date == null || amount == null) continue;
                String desc = normalize(row.getOrDefault("Description", "Contribution"));
                String symbol = normalize(row.getOrDefault("Symbol", ""));
                String raw = (symbol + " " + desc).trim();
                out.add(draft(rowIndex++, date, null, raw, amount.negate(), "donation", "Contribution", row));
            }
            return out;
        }
        private int headerIndex(List<List<String>> rows) {
            for (int i = 0; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.contains("Contribution ID") && row.contains("Estimated Amount")) return i;
            }
            return -1;
        }
    }

    private static final class ChaseBankImporter extends BankImporter {
        @Override public String name() { return "chase_bank"; }
        @Override public String label() { return "Chase Bank Account"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "DETAILS", "POSTING DATE", "DESCRIPTION", "AMOUNT", "TYPE");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            return bankDraft(index, row, "POSTING DATE", null, parseAmount(value(row, "AMOUNT")), value(row, "DESCRIPTION"), value(row, "DETAILS") + " " + value(row, "TYPE"), false);
        }
    }

    private static final class RunningBalanceBankImporter extends BankImporter {
        @Override public String name() { return "running_balance_bank"; }
        @Override public String label() { return "Bank CSV (Date / Description / Amount / Balance)"; }
        @Override boolean canHandleHeader(List<String> header) {
            if (hasAny(header, "ACTIVITY", "TRANSACTION", "DEBIT", "CREDIT", "WITHDRAWALS", "DEPOSITS")) return false;
            return hasAll(header, "DATE", "DESCRIPTION", "AMOUNT") && hasAny(header, "RUNNING BAL.", "RUNNING BALANCE", "BALANCE");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            return bankDraft(index, row, "DATE", null, parseAmount(value(row, "AMOUNT")), value(row, "DESCRIPTION"), "", false);
        }
    }

    private static final class PncBankImporter extends BankImporter {
        @Override public String name() { return "pnc_bank"; }
        @Override public String label() { return "PNC Bank Account"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "DATE", "DESCRIPTION", "WITHDRAWALS", "DEPOSITS", "BALANCE");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            return bankDraft(index, row, "DATE", null, debitCredit(row, "WITHDRAWALS", "DEPOSITS"), value(row, "DESCRIPTION"), "", false);
        }
    }

    private static final class DebitCreditBankImporter extends BankImporter {
        @Override public String name() { return "debit_credit_bank"; }
        @Override public String label() { return "Bank CSV (Debit / Credit)"; }
        @Override boolean canHandleHeader(List<String> header) {
            if (hasAny(header, "STATUS", "CARD NO.")) return false;
            return hasAll(header, "DATE", "DESCRIPTION", "DEBIT", "CREDIT");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            return bankDraft(index, row, "DATE", null, debitCredit(row, "DEBIT", "CREDIT"), value(row, "DESCRIPTION"), "", false);
        }
    }

    private static final class UsBankImporter extends BankImporter {
        @Override public String name() { return "us_bank"; }
        @Override public String label() { return "U.S. Bank Account"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "DATE", "TRANSACTION", "NAME", "MEMO", "AMOUNT");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            String description = String.join(" ", List.of(value(row, "NAME"), value(row, "TRANSACTION"), value(row, "MEMO"))).replaceAll("\\s+", " ").trim();
            return bankDraft(index, row, "DATE", null, parseAmount(value(row, "AMOUNT")), description, value(row, "TRANSACTION"), false);
        }
    }

    private static final class ActivityBankImporter extends BankImporter {
        @Override public String name() { return "activity_bank"; }
        @Override public String label() { return "Activity Bank CSV (Marcus / Morgan Stanley)"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "DATE", "ACTIVITY", "DESCRIPTION", "AMOUNT", "BALANCE");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            String description = (value(row, "ACTIVITY") + " " + value(row, "DESCRIPTION")).trim();
            return bankDraft(index, row, "DATE", null, parseAmount(value(row, "AMOUNT")), description, value(row, "ACTIVITY"), false);
        }
    }

    private static final class CapitalOneCreditImporter extends BankImporter {
        @Override public String name() { return "capital_one_credit"; }
        @Override public String label() { return "Capital One Credit Card"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "TRANSACTION DATE", "POSTED DATE", "CARD NO.", "DESCRIPTION", "CATEGORY", "DEBIT", "CREDIT");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            return bankDraft(index, row, "TRANSACTION DATE", "POSTED DATE", debitCredit(row, "DEBIT", "CREDIT"), value(row, "DESCRIPTION"), value(row, "CATEGORY"), true);
        }
    }

    private static final class CitiCreditImporter extends BankImporter {
        @Override public String name() { return "citi_credit"; }
        @Override public String label() { return "Citi Credit Card"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "STATUS", "DATE", "DESCRIPTION", "DEBIT", "CREDIT");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            return bankDraft(index, row, "DATE", null, debitCredit(row, "DEBIT", "CREDIT"), value(row, "DESCRIPTION"), value(row, "STATUS"), true);
        }
    }

    private static final class DiscoverCreditImporter extends BankImporter {
        @Override public String name() { return "discover_credit"; }
        @Override public String label() { return "Discover Credit Card"; }
        @Override boolean canHandleHeader(List<String> header) {
            return hasAll(header, "TRANS. DATE", "POST DATE", "DESCRIPTION", "AMOUNT", "CATEGORY");
        }
        @Override ImportDraftRow parseRow(int index, Map<String, String> row) {
            BigDecimal amount = parseAmount(value(row, "AMOUNT"));
            return bankDraft(index, row, "TRANS. DATE", "POST DATE", amount == null ? null : amount.negate(), value(row, "DESCRIPTION"), value(row, "CATEGORY"), true);
        }
    }

    private abstract static class BankImporter extends DictCsvImporter {
        ImportDraftRow bankDraft(int index, Map<String, String> row, String dateKey, String postDateKey, BigDecimal amount, String description, String extra, boolean creditCard) {
            LocalDate date = parseDate(value(row, dateKey));
            if (date == null || amount == null) return null;
            String desc = normalize(description);
            return draft(
                    index,
                    date,
                    postDateKey == null ? null : parseDate(value(row, postDateKey)),
                    description,
                    amount,
                    ImportKindSuggester.suggest(desc, amount, creditCard, extra),
                    row);
        }
    }

    private static ImportDraftRow draft(int index, LocalDate date, LocalDate postDate, String raw, BigDecimal amount, String kind, Map<String, String> source) {
        return draft(index, date, postDate, raw, amount, kind, null, source);
    }

    private static ImportDraftRow draft(int index, LocalDate date, LocalDate postDate, String raw, BigDecimal amount, String kind, String merchant, Map<String, String> source) {
        String normalized = normalize(raw);
        return new ImportDraftRow(
                index,
                date,
                postDate,
                raw,
                normalized,
                merchant == null ? guessMerchant(normalized) : merchant,
                moneyString(amount),
                null,
                kind,
                List.of(),
                null,
                false,
                source);
    }

    private static BigDecimal debitCredit(Map<String, String> row, String debitKey, String creditKey) {
        BigDecimal debit = parseAmount(value(row, debitKey));
        BigDecimal credit = parseAmount(value(row, creditKey));
        if (debit != null && debit.compareTo(BigDecimal.ZERO) != 0) return debit.abs().negate();
        if (credit != null && credit.compareTo(BigDecimal.ZERO) != 0) return credit.abs();
        return money(BigDecimal.ZERO);
    }

    private static CsvData readDictRows(String csvText) {
        List<List<String>> rows = readCsv(csvText);
        if (rows.isEmpty()) return new CsvData(List.of(), List.of());
        List<String> header = rows.get(0).stream().map(cell -> cell.trim().replace("\"", "")).toList();
        List<Map<String, String>> dictRows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().noneMatch(cell -> !cell.trim().isEmpty())) continue;
            dictRows.add(rowMap(header, row));
        }
        return new CsvData(header, dictRows);
    }

    private static Map<String, String> rowMap(List<String> header, List<String> cells) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(header.size(), cells.size()); i++) {
            map.put(i < header.size() ? header.get(i) : "col" + i, i < cells.size() ? cells.get(i) : "");
        }
        return map;
    }

    private static List<String> header(String csvText) {
        List<List<String>> rows = readCsv(csvText);
        return rows.isEmpty() ? List.of() : rows.get(0).stream().map(String::trim).toList();
    }

    private static List<List<String>> readCsv(String csvText) {
        String text = csvText.startsWith("\uFEFF") ? csvText.substring(1) : csvText;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                row.add(cell.toString());
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
            } else if (ch != '\r') {
                cell.append(ch);
            }
        }
        row.add(cell.toString());
        if (row.stream().anyMatch(value -> !value.isEmpty())) {
            rows.add(row);
        }
        return rows;
    }

    private static boolean hasAll(List<String> header, String... names) {
        Set<String> values = headerSet(header);
        for (String name : names) {
            if (!values.contains(name.toUpperCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static boolean hasAny(List<String> header, String... names) {
        Set<String> values = headerSet(header);
        for (String name : names) {
            if (values.contains(name.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Set<String> headerSet(List<String> header) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : header) out.add(value.trim().toUpperCase(Locale.ROOT));
        return out;
    }

    private static String value(Map<String, String> row, String key) {
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return "";
    }

    private record CsvData(List<String> header, List<Map<String, String>> rows) {
    }
}
