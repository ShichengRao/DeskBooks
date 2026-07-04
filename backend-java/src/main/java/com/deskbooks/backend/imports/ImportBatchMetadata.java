package com.deskbooks.backend.imports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class ImportBatchMetadata {
    long create(Connection connection, ImportController.ImportApplyRequest body) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_batches (
                  source_filename, importer_name, account_id, row_count_total,
                  row_count_applied, row_count_duplicate, status
                ) VALUES (?, ?, ?, ?, 0, 0, 'applied')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, body.sourceFilename());
            statement.setString(2, body.importerName());
            statement.setLong(3, body.accountId());
            statement.setInt(4, body.rows().size());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    void updateCounts(Connection connection, long batchId, ImportBatchStore.ImportCounts counts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE import_batches
                SET row_count_applied = ?, row_count_duplicate = ?
                WHERE id = ?
                """)) {
            statement.setInt(1, counts.applied());
            statement.setInt(2, counts.duplicates());
            statement.setLong(3, batchId);
            statement.executeUpdate();
        }
    }

    ImportController.ImportBatchResponse get(Connection connection, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, source_filename, importer_name, account_id, imported_at,
                       row_count_total, row_count_applied, row_count_duplicate, status, notes
                FROM import_batches
                WHERE id = ?
                """)) {
            statement.setLong(1, batchId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "import batch not found");
                }
                return batchFrom(rs);
            }
        }
    }

    void markRolledBack(Connection connection, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE import_batches SET status = 'rolled_back' WHERE id = ?")) {
            statement.setLong(1, batchId);
            statement.executeUpdate();
        }
    }

    ImportController.ImportBatchResponse batchFrom(ResultSet rs) throws SQLException {
        return new ImportController.ImportBatchResponse(
                rs.getLong("id"),
                rs.getString("source_filename"),
                rs.getString("importer_name"),
                rs.getLong("account_id"),
                ImportSqlValues.localDateTime(rs.getString("imported_at")),
                rs.getInt("row_count_total"),
                rs.getInt("row_count_applied"),
                rs.getInt("row_count_duplicate"),
                rs.getString("status"),
                rs.getString("notes"));
    }
}
