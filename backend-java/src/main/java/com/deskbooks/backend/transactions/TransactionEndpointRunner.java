package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.SQLException;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class TransactionEndpointRunner {
    private final SqliteConnectionProvider connections;

    TransactionEndpointRunner(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    <T> T run(TransactionConnectionAction<T> action) {
        try (Connection connection = connections.open()) {
            return action.run(connection);
        } catch (SQLException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    @FunctionalInterface
    interface TransactionConnectionAction<T> {
        T run(Connection connection) throws SQLException;
    }
}
