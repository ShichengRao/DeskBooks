package com.deskbooks.backend.imports;

import java.io.IOException;
import java.math.BigDecimal;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.deskbooks.backend.imports.ImportParsing.money;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.rules.RuleEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
    private static final List<CsvImporter> IMPORTERS = CsvImporters.all();

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
                List<ImportDraftRow> rows = AmexWorkbookParser.parse(data);
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
}
