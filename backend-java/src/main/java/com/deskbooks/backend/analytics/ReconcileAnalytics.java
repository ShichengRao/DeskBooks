package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class ReconcileAnalytics {
    private ReconcileAnalytics() {
    }

    static AnalyticsController.ReconcileResponse load(
            Connection connection,
            long accountId,
            Integer year,
            Integer month,
            LocalDate start,
            LocalDate end) throws SQLException {
        if (start != null || end != null) {
            validatePeriod(start, end);
            return accountPeriod(connection, accountId, start, end, null, null);
        }
        if (year == null || month == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "provide either year/month or start/end");
        }
        return accountMonth(connection, accountId, year, month);
    }

    static AnalyticsController.ReconcileResponse upsert(
            Connection connection,
            AnalyticsController.ReconcileRequest body) throws SQLException {
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
            statement.setBigDecimal(4, body.statementTotal() == null ? null : money(body.statementTotal()));
            statement.setString(5, body.notes());
            statement.executeUpdate();
        }
        return accountMonth(connection, body.accountId(), body.year(), body.month());
    }

    private static void validatePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "provide both start and end");
        }
        if (end.isBefore(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }
    }

    private static AnalyticsController.ReconcileResponse accountMonth(
            Connection connection,
            long accountId,
            int year,
            int month) throws SQLException {
        YearMonth yearMonth = YearMonth.of(year, month);
        return accountPeriod(
                connection,
                accountId,
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth(),
                year,
                month);
    }

    private static AnalyticsController.ReconcileResponse accountPeriod(
            Connection connection,
            long accountId,
            LocalDate start,
            LocalDate end,
            Integer year,
            Integer month) throws SQLException {
        PeriodTotals totals = periodTotals(connection, accountId, start, end);
        ReconciliationRow reconciliation = year == null || month == null
                ? null
                : reconciliation(connection, accountId, year, month);
        BigDecimal statementTotal = reconciliation == null ? null : reconciliation.statementTotal();
        BigDecimal delta = statementTotal == null ? null : totals.total().subtract(statementTotal);
        return new AnalyticsController.ReconcileResponse(
                accountId,
                year,
                month,
                start,
                end,
                totals.transactionCount(),
                moneyString(totals.total()),
                moneyString(totals.inflows()),
                moneyString(totals.outflows()),
                stringifyMoney(totals.byKind()),
                moneyStringOrNull(statementTotal),
                reconciliation == null ? null : reconciliation.notes(),
                moneyStringOrNull(delta));
    }

    private static PeriodTotals periodTotals(
            Connection connection,
            long accountId,
            LocalDate start,
            LocalDate end) throws SQLException {
        PeriodAccumulator totals = new PeriodAccumulator();
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

    private static ReconciliationRow reconciliation(Connection connection, long accountId, int year, int month)
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
                return new ReconciliationRow(rs.getBigDecimal("statement_total"), rs.getString("notes"));
            }
        }
    }

    private static Map<String, String> stringifyMoney(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            out.put(entry.getKey(), moneyString(entry.getValue()));
        }
        return out;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private static String moneyStringOrNull(BigDecimal value) {
        return value == null ? null : moneyString(value);
    }

    private record ReconciliationRow(BigDecimal statementTotal, String notes) {
    }

    private record PeriodTotals(
            Map<String, BigDecimal> byKind,
            BigDecimal total,
            BigDecimal inflows,
            BigDecimal outflows,
            int transactionCount) {
    }

    private static final class PeriodAccumulator {
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

        PeriodTotals snapshot() {
            return new PeriodTotals(byKind, total, inflows, outflows, transactionCount);
        }
    }
}
