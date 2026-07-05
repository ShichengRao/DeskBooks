package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class TransactionTouches {
    private TransactionTouches() {
    }

    static void touch(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """)) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
    }
}
