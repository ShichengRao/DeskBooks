package com.deskbooks.backend.budgets;

import com.deskbooks.backend.budgets.BudgetController.BudgetDefaultRequest;
import com.deskbooks.backend.budgets.BudgetController.BudgetDefaultResponse;
import com.deskbooks.backend.budgets.BudgetController.BudgetOverrideRequest;
import com.deskbooks.backend.budgets.BudgetController.BudgetOverrideResponse;
import com.deskbooks.backend.db.SqliteConnectionProvider;
import java.sql.Connection;
import java.sql.SQLException;

final class BudgetSettingsStore {
    private final SqliteConnectionProvider connections;
    private final BudgetCategoryValidator validator = new BudgetCategoryValidator();
    private final BudgetDefaultStore defaults = new BudgetDefaultStore();
    private final BudgetOverrideStore overrides = new BudgetOverrideStore();

    BudgetSettingsStore(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    BudgetDefaultResponse upsertDefault(BudgetDefaultRequest body) throws SQLException {
        try (Connection connection = connections.open()) {
            validator.validate(connection, body.categoryId(), body.amount());
            return defaults.upsert(connection, body);
        }
    }

    BudgetOverrideResponse upsertOverride(BudgetOverrideRequest body) throws SQLException {
        try (Connection connection = connections.open()) {
            validator.validate(connection, body.categoryId(), body.amount());
            return overrides.upsert(connection, body);
        }
    }

    void deleteDefault(long budgetId) throws SQLException {
        try (Connection connection = connections.open()) {
            defaults.delete(connection, budgetId);
        }
    }

    void deleteOverride(long budgetId) throws SQLException {
        try (Connection connection = connections.open()) {
            overrides.delete(connection, budgetId);
        }
    }
}
