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

final class MonthlyAnalytics {
    private static final String UNCATEGORIZED = "Uncategorized";

    private MonthlyAnalytics() {
    }

    static List<AnalyticsController.MonthlyPointResponse> load(
            Connection connection,
            LocalDate start,
            LocalDate end) throws SQLException {
        Map<String, MonthlyAccumulator> byMonth = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.id, t.date, t.kind, t.amount, s.personal_share, c.name AS category_name
                FROM transactions t
                LEFT JOIN categories c ON c.id = t.category_id
                LEFT JOIN transaction_splits s ON s.transaction_id = t.id
                WHERE t.date >= ?
                  AND t.date <= ?
                  AND t.is_excluded_from_totals = 0
                ORDER BY t.date, t.id
                """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    accumulate(byMonth, rs);
                }
            }
        }
        return responses(byMonth);
    }

    private static void accumulate(Map<String, MonthlyAccumulator> byMonth, ResultSet rs) throws SQLException {
        String month = YearMonth.from(LocalDate.parse(rs.getString("date"))).toString();
        MonthlyAccumulator bucket = byMonth.computeIfAbsent(month, ignored -> new MonthlyAccumulator());
        bucket.add(
                rs.getString("kind"),
                effectiveAmount(rs.getBigDecimal("amount"), rs.getBigDecimal("personal_share")),
                rs.getString("category_name"));
    }

    private static List<AnalyticsController.MonthlyPointResponse> responses(Map<String, MonthlyAccumulator> byMonth) {
        List<AnalyticsController.MonthlyPointResponse> out = new ArrayList<>();
        for (Map.Entry<String, MonthlyAccumulator> entry : byMonth.entrySet()) {
            out.add(entry.getValue().response(entry.getKey()));
        }
        return out;
    }

    private static BigDecimal effectiveAmount(BigDecimal amount, BigDecimal personalShare) {
        if (personalShare == null) {
            return amount;
        }
        return amount.multiply(personalShare);
    }

    private static Map<String, BigDecimal> numericMoney(Map<String, BigDecimal> values) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            out.put(entry.getKey(), money(entry.getValue()));
        }
        return out;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class MonthlyAccumulator {
        private final Map<String, BigDecimal> byKind = new LinkedHashMap<>();
        private final Map<String, BigDecimal> byExpenseCategory = new LinkedHashMap<>();
        private final Map<String, BigDecimal> byIncomeCategory = new LinkedHashMap<>();
        private BigDecimal expensesTotal = BigDecimal.ZERO;
        private BigDecimal incomeTotal = BigDecimal.ZERO;
        private BigDecimal donationsTotal = BigDecimal.ZERO;
        private BigDecimal taxesTotal = BigDecimal.ZERO;

        void add(String kind, BigDecimal amount, String categoryName) {
            byKind.merge(kind, amount, BigDecimal::add);
            String categoryLabel = categoryLabel(categoryName);
            if ("expense".equals(kind)) {
                addExpense(categoryLabel, amount.negate());
            } else if ("uncategorized".equals(kind) && amount.compareTo(BigDecimal.ZERO) < 0) {
                addExpense(UNCATEGORIZED, amount.negate());
            } else if ("income".equals(kind)) {
                byIncomeCategory.merge(categoryLabel, amount, BigDecimal::add);
                incomeTotal = incomeTotal.add(amount);
            } else if ("donation".equals(kind)) {
                donationsTotal = donationsTotal.add(amount.negate());
            } else if ("tax".equals(kind)) {
                taxesTotal = taxesTotal.add(amount.negate());
            }
        }

        private void addExpense(String categoryLabel, BigDecimal outflow) {
            byExpenseCategory.merge(categoryLabel, outflow, BigDecimal::add);
            expensesTotal = expensesTotal.add(outflow);
        }

        private AnalyticsController.MonthlyPointResponse response(String month) {
            return new AnalyticsController.MonthlyPointResponse(
                    month,
                    numericMoney(byKind),
                    numericMoney(byExpenseCategory),
                    numericMoney(byIncomeCategory),
                    money(expensesTotal),
                    money(incomeTotal),
                    money(donationsTotal),
                    money(taxesTotal),
                    money(net()));
        }

        private BigDecimal net() {
            return incomeTotal
                    .subtract(expensesTotal)
                    .subtract(donationsTotal)
                    .subtract(taxesTotal);
        }

        private String categoryLabel(String categoryName) {
            return categoryName == null || categoryName.isBlank() ? UNCATEGORIZED : categoryName;
        }
    }
}
