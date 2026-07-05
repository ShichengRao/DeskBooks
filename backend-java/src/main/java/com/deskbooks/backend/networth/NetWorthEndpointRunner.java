package com.deskbooks.backend.networth;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class NetWorthEndpointRunner {
    private final SqliteConnectionProvider connections;

    NetWorthEndpointRunner(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    <T> T run(NetWorthConnectionAction<T> action) {
        try (Connection connection = connections.open()) {
            return action.run(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    <T> T runWorkbookImport(NetWorthWorkbookImportAction<T> action) {
        try (Connection connection = connections.open()) {
            return action.run(connection);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read workbook");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    @FunctionalInterface
    interface NetWorthConnectionAction<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface NetWorthWorkbookImportAction<T> {
        T run(Connection connection) throws SQLException, IOException;
    }
}
