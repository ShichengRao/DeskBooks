package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

final class TransactionMutations {
    private final TransactionReader reader;
    private final TransactionRelations relations;
    private final TransactionLookup lookup = new TransactionLookup();
    private final TransactionPatchBuilder patches = new TransactionPatchBuilder(lookup);
    private final TransactionTransferMutations transfers = new TransactionTransferMutations(lookup);
    private final TransactionUpdateWriter writer = new TransactionUpdateWriter();
    private final TransactionBulkMutations bulk;

    TransactionMutations(TransactionReader reader, TransactionRelations relations) {
        this.reader = reader;
        this.relations = relations;
        bulk = new TransactionBulkMutations(lookup, patches, relations, writer);
    }

    TransactionResponse create(Connection connection, JsonNode body) throws SQLException {
        TransactionCreatePayload payload = TransactionCreatePayload.from(connection, body, lookup);

        String sql = """
                INSERT INTO transactions (
                  account_id, date, post_date, description_raw, description_normalized,
                  merchant, amount, category_id, kind, is_user_categorized,
                  is_excluded_from_totals, notes, matched_rule_id, raw, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            payload.bind(statement);
            statement.setString(12, "{\"source\":\"manual\"}");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return reader.get(connection, keys.getLong(1));
            }
        }
    }

    TransactionResponse setSplit(Connection connection, long transactionId, JsonNode body)
            throws SQLException {
        lookup.requireTransaction(connection, transactionId);
        relations.setSplit(connection, transactionId, body);
        return reader.get(connection, transactionId);
    }

    Map<String, Integer> bulkUpdate(Connection connection, JsonNode body) throws SQLException {
        return bulk.bulkUpdate(connection, body);
    }

    TransactionResponse update(Connection connection, long transactionId, JsonNode body)
            throws SQLException {
        lookup.requireTransaction(connection, transactionId);
        List<TransactionColumnValue> values = patches.patchValues(connection, body);
        if (!values.isEmpty()) {
            writer.update(connection, transactionId, values);
        }
        return reader.get(connection, transactionId);
    }

    Map<String, String> pair(Connection connection, JsonNode body) throws SQLException {
        return transfers.pair(connection, body);
    }

    Map<String, String> unpair(Connection connection, long transactionId) throws SQLException {
        return transfers.unpair(connection, transactionId);
    }

    Map<String, String> delete(Connection connection, long transactionId) throws SQLException {
        lookup.requireTransaction(connection, transactionId);
        transfers.unlinkPairedTransaction(connection, transactionId);
        writer.delete(connection, transactionId);
        return Map.of("status", "deleted");
    }

}

record TransactionCategoryInfo(long id, String kind) {
}

record TransactionColumnValue(String column, Object value) {
}
