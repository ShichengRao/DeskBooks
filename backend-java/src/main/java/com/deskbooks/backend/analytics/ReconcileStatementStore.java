package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class ReconcileStatementStore {
    private ReconcileStatementStore() {
    }

    static void upsert(Connection connection, ReconcileRequest body) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO monthly_reconciliations (account_id, year, month, statement_total, notes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(account_id, year, month) DO UPDATE SET
                  statement_total = excluded.statement_total,
                  notes = excluded.notes,
                  updated_at = CURRENT_TIMESTAMP
                """)) {
            statement.setLong(1, body.accountId());
            statement.setInt(2, body.year());
            statement.setInt(3, body.month());
            statement.setBigDecimal(
                    4,
                    body.statementTotal() == null ? null : ReconcileMoney.money(body.statementTotal()));
            statement.setString(5, body.notes());
            statement.executeUpdate();
        }
    }

    static ReconcileStatement find(Connection connection, long accountId, int year, int month)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT statement_total, notes
                FROM monthly_reconciliations
                WHERE account_id = ? AND year = ? AND month = ?
                """)) {
            statement.setLong(1, accountId);
            statement.setInt(2, year);
            statement.setInt(3, month);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ReconcileStatement(rs.getBigDecimal("statement_total"), rs.getString("notes"));
            }
        }
    }
}

record ReconcileStatement(
        BigDecimal statementTotal,
        String notes) {
}
