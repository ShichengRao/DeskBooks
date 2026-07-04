package com.deskbooks.backend.budgets;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

record BudgetReportSettings(
        Map<Long, BudgetDefault> defaults,
        Map<MonthCategoryKey, BudgetOverride> overrides) {
    private static final String CATEGORY_ID = "category_id";
    private static final String AMOUNT = "amount";

    static BudgetReportSettings load(Connection connection, LocalDate start, LocalDate end) throws SQLException {
        return new BudgetReportSettings(defaults(connection), overrides(connection, start, end));
    }

    private static Map<Long, BudgetDefault> defaults(Connection connection) throws SQLException {
        Map<Long, BudgetDefault> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, category_id, amount, notes FROM budget_defaults
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong(CATEGORY_ID), new BudgetDefault(
                        rs.getLong("id"),
                        rs.getLong(CATEGORY_ID),
                        rs.getBigDecimal(AMOUNT),
                        rs.getString("notes")));
            }
        }
        return out;
    }

    private static Map<MonthCategoryKey, BudgetOverride> overrides(
            Connection connection,
            LocalDate start,
            LocalDate end) throws SQLException {
        Map<MonthCategoryKey, BudgetOverride> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, month, category_id, amount, notes
                FROM budget_overrides
                WHERE month >= ? AND month <= ?
                """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate month = LocalDate.parse(rs.getString("month"));
                    long categoryId = rs.getLong(CATEGORY_ID);
                    out.put(new MonthCategoryKey(month, categoryId), new BudgetOverride(
                            rs.getLong("id"),
                            month,
                            categoryId,
                            rs.getBigDecimal(AMOUNT),
                            rs.getString("notes")));
                }
            }
        }
        return out;
    }
}
