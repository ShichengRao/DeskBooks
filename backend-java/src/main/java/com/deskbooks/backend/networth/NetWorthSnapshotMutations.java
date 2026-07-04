package com.deskbooks.backend.networth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class NetWorthSnapshotMutations {
    private final NetWorthReader reader;
    private final NetWorthBalanceStore balances = new NetWorthBalanceStore();

    NetWorthSnapshotMutations(NetWorthReader reader) {
        this.reader = reader;
    }

    NetWorthController.NetWorthSnapshotResponse create(
            Connection connection,
            NetWorthController.NetWorthSnapshotRequest body) throws SQLException {
        if (snapshotDateExists(connection, body.snapshotDate(), null)) {
            throw new ApiException(HttpStatus.CONFLICT, "snapshot for this date already exists");
        }

        try {
            connection.setAutoCommit(false);
            long snapshotId = insertSnapshot(connection, body.snapshotDate(), body.notes());
            balances.upsert(connection, snapshotId, body.balances() == null ? List.of() : body.balances());
            connection.commit();
            return reader.get(connection, snapshotId);
        } catch (SQLException exception) {
            rollback(connection);
            throw exception;
        }
    }

    NetWorthController.NetWorthSnapshotResponse update(Connection connection, long snapshotId, JsonNode body)
            throws SQLException {
        requireSnapshot(connection, snapshotId);
        NetWorthSnapshotPatch patch = balances.patchFromJson(body);
        if (patch.hasSnapshotDate() && snapshotDateExists(connection, patch.snapshotDate(), snapshotId)) {
            throw new ApiException(HttpStatus.CONFLICT, "snapshot for this date already exists");
        }

        try {
            connection.setAutoCommit(false);
            applyPatch(connection, snapshotId, patch);
            connection.commit();
            return reader.get(connection, snapshotId);
        } catch (SQLException exception) {
            rollback(connection);
            throw exception;
        }
    }

    Map<String, String> delete(Connection connection, long snapshotId) throws SQLException {
        requireSnapshot(connection, snapshotId);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM net_worth_snapshots WHERE id = ?")) {
            statement.setLong(1, snapshotId);
            statement.executeUpdate();
        }
        return Map.of("status", "deleted");
    }

    private void applyPatch(Connection connection, long snapshotId, NetWorthSnapshotPatch patch) throws SQLException {
        if (patch.hasSnapshotDate()) {
            updateSnapshotDate(connection, snapshotId, patch.snapshotDate());
        }
        if (patch.hasNotes()) {
            updateSnapshotNotes(connection, snapshotId, patch.notes());
        }
        if (patch.hasBalances()) {
            balances.replace(connection, snapshotId, patch.balances());
        }
    }

    private long insertSnapshot(Connection connection, LocalDate snapshotDate, String notes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO net_worth_snapshots (snapshot_date, notes)
                VALUES (?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, snapshotDate.toString());
            statement.setString(2, notes);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void updateSnapshotDate(Connection connection, long snapshotId, LocalDate snapshotDate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE net_worth_snapshots SET snapshot_date = ? WHERE id = ?
                """)) {
            statement.setString(1, snapshotDate.toString());
            statement.setLong(2, snapshotId);
            statement.executeUpdate();
        }
    }

    private void updateSnapshotNotes(Connection connection, long snapshotId, String notes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE net_worth_snapshots SET notes = ? WHERE id = ?
                """)) {
            statement.setString(1, notes);
            statement.setLong(2, snapshotId);
            statement.executeUpdate();
        }
    }

    private boolean snapshotDateExists(Connection connection, LocalDate snapshotDate, Long excludedSnapshotId)
            throws SQLException {
        String excludedClause = excludedSnapshotId == null ? "" : " AND id <> ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM net_worth_snapshots WHERE snapshot_date = ?" + excludedClause)) {
            statement.setString(1, snapshotDate.toString());
            if (excludedSnapshotId != null) {
                statement.setLong(2, excludedSnapshotId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void requireSnapshot(Connection connection, long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM net_worth_snapshots WHERE id = ?")) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "snapshot not found");
                }
            }
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original exception carries the actionable failure.
        }
    }
}
