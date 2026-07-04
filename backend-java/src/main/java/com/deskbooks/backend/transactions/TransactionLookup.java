package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class TransactionLookup {
    void requireAccount(Connection connection, long accountId) throws SQLException {
        requireExisting(connection, "SELECT 1 FROM accounts WHERE id = ?", accountId, "account not found");
    }

    void requireTransaction(Connection connection, long transactionId) throws SQLException {
        requireExisting(connection, "SELECT 1 FROM transactions WHERE id = ?", transactionId, "transaction not found");
    }

    Set<Long> existingTransactions(Connection connection, List<Long> ids) throws SQLException {
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

    TransactionCategoryInfo categoryOr404(Connection connection, long categoryId) throws SQLException {
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

    Long transferPairId(Connection connection, long transactionId) throws SQLException {
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

    private void requireExisting(Connection connection, String sql, long id, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, message);
                }
            }
        }
    }
}
