package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SqliteConnectionProvider connections;
    private final BudgetReportBuilder reports = new BudgetReportBuilder();

    BudgetController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    BudgetReportResponse getBudget(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "focus_month", required = false) LocalDate focusMonth,
            @RequestParam(name = "month", required = false) LocalDate month) {
        if (month != null && start == null && end == null) {
            start = month;
            end = month;
            focusMonth = month;
        }
        if (start == null || end == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "provide start/end or month");
        }
        if (end.isBefore(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }
        try (Connection connection = connections.open()) {
            return reports.report(connection, start, end, focusMonth);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/defaults")
    BudgetDefaultResponse upsertBudgetDefault(@Valid @RequestBody BudgetDefaultRequest body) {
        try (Connection connection = connections.open()) {
            validateBudgetCategory(connection, body.categoryId(), body.amount());
            Long existingId = existingBudgetDefaultId(connection, body.categoryId());
            if (existingId == null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO budget_defaults (category_id, amount, notes)
                        VALUES (?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, body.categoryId());
                    statement.setBigDecimal(2, money(body.amount()));
                    statement.setString(3, body.notes());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        return getBudgetDefault(connection, keys.getLong(1));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE budget_defaults
                    SET amount = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setBigDecimal(1, money(body.amount()));
                statement.setString(2, body.notes());
                statement.setLong(3, existingId);
                statement.executeUpdate();
            }
            return getBudgetDefault(connection, existingId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/overrides")
    BudgetOverrideResponse upsertBudgetOverride(@Valid @RequestBody BudgetOverrideRequest body) {
        try (Connection connection = connections.open()) {
            validateBudgetCategory(connection, body.categoryId(), body.amount());
            LocalDate month = normalizeMonth(body.month());
            Long existingId = existingBudgetOverrideId(connection, month, body.categoryId());
            if (existingId == null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO budget_overrides (month, category_id, amount, notes)
                        VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, month.toString());
                    statement.setLong(2, body.categoryId());
                    statement.setBigDecimal(3, money(body.amount()));
                    statement.setString(4, body.notes());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        return getBudgetOverride(connection, keys.getLong(1));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE budget_overrides
                    SET amount = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setBigDecimal(1, money(body.amount()));
                statement.setString(2, body.notes());
                statement.setLong(3, existingId);
                statement.executeUpdate();
            }
            return getBudgetOverride(connection, existingId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/defaults/{budgetId}")
    Map<String, Boolean> deleteBudgetDefault(@PathVariable long budgetId) {
        try (Connection connection = connections.open()) {
            deleteBudgetRow(connection, "budget_defaults", budgetId, "budget default not found");
            return Map.of("ok", true);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/overrides/{budgetId}")
    Map<String, Boolean> deleteBudgetOverride(@PathVariable long budgetId) {
        try (Connection connection = connections.open()) {
            deleteBudgetRow(connection, "budget_overrides", budgetId, "budget override not found");
            return Map.of("ok", true);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private void validateBudgetCategory(Connection connection, long categoryId, BigDecimal amount) throws SQLException {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "budget amount must be zero or greater");
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT kind FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
                }
                if (!"expense".equals(rs.getString("kind"))) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "budgets can only target expense categories");
                }
            }
        }
    }

    private Long existingBudgetDefaultId(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM budget_defaults WHERE category_id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getLong("id");
            }
        }
    }

    private Long existingBudgetOverrideId(Connection connection, LocalDate month, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM budget_overrides WHERE month = ? AND category_id = ?
                """)) {
            statement.setString(1, month.toString());
            statement.setLong(2, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getLong("id");
            }
        }
    }

    private BudgetDefaultResponse getBudgetDefault(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, category_id, amount, notes, updated_at
                FROM budget_defaults
                WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "budget default not found");
                }
                return new BudgetDefaultResponse(
                        rs.getLong("id"),
                        rs.getLong("category_id"),
                        moneyString(rs.getBigDecimal("amount")),
                        rs.getString("notes"),
                        localDateTime(rs.getString("updated_at")));
            }
        }
    }

    private BudgetOverrideResponse getBudgetOverride(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, month, category_id, amount, notes, updated_at
                FROM budget_overrides
                WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "budget override not found");
                }
                return new BudgetOverrideResponse(
                        rs.getLong("id"),
                        LocalDate.parse(rs.getString("month")),
                        rs.getLong("category_id"),
                        moneyString(rs.getBigDecimal("amount")),
                        rs.getString("notes"),
                        localDateTime(rs.getString("updated_at")));
            }
        }
    }

    private void deleteBudgetRow(Connection connection, String table, long id, String missingMessage) throws SQLException {
        String selectSql = "SELECT 1 FROM " + table + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, missingMessage);
                }
            }
        }
        String deleteSql = "DELETE FROM " + table + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private LocalDate normalizeMonth(LocalDate value) {
        return LocalDate.of(value.getYear(), value.getMonth(), 1);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private LocalDateTime localDateTime(String value) {
        if (value == null) {
            return null;
        }
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDateTime.parse(value, SQLITE_TIMESTAMP);
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
