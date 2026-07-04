package com.deskbooks.backend.analytics;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
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
    private static final String END_PARAM = "end";
    private static final String START_PARAM = "start";

    private final SqliteConnectionProvider connections;

    AnalyticsController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("/sankey")
    SankeyResponse sankey(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = START_PARAM, required = false) LocalDate start,
            @RequestParam(name = END_PARAM, required = false) LocalDate end) {
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
            @RequestParam(name = START_PARAM) LocalDate start,
            @RequestParam(name = END_PARAM) LocalDate end) {
        try (Connection connection = connections.open()) {
            return MonthlyAnalytics.load(connection, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/recurring")
    List<RecurringMerchantResponse> recurring(
            @RequestParam(name = "min_occurrences", defaultValue = "3") int minOccurrences,
            @RequestParam(name = START_PARAM, required = false) LocalDate start,
            @RequestParam(name = END_PARAM, required = false) LocalDate end) {
        try (Connection connection = connections.open()) {
            return RecurringAnalytics.load(connection, minOccurrences, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/reconcile")
    ReconcileResponse reconcile(
            @RequestParam(name = "account_id") long accountId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = START_PARAM, required = false) LocalDate start,
            @RequestParam(name = END_PARAM, required = false) LocalDate end) {
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
            @RequestParam(name = START_PARAM) LocalDate start,
            @RequestParam(name = END_PARAM) LocalDate end) {
        try (Connection connection = connections.open()) {
            return SplitAnalytics.load(connection, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
