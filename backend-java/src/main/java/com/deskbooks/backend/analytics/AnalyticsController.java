package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @GetMapping("/sankey")
    SankeyResponse sankey(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try (Connection connection = connections.open()) {
            if (start != null && end != null) {
                if (end.isBefore(start)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
                }
                return SankeyAnalytics.load(connection, start, end, "%s to %s".formatted(start, end));
            }
            if (year == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "provide either year or start/end");
            }
            return SankeyAnalytics.load(
                    connection,
                    LocalDate.of(year, 1, 1),
                    LocalDate.of(year, 12, 31),
                    String.valueOf(year));
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/monthly")
    List<MonthlyPointResponse> monthly(
            @RequestParam(name = "start") LocalDate start,
            @RequestParam(name = "end") LocalDate end) {
        try (Connection connection = connections.open()) {
            return MonthlyAnalytics.load(connection, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/recurring")
    List<RecurringMerchantResponse> recurring(
            @RequestParam(name = "min_occurrences", defaultValue = "3") int minOccurrences,
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COALESCE(merchant, description_normalized) AS merchant,
                               COUNT(id) AS occurrences,
                               AVG(amount) AS avg_amount,
                               SUM(amount) AS total_amount,
                               MAX(date) AS last_seen,
                               MIN(date) AS first_seen
                        FROM transactions
                        WHERE COALESCE(merchant, description_normalized) IS NOT NULL
                          AND is_excluded_from_totals = 0
                          AND (? IS NULL OR date >= ?)
                          AND (? IS NULL OR date <= ?)
                        GROUP BY COALESCE(merchant, description_normalized)
                        HAVING COUNT(id) >= ?
                        ORDER BY COUNT(id) DESC
                        """)) {
            String startValue = start == null ? null : start.toString();
            String endValue = end == null ? null : end.toString();
            statement.setString(1, startValue);
            statement.setString(2, startValue);
            statement.setString(3, endValue);
            statement.setString(4, endValue);
            statement.setInt(5, minOccurrences);

            List<RecurringMerchantResponse> out = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate firstSeen = nullableDate(rs.getString("first_seen"));
                    LocalDate lastSeen = nullableDate(rs.getString("last_seen"));
                    out.add(new RecurringMerchantResponse(
                            rs.getString("merchant"),
                            rs.getInt("occurrences"),
                            moneyString(rs.getBigDecimal("avg_amount")),
                            moneyString(rs.getBigDecimal("total_amount")),
                            lastSeen,
                            cadenceDays(firstSeen, lastSeen, rs.getInt("occurrences"))));
                }
            }
            return out;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/reconcile")
    ReconcileResponse reconcile(
            @RequestParam(name = "account_id") long accountId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try (Connection connection = connections.open()) {
            return ReconcileAnalytics.load(connection, accountId, year, month, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/reconcile")
    ReconcileResponse upsertReconcile(@Valid @RequestBody ReconcileRequest body) {
        try (Connection connection = connections.open()) {
            return ReconcileAnalytics.upsert(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/splits")
    List<SplitGroupSummaryResponse> splitGroups(
            @RequestParam(name = "start") LocalDate start,
            @RequestParam(name = "end") LocalDate end) {
        try (Connection connection = connections.open()) {
            return SplitAnalytics.load(connection, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private LocalDate nullableDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private Double cadenceDays(LocalDate firstSeen, LocalDate lastSeen, int occurrences) {
        if (firstSeen == null || lastSeen == null || occurrences <= 1) {
            return null;
        }
        long spanDays = ChronoUnit.DAYS.between(firstSeen, lastSeen);
        if (spanDays <= 0) {
            return null;
        }
        return spanDays / (double) (occurrences - 1);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
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

    record MonthlyPointResponse(
            String month,
            Map<String, BigDecimal> byKind,
            Map<String, BigDecimal> byExpenseCategory,
            Map<String, BigDecimal> byIncomeCategory,
            BigDecimal expensesTotal,
            BigDecimal incomeTotal,
            BigDecimal donationsTotal,
            BigDecimal taxesTotal,
            BigDecimal net) {
    }

    record RecurringMerchantResponse(
            String merchant,
            int occurrences,
            String avgAmount,
            String totalAmount,
            LocalDate lastSeen,
            Double cadenceDaysEstimate) {
    }

    record SankeyResponse(
            int year,
            String label,
            List<SankeyNodeResponse> nodes,
            List<SankeyLinkResponse> links,
            List<String> notes) {
    }

    record SankeyNodeResponse(String name) {
    }

    record SankeyLinkResponse(int source, int target, double value, String label) {
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

}
