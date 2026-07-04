package com.deskbooks.backend.budgets;

import static com.deskbooks.backend.budgets.BudgetSqlValues.localDateTime;
import static com.deskbooks.backend.budgets.BudgetSqlValues.money;
import static com.deskbooks.backend.budgets.BudgetSqlValues.moneyString;

import com.deskbooks.backend.budgets.BudgetController.BudgetDefaultRequest;
import com.deskbooks.backend.budgets.BudgetController.BudgetDefaultResponse;
import com.deskbooks.backend.foundation.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.http.HttpStatus;

final class BudgetDefaultStore {
    private final BudgetRowDeletion deletion = new BudgetRowDeletion("budget_defaults", "budget default not found");

    BudgetDefaultResponse upsert(Connection connection, BudgetDefaultRequest body) throws SQLException {
        Long existingId = existingId(connection, body.categoryId());
        if (existingId == null) {
            return insert(connection, body);
        }
        update(connection, existingId, body);
        return get(connection, existingId);
    }

    void delete(Connection connection, long budgetId) throws SQLException {
        deletion.delete(connection, budgetId);
    }

    private Long existingId(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM budget_defaults WHERE category_id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    private BudgetDefaultResponse insert(Connection connection, BudgetDefaultRequest body) throws SQLException {
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
                return get(connection, keys.getLong(1));
            }
        }
    }

    private void update(Connection connection, long existingId, BudgetDefaultRequest body) throws SQLException {
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
    }

    private BudgetDefaultResponse get(Connection connection, long id) throws SQLException {
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
}
