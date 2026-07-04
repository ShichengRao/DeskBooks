package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class ReconcileAnalytics {
    private ReconcileAnalytics() {
    }

    static ReconcileResponse load(
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

    static ReconcileResponse upsert(
            Connection connection,
            ReconcileRequest body) throws SQLException {
        ReconcileStatementStore.upsert(connection, body);
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

    private static ReconcileResponse accountMonth(
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

    private static ReconcileResponse accountPeriod(
            Connection connection,
            long accountId,
            LocalDate start,
            LocalDate end,
            Integer year,
            Integer month) throws SQLException {
        ReconcilePeriodTotals totals = ReconcilePeriodTotals.load(connection, accountId, start, end);
        ReconcileStatement reconciliation = year == null || month == null
                ? null
                : ReconcileStatementStore.find(connection, accountId, year, month);
        BigDecimal statementTotal = reconciliation == null ? null : reconciliation.statementTotal();
        BigDecimal delta = statementTotal == null ? null : totals.total().subtract(statementTotal);
        return new ReconcileResponse(
                accountId,
                year,
                month,
                start,
                end,
                totals.transactionCount(),
                ReconcileMoney.moneyString(totals.total()),
                ReconcileMoney.moneyString(totals.inflows()),
                ReconcileMoney.moneyString(totals.outflows()),
                ReconcileMoney.stringify(totals.byKind()),
                ReconcileMoney.moneyStringOrNull(statementTotal),
                reconciliation == null ? null : reconciliation.notes(),
                ReconcileMoney.moneyStringOrNull(delta));
    }
}
