package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class TransactionMutations {
    private final TransactionReader reader;
    private final TransactionRelations relations;
    private final TransactionLookup lookup = new TransactionLookup();
    private final TransactionPatchBuilder patches = new TransactionPatchBuilder(lookup);

    TransactionMutations(TransactionReader reader, TransactionRelations relations) {
        this.reader = reader;
        this.relations = relations;
    }

    TransactionController.TransactionResponse create(Connection connection, JsonNode body) throws SQLException {
        long accountId = TransactionJson.requiredLong(body, "account_id");
        lookup.requireAccount(connection, accountId);

        Long categoryId = TransactionJson.optionalLong(body, "category_id");
        TransactionCategoryInfo category = categoryId == null ? null : lookup.categoryOr404(connection, categoryId);
        String kind = TransactionJson.textOrDefault(body, "kind", "uncategorized");
        if (category != null && !body.has("kind")) {
            kind = category.kind();
        }

        String descriptionRaw = TransactionJson.requiredText(body, "description_raw");
        String normalized = TransactionJson.textOrNull(body, "description_normalized");
        if (normalized == null || normalized.isBlank()) {
            normalized = TransactionJson.normalizeDescription(descriptionRaw);
        }

        String sql = """
                INSERT INTO transactions (
                  account_id, date, post_date, description_raw, description_normalized,
                  merchant, amount, category_id, kind, is_user_categorized,
                  is_excluded_from_totals, notes, matched_rule_id, raw, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.setString(2, TransactionJson.requiredDate(body, "date").toString());
            statement.setString(3, TransactionJson.optionalDateString(body, "post_date"));
            statement.setString(4, descriptionRaw);
            statement.setString(5, normalized);
            statement.setString(6, TransactionJson.blankToNull(TransactionJson.textOrNull(body, "merchant")));
            statement.setBigDecimal(7, TransactionJson.requiredDecimal(body, "amount"));
            TransactionSql.setNullableLong(statement, 8, categoryId);
            statement.setString(9, kind);
            statement.setBoolean(10, TransactionJson.booleanOrDefault(body, "is_excluded_from_totals", false));
            statement.setString(11, TransactionJson.blankToNull(TransactionJson.textOrNull(body, "notes")));
            statement.setString(12, "{\"source\":\"manual\"}");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return reader.get(connection, keys.getLong(1));
            }
        }
    }

    TransactionController.TransactionResponse setSplit(Connection connection, long transactionId, JsonNode body)
            throws SQLException {
        lookup.requireTransaction(connection, transactionId);
        relations.setSplit(connection, transactionId, body);
        return reader.get(connection, transactionId);
    }

    Map<String, Integer> bulkUpdate(Connection connection, JsonNode body) throws SQLException {
        List<Long> ids = TransactionJson.longList(body.get("ids"));
        if (ids.isEmpty()) {
            return Map.of("updated", 0);
        }

        TransactionCategoryInfo category = patches.bulkCategory(connection, body);
        Set<Long> found = lookup.existingTransactions(connection, ids);
        if (found.isEmpty()) {
            return Map.of("updated", 0);
        }
        for (Long id : found) {
            applyBulkUpdate(connection, id, body, category);
        }
        return Map.of("updated", found.size());
    }

    TransactionController.TransactionResponse update(Connection connection, long transactionId, JsonNode body)
            throws SQLException {
        lookup.requireTransaction(connection, transactionId);
        List<TransactionColumnValue> values = patches.patchValues(connection, body);
        if (!values.isEmpty()) {
            applyTransactionUpdate(connection, transactionId, values);
        }
        return reader.get(connection, transactionId);
    }

    Map<String, String> pair(Connection connection, JsonNode body) throws SQLException {
        long transactionAId = TransactionJson.requiredLong(body, "transaction_a_id");
        long transactionBId = TransactionJson.requiredLong(body, "transaction_b_id");
        lookup.requireTransaction(connection, transactionAId);
        lookup.requireTransaction(connection, transactionBId);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions
                SET transfer_pair_id = ?, kind = 'transfer', is_user_categorized = 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """)) {
            statement.setLong(1, transactionBId);
            statement.setLong(2, transactionAId);
            statement.addBatch();
            statement.setLong(1, transactionAId);
            statement.setLong(2, transactionBId);
            statement.addBatch();
            statement.executeBatch();
        }
        return Map.of("status", "paired");
    }

    Map<String, String> unpair(Connection connection, long transactionId) throws SQLException {
        Long pairId = lookup.transferPairId(connection, transactionId);
        if (pairId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "transaction not paired");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions SET transfer_pair_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id IN (?, ?)
                """)) {
            statement.setLong(1, transactionId);
            statement.setLong(2, pairId);
            statement.executeUpdate();
        }
        return Map.of("status", "unpaired");
    }

    Map<String, String> delete(Connection connection, long transactionId) throws SQLException {
        lookup.requireTransaction(connection, transactionId);
        Long pairId = lookup.transferPairId(connection, transactionId);
        if (pairId != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE transactions SET transfer_pair_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """)) {
                statement.setLong(1, pairId);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
        return Map.of("status", "deleted");
    }

    private void applyBulkUpdate(
            Connection connection,
            long transactionId,
            JsonNode body,
            TransactionCategoryInfo category) throws SQLException {
        List<TransactionColumnValue> values = patches.bulkValues(body, category);
        if (!values.isEmpty()) {
            applyTransactionUpdate(connection, transactionId, values);
        }

        relations.applyBulkChanges(connection, transactionId, body);
    }

    private void applyTransactionUpdate(
            Connection connection,
            long transactionId,
            List<TransactionColumnValue> values) throws SQLException {
        StringJoiner assignments = new StringJoiner(", ");
        for (TransactionColumnValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        assignments.add("updated_at = CURRENT_TIMESTAMP");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (TransactionColumnValue value : values) {
                TransactionSql.bindParam(statement, index++, value.value());
            }
            statement.setLong(index, transactionId);
            statement.executeUpdate();
        }
    }

}

record TransactionCategoryInfo(long id, String kind) {
}

record TransactionColumnValue(String column, Object value) {
}
