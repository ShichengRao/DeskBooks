package com.deskbooks.backend.networth;

import java.sql.Connection;
import java.sql.SQLException;
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

    NetWorthSnapshotResponse create(
            Connection connection,
            NetWorthSnapshotRequest body) throws SQLException {
        if (NetWorthSnapshotStore.dateExists(connection, body.snapshotDate(), null)) {
            throw new ApiException(HttpStatus.CONFLICT, "snapshot for this date already exists");
        }

        try {
            connection.setAutoCommit(false);
            long snapshotId = NetWorthSnapshotStore.insert(connection, body.snapshotDate(), body.notes());
            balances.upsert(connection, snapshotId, body.balances() == null ? List.of() : body.balances());
            connection.commit();
            return reader.get(connection, snapshotId);
        } catch (SQLException exception) {
            rollback(connection);
            throw exception;
        }
    }

    NetWorthSnapshotResponse update(Connection connection, long snapshotId, JsonNode body)
            throws SQLException {
        requireSnapshot(connection, snapshotId);
        NetWorthSnapshotPatch patch = balances.patchFromJson(body);
        if (patch.hasSnapshotDate() && NetWorthSnapshotStore.dateExists(connection, patch.snapshotDate(), snapshotId)) {
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
        NetWorthSnapshotStore.delete(connection, snapshotId);
        return Map.of("status", "deleted");
    }

    private void applyPatch(Connection connection, long snapshotId, NetWorthSnapshotPatch patch) throws SQLException {
        if (patch.hasSnapshotDate()) {
            NetWorthSnapshotStore.updateDate(connection, snapshotId, patch.snapshotDate());
        }
        if (patch.hasNotes()) {
            NetWorthSnapshotStore.updateNotes(connection, snapshotId, patch.notes());
        }
        if (patch.hasBalances()) {
            balances.replace(connection, snapshotId, patch.balances());
        }
    }

    private void requireSnapshot(Connection connection, long snapshotId) throws SQLException {
        if (!NetWorthSnapshotStore.exists(connection, snapshotId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "snapshot not found");
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
