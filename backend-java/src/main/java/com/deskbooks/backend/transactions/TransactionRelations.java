package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.SQLException;

import tools.jackson.databind.JsonNode;

final class TransactionRelations {
    private final TransactionSplitRelations splits = new TransactionSplitRelations();
    private final TransactionTagRelations tags = new TransactionTagRelations();

    void setSplit(Connection connection, long transactionId, JsonNode body) throws SQLException {
        splits.setSplit(connection, transactionId, body);
    }

    void applyBulkChanges(Connection connection, long transactionId, JsonNode body) throws SQLException {
        splits.applyBulkChanges(connection, transactionId, body);
        tags.applyBulkChanges(connection, transactionId, body);
    }
}
