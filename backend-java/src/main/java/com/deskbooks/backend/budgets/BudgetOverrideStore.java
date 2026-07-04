package com.deskbooks.backend.budgets;

import static com.deskbooks.backend.budgets.BudgetSqlValues.localDateTime;
import static com.deskbooks.backend.budgets.BudgetSqlValues.money;
import static com.deskbooks.backend.budgets.BudgetSqlValues.moneyString;
import static com.deskbooks.backend.budgets.BudgetSqlValues.normalizeMonth;

import com.deskbooks.backend.budgets.BudgetController.BudgetOverrideRequest;
import com.deskbooks.backend.budgets.BudgetController.BudgetOverrideResponse;
import com.deskbooks.backend.foundation.ApiException;
import java.time.LocalDate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.http.HttpStatus;

final class BudgetOverrideStore {
    private final BudgetRowDeletion deletion = new BudgetRowDeletion("budget_overrides", "budget override not found");

    BudgetOverrideResponse upsert(Connection connection, BudgetOverrideRequest body) throws SQLException {
        LocalDate month = normalizeMonth(body.month());
        Long existingId = existingId(connection, month, body.categoryId());
        if (existingId == null) {
            return insert(connection, body, month);
        }
        update(connection, existingId, body);
        return get(connection, existingId);
    }

    void delete(Connection connection, long budgetId) throws SQLException {
        deletion.delete(connection, budgetId);
    }

    private Long existingId(Connection connection, LocalDate month, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM budget_overrides WHERE month = ? AND category_id = ?
                """)) {
            statement.setString(1, month.toString());
            statement.setLong(2, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    private BudgetOverrideResponse insert(Connection connection, BudgetOverrideRequest body, LocalDate month)
            throws SQLException {
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
                return get(connection, keys.getLong(1));
            }
        }
    }

    private void update(Connection connection, long existingId, BudgetOverrideRequest body) throws SQLException {
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
    }

    private BudgetOverrideResponse get(Connection connection, long id) throws SQLException {
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
}
