package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    TransactionMutations(TransactionReader reader, TransactionRelations relations) {
        this.reader = reader;
        this.relations = relations;
    }

    TransactionController.TransactionResponse create(Connection connection, JsonNode body) throws SQLException {
        long accountId = TransactionJson.requiredLong(body, "account_id");
        requireAccount(connection, accountId);

        Long categoryId = TransactionJson.optionalLong(body, "category_id");
        TransactionCategoryInfo category = categoryId == null ? null : categoryOr404(connection, categoryId);
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
        requireTransaction(connection, transactionId);
        relations.setSplit(connection, transactionId, body);
        return reader.get(connection, transactionId);
    }

    Map<String, Integer> bulkUpdate(Connection connection, JsonNode body) throws SQLException {
        List<Long> ids = TransactionJson.longList(body.get("ids"));
        if (ids.isEmpty()) {
            return Map.of("updated", 0);
        }

        TransactionCategoryInfo category = null;
        if (body.has("category_id") && !body.get("category_id").isNull()) {
            category = categoryOr404(connection, body.get("category_id").asLong());
        }
        Set<Long> found = existingTransactions(connection, ids);
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
        requireTransaction(connection, transactionId);
        List<TransactionColumnValue> values = patchValues(connection, body);
        if (!values.isEmpty()) {
            applyTransactionUpdate(connection, transactionId, values);
        }
        return reader.get(connection, transactionId);
    }

    Map<String, String> pair(Connection connection, JsonNode body) throws SQLException {
        long transactionAId = TransactionJson.requiredLong(body, "transaction_a_id");
        long transactionBId = TransactionJson.requiredLong(body, "transaction_b_id");
        requireTransaction(connection, transactionAId);
        requireTransaction(connection, transactionBId);
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
        Long pairId = transferPairId(connection, transactionId);
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
        requireTransaction(connection, transactionId);
        Long pairId = transferPairId(connection, transactionId);
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

    private List<TransactionColumnValue> patchValues(Connection connection, JsonNode body) throws SQLException {
        List<TransactionColumnValue> values = new ArrayList<>();
        addDate(values, body, "date");
        addDate(values, body, "post_date");
        if (body.has("description_raw")) {
            String raw = TransactionJson.textOrNull(body, "description_raw");
            values.add(new TransactionColumnValue("description_raw", raw));
            if (!body.has("description_normalized")) {
                values.add(new TransactionColumnValue(
                        "description_normalized",
                        raw == null ? null : TransactionJson.normalizeDescription(raw)));
            }
        }
        addText(values, body, "description_normalized");
        addText(values, body, "merchant");
        addBigDecimal(values, body, "amount");
        if (body.has("category_id")) {
            Long categoryId = TransactionJson.optionalLong(body, "category_id");
            TransactionCategoryInfo category = categoryId == null ? null : categoryOr404(connection, categoryId);
            values.add(new TransactionColumnValue("category_id", categoryId));
            values.add(new TransactionColumnValue("is_user_categorized", true));
            values.add(new TransactionColumnValue("matched_rule_id", null));
            if (category != null && !body.has("kind")) {
                values.add(new TransactionColumnValue("kind", category.kind()));
            }
        }
        if (body.has("kind")) {
            values.add(new TransactionColumnValue("kind", TransactionJson.textOrNull(body, "kind")));
            values.add(new TransactionColumnValue("is_user_categorized", true));
            values.add(new TransactionColumnValue("matched_rule_id", null));
        }
        addBoolean(values, body, "is_excluded_from_totals");
        addText(values, body, "notes");
        addLong(values, body, "transfer_pair_id");
        return values;
    }

    private void applyBulkUpdate(
            Connection connection,
            long transactionId,
            JsonNode body,
            TransactionCategoryInfo category) throws SQLException {
        List<TransactionColumnValue> values = new ArrayList<>();
        if (category != null) {
            values.add(new TransactionColumnValue("category_id", category.id()));
            values.add(new TransactionColumnValue("is_user_categorized", true));
            values.add(new TransactionColumnValue("matched_rule_id", null));
            if (!body.has("kind")) {
                values.add(new TransactionColumnValue("kind", category.kind()));
            }
        }
        if (body.has("kind") && !body.get("kind").isNull()) {
            values.add(new TransactionColumnValue("kind", body.get("kind").asText()));
            values.add(new TransactionColumnValue("is_user_categorized", true));
            values.add(new TransactionColumnValue("matched_rule_id", null));
        }
        addBoolean(values, body, "is_excluded_from_totals");

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

    private Set<Long> existingTransactions(Connection connection, List<Long> ids) throws SQLException {
        Set<Long> found = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM transactions WHERE id IN (%s)
                """.formatted(TransactionSql.placeholders(ids.size())))) {
            TransactionSql.bindParams(statement, new ArrayList<>(ids), 1);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getLong("id"));
                }
            }
        }
        return found;
    }

    private TransactionCategoryInfo categoryOr404(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, kind FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
                }
                return new TransactionCategoryInfo(rs.getLong("id"), rs.getString("kind"));
            }
        }
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

    private void requireTransaction(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "transaction not found");
                }
            }
        }
    }

    private Long transferPairId(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT transfer_pair_id FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "transaction not found");
                }
                long pairId = rs.getLong("transfer_pair_id");
                return rs.wasNull() ? null : pairId;
            }
        }
    }

    private void addText(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJson.textOrNull(body, field)));
        }
    }

    private void addDate(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJson.optionalDateString(body, field)));
        }
    }

    private void addBigDecimal(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new TransactionColumnValue(
                    field,
                    node == null || node.isNull() ? null : new BigDecimal(node.asText())));
        }
    }

    private void addBoolean(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new TransactionColumnValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }

    private void addLong(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJson.optionalLong(body, field)));
        }
    }
}

record TransactionCategoryInfo(long id, String kind) {
}

record TransactionColumnValue(String column, Object value) {
}
