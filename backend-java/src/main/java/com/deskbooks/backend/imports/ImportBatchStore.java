package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.ImportParsing.money;

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
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.rules.RuleEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

final class ImportBatchStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RuleEngine ruleEngine;

    ImportBatchStore(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    List<ImportController.ImportBatchResponse> list(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, source_filename, importer_name, account_id, imported_at,
                       row_count_total, row_count_applied, row_count_duplicate, status, notes
                FROM import_batches
                ORDER BY imported_at DESC, id DESC
                """);
                ResultSet rs = statement.executeQuery()) {
            List<ImportController.ImportBatchResponse> batches = new ArrayList<>();
            while (rs.next()) {
                batches.add(batchFrom(rs));
            }
            return batches;
        }
    }

    ImportController.ImportBatchResponse apply(Connection connection, ImportController.ImportApplyRequest body) throws SQLException {
        requireAccount(connection, body.accountId());
        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            long batchId = createBatch(connection, body);
            ImportCounts counts = importRows(connection, body, batchId);
            ruleEngine.stampRuleFires(connection, counts.ruleFires());
            updateBatchCounts(connection, batchId, counts);
            connection.commit();
            committed = true;
            return getBatch(connection, batchId);
        } finally {
            if (!committed) {
                rollback(connection);
            }
        }
    }

    Map<String, String> rollbackBatch(Connection connection, long batchId) throws SQLException {
        ImportController.ImportBatchResponse batch = getBatch(connection, batchId);
        if (!"applied".equals(batch.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "batch is not in 'applied' state");
        }

        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            deleteTransactions(connection, batchId);
            markRolledBack(connection, batchId);
            connection.commit();
            committed = true;
            return Map.of("status", "rolled_back");
        } finally {
            if (!committed) {
                rollback(connection);
            }
        }
    }

    Map<ImportController.DuplicateKey, Integer> existingKeyCounts(Connection connection, long accountId) throws SQLException {
        Map<ImportController.DuplicateKey, Integer> counts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT date, amount, description_normalized
                FROM transactions
                WHERE account_id = ?
                """)) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    counts.merge(keyFrom(rs), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    void requireAccount(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "account not found");
                }
            }
        }
    }

    private long createBatch(Connection connection, ImportController.ImportApplyRequest body) throws SQLException {
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
                return keys.getLong(1);
            }
        }
    }

    private ImportCounts importRows(Connection connection, ImportController.ImportApplyRequest body, long batchId) throws SQLException {
        Map<ImportController.DuplicateKey, Integer> existing = existingKeyCounts(connection, body.accountId());
        Map<ImportController.DuplicateKey, Integer> fileCounts = new LinkedHashMap<>();
        List<Long> ruleFires = new ArrayList<>();
        int applied = 0;
        int duplicates = 0;
        for (ImportController.ImportDraftRow row : body.rows()) {
            ImportController.DuplicateKey key = keyFor(row);
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
        return new ImportCounts(applied, duplicates, ruleFires);
    }

    private void updateBatchCounts(Connection connection, long batchId, ImportCounts counts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE import_batches
                SET row_count_applied = ?, row_count_duplicate = ?
                WHERE id = ?
                """)) {
            statement.setInt(1, counts.applied());
            statement.setInt(2, counts.duplicates());
            statement.setLong(3, batchId);
            statement.executeUpdate();
        }
    }

    private ImportController.ImportBatchResponse getBatch(Connection connection, long batchId) throws SQLException {
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

    private ImportController.ImportBatchResponse batchFrom(ResultSet rs) throws SQLException {
        return new ImportController.ImportBatchResponse(
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

    private void insertTransaction(Connection connection, long accountId, long batchId, ImportController.ImportDraftRow row) throws SQLException {
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

    private void deleteTransactions(Connection connection, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM transactions WHERE import_batch_id = ?")) {
            statement.setLong(1, batchId);
            statement.executeUpdate();
        }
    }

    private void markRolledBack(Connection connection, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE import_batches SET status = 'rolled_back' WHERE id = ?")) {
            statement.setLong(1, batchId);
            statement.executeUpdate();
        }
    }

    private ImportController.DuplicateKey keyFrom(ResultSet rs) throws SQLException {
        return new ImportController.DuplicateKey(
                LocalDate.parse(rs.getString("date")),
                money(rs.getBigDecimal("amount")),
                rs.getString("description_normalized") == null ? "" : rs.getString("description_normalized"));
    }

    private ImportController.DuplicateKey keyFor(ImportController.ImportDraftRow row) {
        return new ImportController.DuplicateKey(
                row.date(),
                money(row.amountValue()),
                row.descriptionNormalized() == null ? "" : row.descriptionNormalized());
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

    private record ImportCounts(int applied, int duplicates, List<Long> ruleFires) {
    }
}
