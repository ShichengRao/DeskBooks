package com.deskbooks.backend.networth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

final class NetWorthSnapshotStore {
    private NetWorthSnapshotStore() {
    }

    static long insert(Connection connection, LocalDate snapshotDate, String notes) throws SQLException {
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

    static Set<LocalDate> dates(Connection connection) throws SQLException {
        Set<LocalDate> out = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_date FROM net_worth_snapshots");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(LocalDate.parse(rs.getString("snapshot_date")));
            }
        }
        return out;
    }
}
