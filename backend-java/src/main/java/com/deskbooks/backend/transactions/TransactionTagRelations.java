package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class TransactionTagRelations {
    void applyBulkChanges(Connection connection, long transactionId, JsonNode body) throws SQLException {
        addTags(connection, transactionId, TransactionJsonValues.longList(body.get("add_tag_ids")));
        removeTags(connection, transactionId, TransactionJsonValues.longList(body.get("remove_tag_ids")));
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
        TransactionTouches.touch(connection, transactionId);
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
        TransactionTouches.touch(connection, transactionId);
    }
}
