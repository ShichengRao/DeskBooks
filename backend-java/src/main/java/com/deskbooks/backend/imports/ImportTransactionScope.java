package com.deskbooks.backend.imports;

import java.sql.Connection;
import java.sql.SQLException;

final class ImportTransactionScope {
    private ImportTransactionScope() {
    }

    static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
