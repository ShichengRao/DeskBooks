package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class NetWorthReader {
    List<NetWorthController.NetWorthSnapshotResponse> list(Connection connection) throws SQLException {
        List<NetWorthController.NetWorthSnapshotResponse> snapshots = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_date, notes
                FROM net_worth_snapshots
                ORDER BY snapshot_date DESC
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                snapshots.add(snapshotFrom(connection, rs));
            }
        }
        return snapshots;
    }

    NetWorthController.NetWorthSnapshotResponse get(Connection connection, long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_date, notes FROM net_worth_snapshots WHERE id = ?
                """)) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "snapshot not found");
                }
                return snapshotFrom(connection, rs);
            }
        }
    }

    private NetWorthController.NetWorthSnapshotResponse snapshotFrom(Connection connection, ResultSet rs)
            throws SQLException {
        long snapshotId = rs.getLong("id");
        return new NetWorthController.NetWorthSnapshotResponse(
                snapshotId,
                LocalDate.parse(rs.getString("snapshot_date")),
                rs.getString("notes"),
                balances(connection, snapshotId));
    }

    private List<NetWorthController.AccountBalanceResponse> balances(Connection connection, long snapshotId)
            throws SQLException {
        List<NetWorthController.AccountBalanceResponse> balances = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, balance, notes
                FROM account_balances
                WHERE snapshot_id = ?
                ORDER BY account_id
                """)) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    BigDecimal balance = rs.getBigDecimal("balance");
                    balances.add(new NetWorthController.AccountBalanceResponse(
                            rs.getLong("account_id"),
                            balance == null ? null : NetWorthMoney.format(balance),
                            rs.getString("notes")));
                }
            }
        }
        return balances;
    }
}
