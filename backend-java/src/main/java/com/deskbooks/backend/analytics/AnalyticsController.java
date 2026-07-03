package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
class AnalyticsController {
    private final SqliteConnectionProvider connections;

    AnalyticsController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("/reconcile")
    ReconcileResponse reconcile(
            @RequestParam(name = "account_id") long accountId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try (Connection connection = connections.open()) {
            if (start != null || end != null) {
                if (start == null || end == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "provide both start and end");
                }
                if (end.isBefore(start)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
                }
                return reconcileAccountPeriod(connection, accountId, start, end, null, null);
            }
            if (year == null || month == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "provide either year/month or start/end");
            }
            return reconcileAccountMonth(connection, accountId, year, month);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/reconcile")
    ReconcileResponse upsertReconcile(@Valid @RequestBody ReconcileRequest body) {
        try (Connection connection = connections.open()) {
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
            return reconcileAccountMonth(connection, body.accountId(), body.year(), body.month());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/splits")
    List<SplitGroupSummaryResponse> splitGroups(
            @RequestParam(name = "start") LocalDate start,
            @RequestParam(name = "end") LocalDate end) {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT s.group_name, s.personal_share, t.amount
                        FROM transaction_splits s
                        JOIN transactions t ON t.id = s.transaction_id
                        WHERE t.date >= ?
                          AND t.date <= ?
                          AND t.is_excluded_from_totals = 0
                        """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            Map<String, SplitAccumulator> groups = new TreeMap<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String groupName = rs.getString("group_name");
                    SplitAccumulator group = groups.computeIfAbsent(groupName, ignored -> new SplitAccumulator());
                    group.transactionCount++;
                    BigDecimal amount = rs.getBigDecimal("amount");
                    BigDecimal share = rs.getBigDecimal("personal_share");
                    if (amount.compareTo(BigDecimal.ZERO) < 0) {
                        BigDecimal fullOutflow = amount.negate();
                        BigDecimal personal = fullOutflow.multiply(share);
                        group.sharedOutflows = group.sharedOutflows.add(fullOutflow);
                        group.personalOutflows = group.personalOutflows.add(personal);
                        group.expectedReimbursement = group.expectedReimbursement.add(fullOutflow.subtract(personal));
                    } else if (amount.compareTo(BigDecimal.ZERO) > 0) {
                        group.receivedReimbursement = group.receivedReimbursement.add(amount);
                    }
                }
            }

            List<SplitGroupSummaryResponse> out = new ArrayList<>();
            for (Map.Entry<String, SplitAccumulator> entry : groups.entrySet()) {
                SplitAccumulator group = entry.getValue();
                BigDecimal remaining = group.expectedReimbursement.subtract(group.receivedReimbursement);
                out.add(new SplitGroupSummaryResponse(
                        entry.getKey(),
                        moneyString(group.sharedOutflows),
                        moneyString(group.personalOutflows),
                        moneyString(group.expectedReimbursement),
                        moneyString(group.receivedReimbursement),
                        moneyString(remaining),
                        group.transactionCount));
            }
            return out;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ReconcileResponse reconcileAccountMonth(Connection connection, long accountId, int year, int month) throws SQLException {
        YearMonth yearMonth = YearMonth.of(year, month);
        return reconcileAccountPeriod(
                connection,
                accountId,
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth(),
                year,
                month);
    }

    private ReconcileResponse reconcileAccountPeriod(
            Connection connection,
            long accountId,
            LocalDate start,
            LocalDate end,
            Integer year,
            Integer month) throws SQLException {
        Map<String, BigDecimal> byKind = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal inflows = BigDecimal.ZERO;
        BigDecimal outflows = BigDecimal.ZERO;
        int transactionCount = 0;
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
                    transactionCount++;
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String kind = rs.getString("kind");
                    byKind.merge(kind, amount, BigDecimal::add);
                    total = total.add(amount);
                    if (amount.compareTo(BigDecimal.ZERO) >= 0) {
                        inflows = inflows.add(amount);
                    } else {
                        outflows = outflows.add(amount);
                    }
                }
            }
        }

        ReconciliationRow reconciliation = null;
        if (year != null && month != null) {
            reconciliation = reconciliation(connection, accountId, year, month);
        }
        BigDecimal statementTotal = reconciliation == null ? null : reconciliation.statementTotal();
        BigDecimal delta = statementTotal == null ? null : total.subtract(statementTotal);
        return new ReconcileResponse(
                accountId,
                year,
                month,
                start,
                end,
                transactionCount,
                moneyString(total),
                moneyString(inflows),
                moneyString(outflows),
                stringifyMoney(byKind),
                moneyStringOrNull(statementTotal),
                reconciliation == null ? null : reconciliation.notes(),
                moneyStringOrNull(delta));
    }

    private ReconciliationRow reconciliation(Connection connection, long accountId, int year, int month) throws SQLException {
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

    private Map<String, String> stringifyMoney(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            out.put(entry.getKey(), moneyString(entry.getValue()));
        }
        return out;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private String moneyStringOrNull(BigDecimal value) {
        return value == null ? null : moneyString(value);
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record ReconcileRequest(
            @NotNull Long accountId,
            int year,
            int month,
            BigDecimal statementTotal,
            String notes) {
    }

    record ReconcileResponse(
            long accountId,
            Integer year,
            Integer month,
            LocalDate start,
            LocalDate end,
            int transactionCount,
            String importedTotal,
            String importedInflows,
            String importedOutflows,
            Map<String, String> byKind,
            String statementTotal,
            String statementNotes,
            String delta) {
    }

    record SplitGroupSummaryResponse(
            String groupName,
            String sharedOutflows,
            String personalOutflows,
            String expectedReimbursement,
            String receivedReimbursement,
            String remainingOwed,
            int transactionCount) {
    }

    private record ReconciliationRow(BigDecimal statementTotal, String notes) {
    }

    private static final class SplitAccumulator {
        BigDecimal sharedOutflows = BigDecimal.ZERO;
        BigDecimal personalOutflows = BigDecimal.ZERO;
        BigDecimal expectedReimbursement = BigDecimal.ZERO;
        BigDecimal receivedReimbursement = BigDecimal.ZERO;
        int transactionCount = 0;
    }
}
