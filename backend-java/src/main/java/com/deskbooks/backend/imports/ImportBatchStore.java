package com.deskbooks.backend.imports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.rules.RuleEngine;
import org.springframework.http.HttpStatus;

final class ImportBatchStore {
    private final RuleEngine ruleEngine;
    private final ImportBatchMetadata metadata = new ImportBatchMetadata();
    private final ImportTransactionStore transactions = new ImportTransactionStore();

    ImportBatchStore(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    List<ImportBatchResponse> list(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, source_filename, importer_name, account_id, imported_at,
                       row_count_total, row_count_applied, row_count_duplicate, status, notes
                FROM import_batches
                ORDER BY imported_at DESC, id DESC
                """);
                ResultSet rs = statement.executeQuery()) {
            List<ImportBatchResponse> batches = new ArrayList<>();
            while (rs.next()) {
                batches.add(metadata.batchFrom(rs));
            }
            return batches;
        }
    }

    ImportBatchResponse apply(Connection connection, ImportApplyRequest body) throws SQLException {
        return apply(connection, body, null);
    }

    ImportBatchResponse apply(Connection connection, ImportApplyRequest body, String notes) throws SQLException {
        requireAccount(connection, body.accountId());
        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            long batchId = metadata.create(connection, body, notes);
            ImportCounts counts = new ImportBatchApplier(this, transactions).apply(connection, body, batchId);
            ruleEngine.stampRuleFires(connection, counts.ruleFires());
            metadata.updateCounts(connection, batchId, counts);
            connection.commit();
            committed = true;
            return metadata.get(connection, batchId);
        } finally {
            if (!committed) {
                ImportTransactionScope.rollback(connection);
            }
        }
    }

    boolean hasNotes(Connection connection, String notes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM import_batches WHERE notes = ? LIMIT 1")) {
            statement.setString(1, notes);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    Map<String, String> rollbackBatch(Connection connection, long batchId) throws SQLException {
        return new ImportBatchRollback(metadata, transactions).rollback(connection, batchId);
    }

    Map<DuplicateKey, Integer> existingKeyCounts(Connection connection, long accountId) throws SQLException {
        Map<DuplicateKey, Integer> counts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT date, amount, description_normalized
                FROM transactions
                WHERE account_id = ?
                """)) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    counts.merge(ImportDuplicateKeys.from(rs), 1, Integer::sum);
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

    record ImportCounts(int applied, int duplicates, List<Long> ruleFires) {
    }
}
