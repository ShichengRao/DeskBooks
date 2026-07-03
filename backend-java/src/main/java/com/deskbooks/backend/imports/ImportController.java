package com.deskbooks.backend.imports;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.rules.RuleEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
class ImportController {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DataFormatter EXCEL_FORMATTER = new DataFormatter(Locale.US);
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

    private final SqliteConnectionProvider connections;
    private final RuleEngine ruleEngine;

    ImportController(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.ruleEngine = ruleEngine;
    }

    @GetMapping("/importers")
    List<ImporterResponse> listImporters() {
        return IMPORTERS.stream()
                .map(importer -> new ImporterResponse(importer.name(), importer.label()))
                .toList();
    }

    @GetMapping("")
    List<ImportBatchResponse> listBatches() {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, source_filename, importer_name, account_id, imported_at,
                               row_count_total, row_count_applied, row_count_duplicate, status, notes
                        FROM import_batches
                        ORDER BY imported_at DESC, id DESC
                        """);
                ResultSet rs = statement.executeQuery()) {
            List<ImportBatchResponse> batches = new ArrayList<>();
            while (rs.next()) {
                batches.add(batchFrom(rs));
            }
            return batches;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/preview")
    ImportPreviewResponse previewUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("account_id") long accountId,
            @RequestParam(name = "importer_name", required = false) String importerName) {
        try {
            return previewBytes(file.getBytes(), file.getOriginalFilename() == null ? "uploaded.csv" : file.getOriginalFilename(), accountId, importerName);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read upload");
        }
    }

    @PostMapping("/preview-path")
    ImportPreviewResponse previewPath(@Valid @RequestBody ImportPathPreviewRequest body) {
        Path path = Path.of(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        try {
            return previewBytes(Files.readAllBytes(path), path.getFileName().toString(), body.accountId(), body.importerName());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read file");
        }
    }

    @PostMapping("/apply")
    ImportBatchResponse apply(@Valid @RequestBody ImportApplyRequest body) {
        try (Connection connection = connections.open()) {
            requireAccount(connection, body.accountId());
            try {
                connection.setAutoCommit(false);
                long batchId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO import_batches (
                          source_filename, importer_name, account_id, row_count_total,
                          row_count_applied, row_count_duplicate, status
                        ) VALUES (?, ?, ?, ?, 0, 0, 'applied')
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, body.sourceFilename());
                    statement.setString(2, body.importerName());
                    statement.setLong(3, body.accountId());
                    statement.setInt(4, body.rows().size());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        batchId = keys.getLong(1);
                    }
                }

                Map<DuplicateKey, Integer> existing = existingKeyCounts(connection, body.accountId());
                Map<DuplicateKey, Integer> fileCounts = new LinkedHashMap<>();
                List<Long> ruleFires = new ArrayList<>();
                int applied = 0;
                int duplicates = 0;
                for (ImportDraftRow row : body.rows()) {
                    DuplicateKey key = new DuplicateKey(row.date(), money(row.amountValue()), row.descriptionNormalized() == null ? "" : row.descriptionNormalized());
                    int position = fileCounts.getOrDefault(key, 0);
                    fileCounts.put(key, position + 1);
                    boolean duplicate = position < existing.getOrDefault(key, 0);
                    if (duplicate && body.skipDuplicates()) {
                        duplicates++;
                        continue;
                    }
                    insertTransaction(connection, body.accountId(), batchId, row);
                    applied++;
                    if (row.suggestedMatchedRuleId() != null) {
                        ruleFires.add(row.suggestedMatchedRuleId());
                    }
                }
                ruleEngine.stampRuleFires(connection, ruleFires);

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE import_batches
                        SET row_count_applied = ?, row_count_duplicate = ?
                        WHERE id = ?
                        """)) {
                    statement.setInt(1, applied);
                    statement.setInt(2, duplicates);
                    statement.setLong(3, batchId);
                    statement.executeUpdate();
                }
                connection.commit();
                return getBatch(connection, batchId);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/{batchId}/rollback")
    Map<String, String> rollbackBatch(@PathVariable long batchId) {
        try (Connection connection = connections.open()) {
            ImportBatchResponse batch = getBatch(connection, batchId);
            if (!"applied".equals(batch.status())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "batch is not in 'applied' state");
            }
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM transactions WHERE import_batch_id = ?
                        """)) {
                    statement.setLong(1, batchId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE import_batches SET status = 'rolled_back' WHERE id = ?
                        """)) {
                    statement.setLong(1, batchId);
                    statement.executeUpdate();
                }
                connection.commit();
                return Map.of("status", "rolled_back");
            } catch (SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ImportPreviewResponse previewBytes(byte[] data, String filename, long accountId, String importerName) {
        try (Connection connection = connections.open()) {
            requireAccount(connection, accountId);
            if (filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                List<ImportDraftRow> rows = parseAmexXlsx(data);
                if (rows.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "no importer can handle this file");
                }
                return previewRows(
                        connection,
                        rows,
                        "amex",
                        accountId,
                        filename,
                        List.of("matched importers: amex"));
            }

            String csvText = new String(data, StandardCharsets.UTF_8);
            List<CsvImporter> matched = IMPORTERS.stream().filter(importer -> importer.canHandle(csvText)).toList();
            CsvImporter chosen;
            if (importerName != null && !importerName.isBlank()) {
                chosen = IMPORTERS.stream()
                        .filter(importer -> importer.name().equals(importerName))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "unknown importer: " + importerName));
            } else {
                if (matched.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "no importer can handle this file");
                }
                chosen = matched.get(0);
            }

            List<ImportDraftRow> rows = chosen.parse(csvText);
            String names = matched.isEmpty()
                    ? ""
                    : String.join(", ", matched.stream().map(CsvImporter::name).toList());
            return previewRows(
                    connection,
                    rows,
                    chosen.name(),
                    accountId,
                    filename,
                    List.of("matched importers: " + names));
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ImportPreviewResponse previewRows(
            Connection connection,
            List<ImportDraftRow> rows,
            String importerName,
            long accountId,
            String filename,
            List<String> sniffNotes) throws SQLException {
            List<RuleEngine.RuleRecord> activeRules = ruleEngine.loadActiveRules(connection);
            Map<DuplicateKey, Integer> existing = existingKeyCounts(connection, accountId);
            Map<DuplicateKey, Integer> fileCounts = new LinkedHashMap<>();
            List<ImportDraftRow> markedRows = new ArrayList<>();
            for (ImportDraftRow row : rows) {
                row = row.withRuleSuggestion(ruleEngine.evaluate(
                        activeRules,
                        accountId,
                        row.descriptionNormalized() == null ? row.descriptionRaw() : row.descriptionNormalized(),
                        row.amountValue()));
                DuplicateKey key = new DuplicateKey(row.date(), money(row.amountValue()), row.descriptionNormalized() == null ? "" : row.descriptionNormalized());
                int position = fileCounts.getOrDefault(key, 0);
                fileCounts.put(key, position + 1);
                boolean duplicate = position < existing.getOrDefault(key, 0);
                markedRows.add(row.withDuplicate(duplicate));
            }
            return new ImportPreviewResponse(
                    importerName,
                    accountId,
                    filename,
                    markedRows,
                    sniffNotes);
    }

    private List<ImportDraftRow> parseAmexXlsx(byte[] data) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheet("Transaction Details");
            if (sheet == null) {
                if (workbook.getNumberOfSheets() == 0) {
                    return List.of();
                }
                sheet = workbook.getSheetAt(0);
            }

            int headerRowIndex = -1;
            int maxHeaderRow = Math.min(sheet.getLastRowNum(), 29);
            for (int rowIndex = 0; rowIndex <= maxHeaderRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if ("date".equalsIgnoreCase(cellString(row == null ? null : row.getCell(0)).trim())) {
                    headerRowIndex = rowIndex;
                    break;
                }
            }
            if (headerRowIndex < 0) {
                return List.of();
            }

            List<ImportDraftRow> out = new ArrayList<>();
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                LocalDate date = cellDate(row.getCell(0));
                String rawDescription = cellString(row.getCell(1));
                BigDecimal amount = cellAmount(row.getCell(2));
                if (date == null || rawDescription.isBlank() || amount == null) {
                    continue;
                }
                amount = amount.negate();
                String normalized = normalize(rawDescription);
                String upper = normalized.toUpperCase(Locale.ROOT);
                String kind = upper.contains("AUTOPAY PAYMENT")
                        || upper.contains("PAYMENT - THANK YOU")
                        || upper.contains("PAYMENT RECEIVED")
                        ? "cc_payment"
                        : "uncategorized";
                out.add(new ImportDraftRow(
                        rowIndex - headerRowIndex - 1,
                        date,
                        null,
                        rawDescription,
                        normalized,
                        guessMerchant(normalized),
                        moneyString(amount),
                        null,
                        kind,
                        List.of(),
                        null,
                        false,
                        Map.of("row", String.valueOf(rowIndex + 1))));
            }
            return out;
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read xlsx file");
        }
    }

    private ImportBatchResponse getBatch(Connection connection, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, source_filename, importer_name, account_id, imported_at,
                       row_count_total, row_count_applied, row_count_duplicate, status, notes
                FROM import_batches
                WHERE id = ?
                """)) {
            statement.setLong(1, batchId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "import batch not found");
                }
                return batchFrom(rs);
            }
        }
    }

    private ImportBatchResponse batchFrom(ResultSet rs) throws SQLException {
        return new ImportBatchResponse(
                rs.getLong("id"),
                rs.getString("source_filename"),
                rs.getString("importer_name"),
                rs.getLong("account_id"),
                localDateTime(rs.getString("imported_at")),
                rs.getInt("row_count_total"),
                rs.getInt("row_count_applied"),
                rs.getInt("row_count_duplicate"),
                rs.getString("status"),
                rs.getString("notes"));
    }

    private void insertTransaction(Connection connection, long accountId, long batchId, ImportDraftRow row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                  account_id, date, post_date, description_raw, description_normalized,
                  merchant, amount, category_id, kind, is_user_categorized,
                  is_excluded_from_totals, notes, import_batch_id, matched_rule_id, raw,
                  updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, NULL, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            statement.setLong(1, accountId);
            statement.setString(2, row.date().toString());
            statement.setString(3, row.postDate() == null ? null : row.postDate().toString());
            statement.setString(4, row.descriptionRaw());
            statement.setString(5, row.descriptionNormalized());
            statement.setString(6, row.merchant());
            statement.setBigDecimal(7, money(row.amountValue()));
            if (row.suggestedCategoryId() == null) {
                statement.setObject(8, null);
            } else {
                statement.setLong(8, row.suggestedCategoryId());
            }
            statement.setString(9, row.suggestedKind() == null ? "uncategorized" : row.suggestedKind());
            statement.setLong(10, batchId);
            if (row.suggestedMatchedRuleId() == null) {
                statement.setObject(11, null);
            } else {
                statement.setLong(11, row.suggestedMatchedRuleId());
            }
            statement.setString(12, rawJson(row.raw()));
            statement.executeUpdate();
        }
    }

    private Map<DuplicateKey, Integer> existingKeyCounts(Connection connection, long accountId) throws SQLException {
        Map<DuplicateKey, Integer> counts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT date, amount, description_normalized
                FROM transactions
                WHERE account_id = ?
                """)) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    DuplicateKey key = new DuplicateKey(
                            LocalDate.parse(rs.getString("date")),
                            money(rs.getBigDecimal("amount")),
                            rs.getString("description_normalized") == null ? "" : rs.getString("description_normalized"));
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private void requireAccount(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "account not found");
                }
            }
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyString(BigDecimal value) {
        return money(value).toPlainString();
    }

    private String rawJson(Map<String, String> raw) {
        try {
            return raw == null ? null : MAPPER.writeValueAsString(raw);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private LocalDateTime localDateTime(String value) {
        if (value == null) {
            return null;
        }
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDateTime.parse(value, SQLITE_TIMESTAMP);
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    private interface CsvImporter {
        String name();
        String label();
        boolean canHandle(String csvText);
        List<ImportDraftRow> parse(String csvText);
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
            return headerSet(header).equals(java.util.Set.of("DATE", "DESCRIPTION", "AMOUNT"));
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
            return draft(index, date, postDateKey == null ? null : parseDate(value(row, postDateKey)), description, amount, suggestKind(desc, amount, creditCard, extra), row);
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
                amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                null,
                kind,
                List.of(),
                null,
                false,
                source);
    }

    private static String suggestKind(String description, BigDecimal amount, boolean creditCard, String extra) {
        String haystack = (description + " " + extra).toUpperCase(Locale.ROOT);
        if ((haystack.contains("REFUND") || haystack.contains("RETURN") || haystack.contains("REVERSAL")) && amount.compareTo(BigDecimal.ZERO) > 0) return "refund";
        if (haystack.contains("PAYMENT") && (creditCard || haystack.contains("CREDIT CARD") || haystack.contains("CRD"))) return "cc_payment";
        if (haystack.contains("AUTOPAY") && creditCard) return "cc_payment";
        if (haystack.contains("DIRECT DEP") || haystack.contains("DIRECTDEP") || haystack.contains("PAYROLL")) return "income";
        if (haystack.contains("INTEREST") && amount.compareTo(BigDecimal.ZERO) > 0) return "income";
        if (haystack.contains("TAX REFUND") || haystack.contains("TREAS 310 TAX REF")) return "income";
        if (haystack.contains("IRS") || haystack.contains("USATAXPYMT")) return "tax";
        if (haystack.contains("TRANSFER") || haystack.contains("XFER") || haystack.contains("EXT TRNSFR")) return "transfer";
        return "uncategorized";
    }

    private static BigDecimal debitCredit(Map<String, String> row, String debitKey, String creditKey) {
        BigDecimal debit = parseAmount(value(row, debitKey));
        BigDecimal credit = parseAmount(value(row, creditKey));
        if (debit != null && debit.compareTo(BigDecimal.ZERO) != 0) return debit.abs().negate();
        if (credit != null && credit.compareTo(BigDecimal.ZERO) != 0) return credit.abs();
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
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

    private static BigDecimal parseAmount(String value) {
        if (value == null) return null;
        String cleaned = value.trim().replace("$", "").replace(",", "");
        if (cleaned.isEmpty()) return null;
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            cleaned = "-" + cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal cellAmount(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            } catch (IllegalStateException ignored) {
                // Fall through to formatted text parsing.
            }
        }
        return parseAmount(cellString(cell));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim().replace("\"", "");
        try {
            if (trimmed.contains("T")) {
                return OffsetDateTime.parse(trimmed).toLocalDate();
            }
        } catch (DateTimeParseException ignored) {
            // Try the local-date formats below.
        }
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                new DateTimeFormatterBuilder()
                        .appendPattern("M/d/")
                        .appendValue(ChronoField.YEAR, 2, 4, java.time.format.SignStyle.NORMAL)
                        .toFormatter(Locale.US));
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // Continue.
            }
        }
        return null;
    }

    private static LocalDate cellDate(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                if (DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
            } catch (RuntimeException ignored) {
                // Fall through to formatted text parsing.
            }
        }
        return parseDate(cellString(cell));
    }

    private static String cellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return EXCEL_FORMATTER.formatCellValue(cell).trim();
    }

    private static boolean hasAll(List<String> header, String... names) {
        java.util.Set<String> values = headerSet(header);
        for (String name : names) {
            if (!values.contains(name.toUpperCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static boolean hasAny(List<String> header, String... names) {
        java.util.Set<String> values = headerSet(header);
        for (String name : names) {
            if (values.contains(name.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static java.util.Set<String> headerSet(List<String> header) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
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

    private static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    private static String guessMerchant(String raw) {
        String value = normalize(raw)
                .replaceFirst("(?i)^(DD \\*|TST\\*|SQ \\*|SP \\*|PY \\*|PAYPAL \\*|VENMO \\*)", "")
                .replaceFirst("\\s+[A-Z]{2}\\s*$", "")
                .replaceFirst("\\s+\\d{6,}\\s*$", "")
                .replaceAll("\\s+#\\d+", "")
                .trim();
        if (value.isEmpty()) return value;
        StringBuilder title = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).split(" ")) {
            if (part.isEmpty()) continue;
            title.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(" ");
        }
        return title.toString().trim();
    }

    record ImportPathPreviewRequest(@NotNull String path, long accountId, String importerName) {
    }

    record ImportApplyRequest(
            @NotNull String importerName,
            long accountId,
            @NotNull String sourceFilename,
            List<ImportDraftRow> rows,
            boolean skipDuplicates) {
        ImportApplyRequest {
            rows = rows == null ? List.of() : rows;
        }
    }

    record ImporterResponse(String name, String label) {
    }

    record ImportPreviewResponse(
            String importerName,
            long accountId,
            String sourceFilename,
            List<ImportDraftRow> rows,
            List<String> sniffNotes) {
    }

    record ImportDraftRow(
            int rowIndex,
            LocalDate date,
            LocalDate postDate,
            String descriptionRaw,
            String descriptionNormalized,
            String merchant,
            String amount,
            Long suggestedCategoryId,
            String suggestedKind,
            List<String> suggestedTags,
            Long suggestedMatchedRuleId,
            boolean isDuplicate,
            Map<String, String> raw) {
        ImportDraftRow withDuplicate(boolean duplicate) {
            return new ImportDraftRow(
                    rowIndex,
                    date,
                    postDate,
                    descriptionRaw,
                    descriptionNormalized,
                    merchant,
                    amount,
                    suggestedCategoryId,
                    suggestedKind,
                    suggestedTags,
                    suggestedMatchedRuleId,
                    duplicate,
                    raw);
        }
        ImportDraftRow withRuleSuggestion(RuleEngine.RuleEval eval) {
            if (!eval.matched()) {
                return this;
            }
            return new ImportDraftRow(
                    rowIndex,
                    date,
                    postDate,
                    descriptionRaw,
                    descriptionNormalized,
                    eval.merchant() == null || eval.merchant().isBlank() ? merchant : eval.merchant(),
                    amount,
                    eval.categoryId() == null ? suggestedCategoryId : eval.categoryId(),
                    eval.kind() == null ? suggestedKind : eval.kind(),
                    eval.tags() == null ? suggestedTags : eval.tags(),
                    eval.matchedRuleId(),
                    isDuplicate,
                    raw);
        }
        BigDecimal amountValue() {
            return amount == null ? null : new BigDecimal(amount);
        }
    }

    record ImportBatchResponse(
            long id,
            String sourceFilename,
            String importerName,
            long accountId,
            LocalDateTime importedAt,
            int rowCountTotal,
            int rowCountApplied,
            int rowCountDuplicate,
            String status,
            String notes) {
    }

    record DuplicateKey(LocalDate date, BigDecimal amount, String description) {
    }

    record CsvData(List<String> header, List<Map<String, String>> rows) {
    }
}
