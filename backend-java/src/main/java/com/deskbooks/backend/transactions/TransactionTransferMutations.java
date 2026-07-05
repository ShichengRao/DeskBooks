package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class TransactionTransferMutations {
    private final TransactionLookup lookup;

    TransactionTransferMutations(TransactionLookup lookup) {
        this.lookup = lookup;
    }

    Map<String, String> pair(Connection connection, JsonNode body) throws SQLException {
        long transactionAId = TransactionJsonValues.requiredLong(body, "transaction_a_id");
        long transactionBId = TransactionJsonValues.requiredLong(body, "transaction_b_id");
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
        clearTransferPair(connection, transactionId, pairId);
        return Map.of("status", "unpaired");
    }

    void unlinkPairedTransaction(Connection connection, long transactionId) throws SQLException {
        Long pairId = lookup.transferPairId(connection, transactionId);
        if (pairId != null) {
            clearTransferPair(connection, pairId);
        }
    }

    private void clearTransferPair(Connection connection, long transactionId, long pairId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions SET transfer_pair_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id IN (?, ?)
                """)) {
            statement.setLong(1, transactionId);
            statement.setLong(2, pairId);
            statement.executeUpdate();
        }
    }

    private void clearTransferPair(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions SET transfer_pair_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """)) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
    }
}
