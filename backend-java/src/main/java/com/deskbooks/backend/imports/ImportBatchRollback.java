package com.deskbooks.backend.imports;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class ImportBatchRollback {
    private final ImportBatchMetadata metadata;
    private final ImportTransactionStore transactions;

    ImportBatchRollback(ImportBatchMetadata metadata, ImportTransactionStore transactions) {
        this.metadata = metadata;
        this.transactions = transactions;
    }

    Map<String, String> rollback(Connection connection, long batchId) throws SQLException {
        ImportController.ImportBatchResponse batch = metadata.get(connection, batchId);
        if (!"applied".equals(batch.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "batch is not in 'applied' state");
        }

        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            transactions.deleteBatch(connection, batchId);
            metadata.markRolledBack(connection, batchId);
            connection.commit();
            committed = true;
            return Map.of("status", "rolled_back");
        } finally {
            if (!committed) {
                ImportTransactionScope.rollback(connection);
            }
        }
    }
}
