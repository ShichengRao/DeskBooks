package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class RuleLookup {
    void validateReferences(Connection connection, Long accountId, Long categoryId) throws SQLException {
        if (accountId != null) {
            requireAccount(connection, accountId);
        }
        if (categoryId != null) {
            requireCategory(connection, categoryId);
        }
    }

    void requireRule(Connection connection, long ruleId) throws SQLException {
        requireExists(connection, "rules", ruleId, "rule not found");
    }

    private void requireAccount(Connection connection, long accountId) throws SQLException {
        requireExists(connection, "accounts", accountId, "account not found");
    }

    private void requireCategory(Connection connection, long categoryId) throws SQLException {
        requireExists(connection, "categories", categoryId, "category not found");
    }

    private void requireExists(Connection connection, String table, long id, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, message);
                }
            }
        }
    }
}
