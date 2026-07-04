package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BudgetReportSpending {
    SpendingContext collect(Connection connection, List<LocalDate> months) throws SQLException {
        BudgetSpendingAccumulator accumulator = new BudgetSpendingAccumulator();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.date, t.category_id, t.amount, s.personal_share
                FROM transactions t
                LEFT JOIN transaction_splits s ON s.transaction_id = t.id
                WHERE t.date >= ?
                  AND t.date < ?
                  AND t.kind = 'expense'
                  AND t.is_excluded_from_totals = 0
                """)) {
            statement.setString(1, months.getFirst().toString());
            statement.setString(2, BudgetReportMonths.endExclusive(months.getLast()).toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    accumulator.add(row(rs));
                }
            }
        }
        return accumulator.context();
    }

    private TransactionSpendingRow row(ResultSet rs) throws SQLException {
        LocalDate month = BudgetReportMonths.normalize(LocalDate.parse(rs.getString("date")));
        Long categoryId = BudgetSqlValues.nullableLong(rs, "category_id");
        BigDecimal personalShare = rs.getBigDecimal("personal_share");
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal effective = personalShare == null ? amount : amount.multiply(personalShare);
        return new TransactionSpendingRow(month, categoryId, effective.negate());
    }
}

final class BudgetSpendingAccumulator {
    private final Map<MonthCategoryKey, BigDecimal> actualByMonthExact = new HashMap<>();
    private final Map<MonthCategoryKey, Integer> countByMonthExact = new HashMap<>();
    private final Map<LocalDate, List<TransactionSpending>> transactionRowsByMonth = new HashMap<>();
    private final Map<LocalDate, BigDecimal> uncategorizedByMonth = new HashMap<>();

    void add(TransactionSpendingRow row) {
        transactionRowsByMonth
                .computeIfAbsent(row.month(), ignored -> new ArrayList<>())
                .add(new TransactionSpending(row.categoryId(), row.spending()));
        if (row.categoryId() == null) {
            uncategorizedByMonth.merge(row.month(), row.spending(), BigDecimal::add);
            return;
        }
        MonthCategoryKey key = new MonthCategoryKey(row.month(), row.categoryId());
        actualByMonthExact.merge(key, row.spending(), BigDecimal::add);
        countByMonthExact.merge(key, 1, Integer::sum);
    }

    SpendingContext context() {
        return new SpendingContext(
                actualByMonthExact,
                countByMonthExact,
                transactionRowsByMonth,
                uncategorizedByMonth);
    }
}

record TransactionSpendingRow(LocalDate month, Long categoryId, BigDecimal spending) {
}
