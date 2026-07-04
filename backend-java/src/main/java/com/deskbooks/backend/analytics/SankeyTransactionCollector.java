package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

final class SankeyTransactionCollector {
    private static final String INCOME_KIND = "income";
    private static final String EXPENSE_KIND = "expense";
    private static final String DONATION_KIND = "donation";
    private static final String TAX_KIND = "tax";

    private SankeyTransactionCollector() {
    }

    static SankeyTransactionRollup collect(
            Connection connection,
            LocalDate start,
            LocalDate end,
            Map<Long, CategoryGroup> groupMap) throws SQLException {
        SankeyTransactionCollector collector = new SankeyTransactionCollector();
        return collector.collectTransactions(connection, start, end, groupMap);
    }

    private SankeyTransactionRollup collectTransactions(
            Connection connection,
            LocalDate start,
            LocalDate end,
            Map<Long, CategoryGroup> groupMap) throws SQLException {
        SankeyTransactionAccumulator accumulator = new SankeyTransactionAccumulator();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.kind, t.amount, s.personal_share, t.merchant, t.category_id
                FROM transactions t
                LEFT JOIN transaction_splits s ON s.transaction_id = t.id
                WHERE t.date >= ?
                  AND t.date <= ?
                  AND t.is_excluded_from_totals = 0
                """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            collectRows(statement, groupMap, accumulator);
        }
        return accumulator.rollup();
    }

    private void collectRows(
            PreparedStatement statement,
            Map<Long, CategoryGroup> groupMap,
            SankeyTransactionAccumulator accumulator) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                accumulator.add(transactionRow(rs, groupMap));
            }
        }
    }

    private SankeyTransactionRow transactionRow(ResultSet rs, Map<Long, CategoryGroup> groupMap)
            throws SQLException {
        BigDecimal amount = effectiveAmount(rs.getBigDecimal("amount"), rs.getBigDecimal("personal_share"));
        CategoryGroup categoryGroup = categoryGroup(rs, groupMap);
        return new SankeyTransactionRow(
                rs.getString("kind"),
                amount,
                firstNonBlank(categoryGroup == null ? null : categoryGroup.leaf(), rs.getString("merchant"), "(uncategorized)"),
                firstNonBlank(categoryGroup == null ? null : categoryGroup.group(), "(Uncategorized)"));
    }

    private CategoryGroup categoryGroup(ResultSet rs, Map<Long, CategoryGroup> groupMap) throws SQLException {
        Long categoryId = nullableLong(rs, "category_id");
        return categoryId == null ? null : groupMap.get(categoryId);
    }

    private BigDecimal effectiveAmount(BigDecimal amount, BigDecimal personalShare) {
        return personalShare == null ? amount : amount.multiply(personalShare);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static final class SankeyTransactionAccumulator {
        private final Map<String, BigDecimal> incomeLeaves = new LinkedHashMap<>();
        private final Map<String, Map<String, BigDecimal>> expenses = new LinkedHashMap<>();
        private BigDecimal donationsTotal = BigDecimal.ZERO;
        private BigDecimal taxesTotal = BigDecimal.ZERO;

        void add(SankeyTransactionRow row) {
            switch (row.kind()) {
                case INCOME_KIND -> incomeLeaves.merge(row.leaf(), row.amount(), BigDecimal::add);
                case EXPENSE_KIND -> expenses.computeIfAbsent(row.group(), ignored -> new LinkedHashMap<>())
                        .merge(row.leaf(), row.amount().negate(), BigDecimal::add);
                case DONATION_KIND -> donationsTotal = donationsTotal.add(row.amount().negate());
                case TAX_KIND -> taxesTotal = taxesTotal.add(row.amount().negate());
                default -> {
                    // Transfers and uncategorized rows do not participate in the Sankey cashflow.
                }
            }
        }

        SankeyTransactionRollup rollup() {
            return new SankeyTransactionRollup(incomeLeaves, expenses, donationsTotal, taxesTotal);
        }
    }

    private record SankeyTransactionRow(String kind, BigDecimal amount, String leaf, String group) {
    }
}
