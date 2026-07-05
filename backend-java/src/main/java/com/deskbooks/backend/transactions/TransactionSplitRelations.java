package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import tools.jackson.databind.JsonNode;

final class TransactionSplitRelations {
    void setSplit(Connection connection, long transactionId, JsonNode body) throws SQLException {
        SplitChange change = splitChange(body, "group_name", "personal_share", "notes");
        if (change.groupName() == null) {
            clearSplit(connection, transactionId);
        } else {
            upsertSplit(connection, transactionId, change);
        }
        TransactionTouches.touch(connection, transactionId);
    }

    void applyBulkChanges(Connection connection, long transactionId, JsonNode body) throws SQLException {
        if (body.has("clear_split") && body.get("clear_split").asBoolean()) {
            clearSplit(connection, transactionId);
            TransactionTouches.touch(connection, transactionId);
        } else if (body.has("split_group_name") && !body.get("split_group_name").isNull()) {
            SplitChange change = splitChange(body, "split_group_name", "split_personal_share", "split_notes");
            if (change.groupName() != null) {
                upsertSplit(connection, transactionId, change);
                TransactionTouches.touch(connection, transactionId);
            }
        }
    }

    private void upsertSplit(Connection connection, long transactionId, SplitChange change) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_splits (transaction_id, group_name, personal_share, notes)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(transaction_id) DO UPDATE SET
                  group_name = excluded.group_name,
                  personal_share = excluded.personal_share,
                  notes = excluded.notes
                """)) {
            statement.setLong(1, transactionId);
            statement.setString(2, change.groupName());
            statement.setBigDecimal(3, change.personalShare());
            statement.setString(4, change.notes());
            statement.executeUpdate();
        }
    }

    private void clearSplit(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM transaction_splits WHERE transaction_id = ?")) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
    }

    private SplitChange splitChange(JsonNode body, String groupField, String shareField, String notesField) {
        return new SplitChange(
                blankToNull(body, groupField),
                clampedShare(body, shareField),
                blankToNull(body, notesField));
    }

    private BigDecimal clampedShare(JsonNode body, String field) {
        JsonNode node = body.get(field);
        BigDecimal value = node == null || node.isNull() ? new BigDecimal("0.5") : new BigDecimal(node.asText());
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }

    private String blankToNull(JsonNode body, String field) {
        return TransactionJsonText.blankToNull(TransactionJsonText.orNull(body, field));
    }

    private record SplitChange(String groupName, BigDecimal personalShare, String notes) {
    }
}
