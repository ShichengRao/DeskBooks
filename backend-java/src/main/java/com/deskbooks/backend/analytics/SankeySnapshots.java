package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

final class SankeySnapshots {
    private static final int SNAPSHOT_BRACKET_DAYS = 60;
    private static final String ASCENDING = "ASC";
    private static final String DESCENDING = "DESC";

    SnapshotRef bracketingStart(Connection connection, LocalDate start) throws SQLException {
        SnapshotRef snapshot = nearest(connection, start);
        return snapshot == null ? first(connection) : snapshot;
    }

    SnapshotRef bracketingEnd(Connection connection, LocalDate endAnchor) throws SQLException {
        SnapshotRef snapshot = nearest(connection, endAnchor);
        return snapshot == null ? last(connection) : snapshot;
    }

    Map<Long, BigDecimal> balances(Connection connection, SnapshotRef snapshot) throws SQLException {
        if (snapshot == null) {
            return Map.of();
        }
        Map<Long, BigDecimal> out = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, balance
                FROM account_balances
                WHERE snapshot_id = ?
                  AND balance IS NOT NULL
                """)) {
            statement.setLong(1, snapshot.id());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong("account_id"), rs.getBigDecimal("balance"));
                }
            }
        }
        return out;
    }

    private SnapshotRef nearest(Connection connection, LocalDate target) throws SQLException {
        LocalDate earliest = target.minusDays(SNAPSHOT_BRACKET_DAYS);
        LocalDate latest = target.plusDays(SNAPSHOT_BRACKET_DAYS);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_date
                FROM net_worth_snapshots
                WHERE snapshot_date >= ?
                  AND snapshot_date <= ?
                """)) {
            statement.setString(1, earliest.toString());
            statement.setString(2, latest.toString());
            return nearestFrom(statement, target);
        }
    }

    private SnapshotRef nearestFrom(PreparedStatement statement, LocalDate target) throws SQLException {
        SnapshotRef best = null;
        long bestDistance = Long.MAX_VALUE;
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                SnapshotRef candidate = new SnapshotRef(rs.getLong("id"), LocalDate.parse(rs.getString("snapshot_date")));
                long distance = Math.abs(ChronoUnit.DAYS.between(candidate.snapshotDate(), target));
                if (isBetterNearest(candidate, distance, best, bestDistance)) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean isBetterNearest(
            SnapshotRef candidate,
            long distance,
            SnapshotRef best,
            long bestDistance) {
        return best == null
                || distance < bestDistance
                || (distance == bestDistance && candidate.snapshotDate().isBefore(best.snapshotDate()));
    }

    private SnapshotRef first(Connection connection) throws SQLException {
        return ordered(connection, ASCENDING);
    }

    private SnapshotRef last(Connection connection) throws SQLException {
        return ordered(connection, DESCENDING);
    }

    private SnapshotRef ordered(Connection connection, String direction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_date
                FROM net_worth_snapshots
                ORDER BY snapshot_date %s
                LIMIT 1
                """.formatted(direction));
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new SnapshotRef(rs.getLong("id"), LocalDate.parse(rs.getString("snapshot_date")));
        }
    }
}
