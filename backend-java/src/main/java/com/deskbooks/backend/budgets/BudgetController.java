package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budgets")
class BudgetController {
    private final SqliteConnectionProvider connections;
    private final BudgetReportBuilder reports = new BudgetReportBuilder();
    private final BudgetSettingsStore settings;

    BudgetController(SqliteConnectionProvider connections) {
        this.connections = connections;
        settings = new BudgetSettingsStore(connections);
    }

    @GetMapping("")
    BudgetReportResponse getBudget(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "focus_month", required = false) LocalDate focusMonth,
            @RequestParam(name = "month", required = false) LocalDate month) {
        BudgetWindow window = BudgetWindow.from(start, end, focusMonth, month);
        try (Connection connection = connections.open()) {
            return reports.report(connection, window.start(), window.end(), window.focusMonth());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/defaults")
    BudgetDefaultResponse upsertBudgetDefault(@Valid @RequestBody BudgetDefaultRequest body) {
        try {
            return settings.upsertDefault(body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/overrides")
    BudgetOverrideResponse upsertBudgetOverride(@Valid @RequestBody BudgetOverrideRequest body) {
        try {
            return settings.upsertOverride(body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/defaults/{budgetId}")
    Map<String, Boolean> deleteBudgetDefault(@PathVariable long budgetId) {
        try {
            settings.deleteDefault(budgetId);
            return Map.of("ok", true);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/overrides/{budgetId}")
    Map<String, Boolean> deleteBudgetOverride(@PathVariable long budgetId) {
        try {
            settings.deleteOverride(budgetId);
            return Map.of("ok", true);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record BudgetDefaultRequest(@NotNull Long categoryId, @NotNull BigDecimal amount, String notes) {
    }

    record BudgetOverrideRequest(@NotNull LocalDate month, @NotNull Long categoryId, @NotNull BigDecimal amount, String notes) {
    }

    record BudgetDefaultResponse(long id, long categoryId, String amount, String notes, LocalDateTime updatedAt) {
    }

    record BudgetOverrideResponse(long id, LocalDate month, long categoryId, String amount, String notes, LocalDateTime updatedAt) {
    }

}
