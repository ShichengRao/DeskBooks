package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BudgetReportBuilder {
    private final BudgetTargets targets = new BudgetTargets();
    private final BudgetReportRows rows = new BudgetReportRows(targets);

    BudgetReportResponse report(
            Connection connection,
            LocalDate startValue,
            LocalDate endValue,
            LocalDate focusMonthValue) throws SQLException {
        List<LocalDate> months = monthRange(startValue, endValue);
        if (months.isEmpty()) {
            months = List.of(normalizeMonth(startValue));
        }
        LocalDate focusMonth = focusMonthValue == null ? null : normalizeMonth(focusMonthValue);
        if (focusMonth != null && (focusMonth.isBefore(months.getFirst()) || focusMonth.isAfter(months.getLast()))) {
            focusMonth = months.getLast();
        }

        CategoryContext context = categoryContext(connection);
        Map<Long, BudgetDefault> defaultByCategory = budgetDefaults(connection);
        Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory = budgetOverrides(connection, months.getFirst(), months.getLast());
        SpendingContext spending = spendingContext(connection, months);

        Map<MonthCategoryKey, BigDecimal> rollupCache = new HashMap<>();
        List<LocalDate> rowMonths = focusMonth == null ? months : List.of(focusMonth);
        List<BudgetReportRowResponse> reportRows = rows.rows(
                context,
                defaultByCategory,
                overrideByMonthCategory,
                spending,
                rollupCache,
                focusMonth,
                rowMonths);

        List<BudgetMonthSummaryResponse> summaries = new ArrayList<>();
        for (LocalDate month : months) {
            summaries.add(monthSummary(month, context, defaultByCategory, overrideByMonthCategory, spending, rollupCache));
        }
        BigDecimal rangePlanned = summaries.stream().map(summary -> new BigDecimal(summary.plannedTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rangeActual = summaries.stream().map(summary -> new BigDecimal(summary.actualTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rangeBudgetedActual = summaries.stream().map(summary -> new BigDecimal(summary.budgetedActualTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rangeUncategorized = summaries.stream().map(summary -> new BigDecimal(summary.uncategorizedActual())).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BudgetReportResponse(
                months.getFirst(),
                months.getLast(),
                focusMonth,
                summaries,
                BudgetMoney.format(rangePlanned),
                BudgetMoney.format(rangeActual),
                BudgetMoney.format(rangePlanned.subtract(rangeActual)),
                BudgetMoney.format(rangeBudgetedActual),
                BudgetMoney.format(rangeActual.subtract(rangeBudgetedActual)),
                BudgetMoney.format(rangeUncategorized),
                reportRows);
    }

    private BudgetMonthSummaryResponse monthSummary(
            LocalDate month,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache) {
        BigDecimal plannedTotal = BigDecimal.ZERO;
        Set<Long> coveredCategoryIds = new HashSet<>();
        for (CategoryRow root : context.roots()) {
            BigDecimal target = targets.rollupTargetFor(
                    month,
                    root.id(),
                    context,
                    defaultByCategory,
                    overrideByMonthCategory,
                    rollupCache);
            if (target == null) {
                continue;
            }
            plannedTotal = plannedTotal.add(target);
            coveredCategoryIds.addAll(targets.descendants(context, root.id()));
        }

        BigDecimal actualTotal = spending.transactionRowsByMonth().getOrDefault(month, List.of()).stream()
                .map(TransactionSpending::spending)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetedActualTotal = BigDecimal.ZERO;
        for (TransactionSpending row : spending.transactionRowsByMonth().getOrDefault(month, List.of())) {
            if (row.categoryId() != null && coveredCategoryIds.contains(row.categoryId())) {
                budgetedActualTotal = budgetedActualTotal.add(row.spending());
            }
        }
        BigDecimal uncategorized = spending.uncategorizedByMonth().getOrDefault(month, BigDecimal.ZERO);
        return new BudgetMonthSummaryResponse(
                month,
                BudgetMoney.format(plannedTotal),
                BudgetMoney.format(actualTotal),
                BudgetMoney.format(plannedTotal.subtract(actualTotal)),
                BudgetMoney.format(budgetedActualTotal),
                BudgetMoney.format(actualTotal.subtract(budgetedActualTotal)),
                BudgetMoney.format(uncategorized));
    }

    private CategoryContext categoryContext(Connection connection) throws SQLException {
        List<CategoryRow> categories = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, parent_id, sort_order
                FROM categories
                WHERE kind = 'expense'
                ORDER BY sort_order, name
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                categories.add(new CategoryRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        nullableLong(rs, "parent_id"),
                        rs.getInt("sort_order")));
            }
        }

        Map<Long, CategoryRow> categoryById = new LinkedHashMap<>();
        Map<Long, List<CategoryRow>> childrenByParent = new LinkedHashMap<>();
        for (CategoryRow category : categories) {
            categoryById.put(category.id(), category);
            if (category.parentId() != null) {
                childrenByParent.computeIfAbsent(category.parentId(), ignored -> new ArrayList<>()).add(category);
            }
        }
        childrenByParent.values().forEach(children -> children.sort(categoryComparator()));
        List<CategoryRow> roots = categories.stream()
                .filter(category -> category.parentId() == null || !categoryById.containsKey(category.parentId()))
                .sorted(categoryComparator())
                .toList();
        List<CategoryRow> ordered = new ArrayList<>();
        for (CategoryRow root : roots) {
            walk(root, childrenByParent, ordered);
        }
        return new CategoryContext(roots, ordered, categoryById, childrenByParent);
    }

    private void walk(CategoryRow category, Map<Long, List<CategoryRow>> childrenByParent, List<CategoryRow> ordered) {
        ordered.add(category);
        for (CategoryRow child : childrenByParent.getOrDefault(category.id(), List.of())) {
            walk(child, childrenByParent, ordered);
        }
    }

    private Comparator<CategoryRow> categoryComparator() {
        return Comparator.comparingInt(CategoryRow::sortOrder)
                .thenComparing(category -> category.name().toLowerCase(Locale.ROOT));
    }

    private Map<Long, BudgetDefault> budgetDefaults(Connection connection) throws SQLException {
        Map<Long, BudgetDefault> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, category_id, amount, notes FROM budget_defaults
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong("category_id"), new BudgetDefault(
                        rs.getLong("id"),
                        rs.getLong("category_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("notes")));
            }
        }
        return out;
    }

    private Map<MonthCategoryKey, BudgetOverride> budgetOverrides(
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
                    long categoryId = rs.getLong("category_id");
                    out.put(new MonthCategoryKey(month, categoryId), new BudgetOverride(
                            rs.getLong("id"),
                            month,
                            categoryId,
                            rs.getBigDecimal("amount"),
                            rs.getString("notes")));
                }
            }
        }
        return out;
    }

    private SpendingContext spendingContext(Connection connection, List<LocalDate> months) throws SQLException {
        Map<MonthCategoryKey, BigDecimal> actualByMonthExact = new HashMap<>();
        Map<MonthCategoryKey, Integer> countByMonthExact = new HashMap<>();
        Map<LocalDate, List<TransactionSpending>> transactionRowsByMonth = new HashMap<>();
        Map<LocalDate, BigDecimal> uncategorizedByMonth = new HashMap<>();

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
            statement.setString(2, monthEndExclusive(months.getLast()).toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate month = normalizeMonth(LocalDate.parse(rs.getString("date")));
                    Long categoryId = nullableLong(rs, "category_id");
                    BigDecimal personalShare = rs.getBigDecimal("personal_share");
                    BigDecimal effective = personalShare == null
                            ? rs.getBigDecimal("amount")
                            : rs.getBigDecimal("amount").multiply(personalShare);
                    BigDecimal spending = effective.negate();
                    transactionRowsByMonth.computeIfAbsent(month, ignored -> new ArrayList<>()).add(new TransactionSpending(categoryId, spending));
                    if (categoryId == null) {
                        uncategorizedByMonth.merge(month, spending, BigDecimal::add);
                        continue;
                    }
                    MonthCategoryKey key = new MonthCategoryKey(month, categoryId);
                    actualByMonthExact.merge(key, spending, BigDecimal::add);
                    countByMonthExact.merge(key, 1, Integer::sum);
                }
            }
        }
        return new SpendingContext(actualByMonthExact, countByMonthExact, transactionRowsByMonth, uncategorizedByMonth);
    }

    private List<LocalDate> monthRange(LocalDate start, LocalDate end) {
        LocalDate current = normalizeMonth(start);
        LocalDate finalMonth = normalizeMonth(end);
        List<LocalDate> out = new ArrayList<>();
        while (!current.isAfter(finalMonth)) {
            out.add(current);
            current = monthEndExclusive(current);
        }
        return out;
    }

    private LocalDate normalizeMonth(LocalDate value) {
        return LocalDate.of(value.getYear(), value.getMonth(), 1);
    }

    private LocalDate monthEndExclusive(LocalDate month) {
        return normalizeMonth(month).plusMonths(1);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
