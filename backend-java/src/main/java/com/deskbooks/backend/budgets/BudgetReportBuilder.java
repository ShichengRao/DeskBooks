package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BudgetReportBuilder {
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
        List<BudgetReportRowResponse> rows = reportRows(context, defaultByCategory, overrideByMonthCategory, spending, rollupCache, focusMonth, rowMonths);

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
                moneyString(rangePlanned),
                moneyString(rangeActual),
                moneyString(rangePlanned.subtract(rangeActual)),
                moneyString(rangeBudgetedActual),
                moneyString(rangeActual.subtract(rangeBudgetedActual)),
                moneyString(rangeUncategorized),
                rows);
    }

    private List<BudgetReportRowResponse> reportRows(
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache,
            LocalDate focusMonth,
            List<LocalDate> rowMonths) {
        List<BudgetReportRowResponse> rows = new ArrayList<>();
        for (CategoryRow category : context.orderedCategories()) {
            rows.add(reportRow(category, context, defaultByCategory, overrideByMonthCategory, spending, rollupCache, focusMonth, rowMonths));
        }
        return rows;
    }

    private BudgetReportRowResponse reportRow(
            CategoryRow category,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache,
            LocalDate focusMonth,
            List<LocalDate> rowMonths) {
        Set<Long> ids = descendants(context, category.id());
        BigDecimal actual = BigDecimal.ZERO;
        int transactionCount = 0;
        for (LocalDate rowMonth : rowMonths) {
            for (Long id : ids) {
                MonthCategoryKey key = new MonthCategoryKey(rowMonth, id);
                actual = actual.add(spending.actualByMonthExact().getOrDefault(key, BigDecimal.ZERO));
                transactionCount += spending.countByMonthExact().getOrDefault(key, 0);
            }
        }

        BudgetDefault defaultBudget = defaultByCategory.get(category.id());
        BudgetOverride override = focusMonth == null ? null : overrideByMonthCategory.get(new MonthCategoryKey(focusMonth, category.id()));
        BigDecimal target = targetForRow(category, context, defaultByCategory, overrideByMonthCategory, rollupCache, rowMonths);
        CategoryRow parent = category.parentId() == null ? null : context.categoryById().get(category.parentId());
        return new BudgetReportRowResponse(
                category.id(),
                category.name(),
                category.parentId(),
                parent == null ? null : parent.name(),
                depth(context, category),
                !context.childrenByParent().getOrDefault(category.id(), List.of()).isEmpty(),
                defaultBudget == null ? null : defaultBudget.id(),
                defaultBudget == null ? null : moneyString(defaultBudget.amount()),
                override == null ? null : override.id(),
                override == null ? null : moneyString(override.amount()),
                moneyStringOrNull(target),
                moneyString(actual),
                target == null ? null : moneyString(target.subtract(actual)),
                transactionCount,
                defaultBudget == null ? null : defaultBudget.notes(),
                override == null ? null : override.notes());
    }

    private BigDecimal targetForRow(
            CategoryRow category,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            Map<MonthCategoryKey, BigDecimal> rollupCache,
            List<LocalDate> rowMonths) {
        List<BigDecimal> rowTargets = new ArrayList<>();
        for (LocalDate rowMonth : rowMonths) {
            BigDecimal target = rollupTargetFor(rowMonth, category.id(), context, defaultByCategory, overrideByMonthCategory, rollupCache);
            if (target != null) {
                rowTargets.add(target);
            }
        }
        return rowTargets.isEmpty()
                ? null
                : rowTargets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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
            BigDecimal target = rollupTargetFor(month, root.id(), context, defaultByCategory, overrideByMonthCategory, rollupCache);
            if (target == null) {
                continue;
            }
            plannedTotal = plannedTotal.add(target);
            coveredCategoryIds.addAll(descendants(context, root.id()));
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
                moneyString(plannedTotal),
                moneyString(actualTotal),
                moneyString(plannedTotal.subtract(actualTotal)),
                moneyString(budgetedActualTotal),
                moneyString(actualTotal.subtract(budgetedActualTotal)),
                moneyString(uncategorized));
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

    private BigDecimal rollupTargetFor(
            LocalDate month,
            long categoryId,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            Map<MonthCategoryKey, BigDecimal> cache) {
        MonthCategoryKey key = new MonthCategoryKey(month, categoryId);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        List<BigDecimal> childTargets = new ArrayList<>();
        for (CategoryRow child : context.childrenByParent().getOrDefault(categoryId, List.of())) {
            BigDecimal target = rollupTargetFor(month, child.id(), context, defaultByCategory, overrideByMonthCategory, cache);
            if (target != null) {
                childTargets.add(target);
            }
        }
        BigDecimal target;
        if (childTargets.isEmpty()) {
            target = directTargetFor(month, categoryId, defaultByCategory, overrideByMonthCategory);
        } else {
            target = childTargets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        cache.put(key, target);
        return target;
    }

    private BigDecimal directTargetFor(
            LocalDate month,
            long categoryId,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory) {
        BudgetOverride override = overrideByMonthCategory.get(new MonthCategoryKey(month, categoryId));
        if (override != null) {
            return override.amount();
        }
        BudgetDefault budgetDefault = defaultByCategory.get(categoryId);
        return budgetDefault == null ? null : budgetDefault.amount();
    }

    private Set<Long> descendants(CategoryContext context, long categoryId) {
        Set<Long> out = new LinkedHashSet<>();
        out.add(categoryId);
        for (CategoryRow child : context.childrenByParent().getOrDefault(categoryId, List.of())) {
            out.addAll(descendants(context, child.id()));
        }
        return out;
    }

    private int depth(CategoryContext context, CategoryRow category) {
        CategoryRow current = category;
        int count = 0;
        Set<Long> seen = new HashSet<>();
        while (current.parentId() != null
                && context.categoryById().containsKey(current.parentId())
                && seen.add(current.parentId())) {
            count++;
            current = context.categoryById().get(current.parentId());
        }
        return count;
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

    private String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private String moneyStringOrNull(BigDecimal value) {
        return value == null ? null : moneyString(value);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record CategoryRow(long id, String name, Long parentId, int sortOrder) {
    }

    private record CategoryContext(
            List<CategoryRow> roots,
            List<CategoryRow> orderedCategories,
            Map<Long, CategoryRow> categoryById,
            Map<Long, List<CategoryRow>> childrenByParent) {
    }

    private record BudgetDefault(long id, long categoryId, BigDecimal amount, String notes) {
    }

    private record BudgetOverride(long id, LocalDate month, long categoryId, BigDecimal amount, String notes) {
    }

    private record MonthCategoryKey(LocalDate month, long categoryId) {
    }

    private record TransactionSpending(Long categoryId, BigDecimal spending) {
    }

    private record SpendingContext(
            Map<MonthCategoryKey, BigDecimal> actualByMonthExact,
            Map<MonthCategoryKey, Integer> countByMonthExact,
            Map<LocalDate, List<TransactionSpending>> transactionRowsByMonth,
            Map<LocalDate, BigDecimal> uncategorizedByMonth) {
    }
}
