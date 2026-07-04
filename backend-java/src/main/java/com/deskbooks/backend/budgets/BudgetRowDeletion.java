package com.deskbooks.backend.budgets;

import com.deskbooks.backend.foundation.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.http.HttpStatus;

final class BudgetRowDeletion {
    private final String table;
    private final String missingMessage;

    BudgetRowDeletion(String table, String missingMessage) {
        this.table = table;
        this.missingMessage = missingMessage;
    }

    void delete(Connection connection, long id) throws SQLException {
        requireExisting(connection, id);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private void requireExisting(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, missingMessage);
                }
            }
        }
    }
}
