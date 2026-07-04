package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

record ReconcilePeriodTotals(
        Map<String, BigDecimal> byKind,
        BigDecimal total,
        BigDecimal inflows,
        BigDecimal outflows,
        int transactionCount) {

    static ReconcilePeriodTotals load(
            Connection connection,
            long accountId,
            LocalDate start,
            LocalDate end) throws SQLException {
        Accumulator totals = new Accumulator();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT amount, kind
                FROM transactions
                WHERE account_id = ?
                  AND date >= ?
                  AND date <= ?
                  AND is_excluded_from_totals = 0
                """)) {
            statement.setLong(1, accountId);
            statement.setString(2, start.toString());
            statement.setString(3, end.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    totals.add(rs.getBigDecimal("amount"), rs.getString("kind"));
                }
            }
        }
        return totals.snapshot();
    }

    private static final class Accumulator {
        private final Map<String, BigDecimal> byKind = new LinkedHashMap<>();
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal inflows = BigDecimal.ZERO;
        private BigDecimal outflows = BigDecimal.ZERO;
        private int transactionCount = 0;

        void add(BigDecimal amount, String kind) {
            transactionCount++;
            byKind.merge(kind, amount, BigDecimal::add);
            total = total.add(amount);
            if (amount.compareTo(BigDecimal.ZERO) >= 0) {
                inflows = inflows.add(amount);
            } else {
                outflows = outflows.add(amount);
            }
        }

        ReconcilePeriodTotals snapshot() {
            return new ReconcilePeriodTotals(byKind, total, inflows, outflows, transactionCount);
        }
    }
}
