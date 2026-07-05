package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

final class TransactionUpdateWriter {
    void update(
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

    void delete(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
    }
}
