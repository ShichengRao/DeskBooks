package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class TransactionRelations {
    void setSplit(Connection connection, long transactionId, JsonNode body) throws SQLException {
        String groupName = blankToNull(body.get("group_name"));
        if (groupName == null) {
            clearSplit(connection, transactionId);
        } else {
            upsertSplit(connection, transactionId, groupName, clampedShare(body, "personal_share"), blankToNull(body.get("notes")));
        }
        touchTransaction(connection, transactionId);
    }

    void applyBulkChanges(Connection connection, long transactionId, JsonNode body) throws SQLException {
        if (body.has("clear_split") && body.get("clear_split").asBoolean()) {
            clearSplit(connection, transactionId);
            touchTransaction(connection, transactionId);
        } else if (body.has("split_group_name") && !body.get("split_group_name").isNull()) {
            String groupName = blankToNull(body.get("split_group_name"));
            if (groupName != null) {
                upsertSplit(
                        connection,
                        transactionId,
                        groupName,
                        clampedShare(body, "split_personal_share"),
                        blankToNull(body.get("split_notes")));
                touchTransaction(connection, transactionId);
            }
        }
        addTags(connection, transactionId, longList(body.get("add_tag_ids")));
        removeTags(connection, transactionId, longList(body.get("remove_tag_ids")));
    }

    private void upsertSplit(
            Connection connection, long transactionId, String groupName, BigDecimal personalShare, String notes)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_splits (transaction_id, group_name, personal_share, notes)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(transaction_id) DO UPDATE SET
                  group_name = excluded.group_name,
                  personal_share = excluded.personal_share,
                  notes = excluded.notes
                """)) {
            statement.setLong(1, transactionId);
            statement.setString(2, groupName);
            statement.setBigDecimal(3, personalShare);
            statement.setString(4, notes);
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

    private void addTags(Connection connection, long transactionId, List<Long> tagIds) throws SQLException {
        if (tagIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO transaction_tags (transaction_id, tag_id)
                SELECT ?, id FROM tags WHERE id = ?
                """)) {
            for (Long tagId : tagIds) {
                statement.setLong(1, transactionId);
                statement.setLong(2, tagId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        touchTransaction(connection, transactionId);
    }

    private void removeTags(Connection connection, long transactionId, List<Long> tagIds) throws SQLException {
        if (tagIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM transaction_tags WHERE transaction_id = ? AND tag_id = ?
                """)) {
            for (Long tagId : tagIds) {
                statement.setLong(1, transactionId);
                statement.setLong(2, tagId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        touchTransaction(connection, transactionId);
    }

    private void touchTransaction(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """)) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
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

    private List<Long> longList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asLong());
        }
        return values;
    }

    private String blankToNull(JsonNode node) {
        String value = node == null || node.isNull() ? null : node.asText();
        return value == null || value.isBlank() ? null : value;
    }
}
