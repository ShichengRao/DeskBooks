package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BudgetReportRows {
    private final BudgetTargets targets;
    private final BudgetReportRowAmounts amounts;

    BudgetReportRows(BudgetTargets targets) {
        this.targets = targets;
        this.amounts = new BudgetReportRowAmounts(targets);
    }

    List<BudgetReportRowResponse> rows(
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache,
            LocalDate focusMonth,
            List<LocalDate> rowMonths) {
        List<BudgetReportRowResponse> rows = new ArrayList<>();
        for (CategoryRow category : context.orderedCategories()) {
            rows.add(row(
                    category,
                    context,
                    defaultByCategory,
                    overrideByMonthCategory,
                    spending,
                    rollupCache,
                    focusMonth,
                    rowMonths));
        }
        return rows;
    }

    private BudgetReportRowResponse row(
            CategoryRow category,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache,
            LocalDate focusMonth,
            List<LocalDate> rowMonths) {
        BudgetRowActual actual = amounts.actual(category, context, spending, rowMonths);
        BudgetRowBudget budget = budget(category, defaultByCategory, overrideByMonthCategory, focusMonth);
        BudgetRowTarget targetInfo = amounts.targetInfo(
                category,
                context,
                defaultByCategory,
                overrideByMonthCategory,
                rollupCache,
                rowMonths,
                actual.amount());
        BudgetRowCategory categoryInfo = categoryInfo(category, context);
        return new BudgetReportRowResponse(
                category.id(),
                category.name(),
                category.parentId(),
                categoryInfo.parentName(),
                categoryInfo.depth(),
                categoryInfo.hasChildren(),
                budget.defaultBudgetId(),
                budget.defaultAmount(),
                budget.overrideBudgetId(),
                budget.overrideAmount(),
                targetInfo.targetAmount(),
                BudgetMoney.format(actual.amount()),
                targetInfo.delta(),
                actual.transactionCount(),
                budget.defaultNotes(),
                budget.overrideNotes());
    }

    private BudgetRowBudget budget(
            CategoryRow category,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            LocalDate focusMonth) {
        BudgetDefault defaultBudget = defaultByCategory.get(category.id());
        BudgetOverride override = focusMonth == null
                ? null
                : overrideByMonthCategory.get(new MonthCategoryKey(focusMonth, category.id()));
        return new BudgetRowBudget(
                defaultBudget == null ? null : defaultBudget.id(),
                defaultBudget == null ? null : BudgetMoney.format(defaultBudget.amount()),
                override == null ? null : override.id(),
                override == null ? null : BudgetMoney.format(override.amount()),
                defaultBudget == null ? null : defaultBudget.notes(),
                override == null ? null : override.notes());
    }

    private BudgetRowCategory categoryInfo(CategoryRow category, CategoryContext context) {
        CategoryRow parent = category.parentId() == null ? null : context.categoryById().get(category.parentId());
        return new BudgetRowCategory(
                parent == null ? null : parent.name(),
                targets.depth(context, category),
                !context.childrenByParent().getOrDefault(category.id(), List.of()).isEmpty());
    }

}

record BudgetRowActual(BigDecimal amount, int transactionCount) {
}

record BudgetRowBudget(
        Long defaultBudgetId,
        String defaultAmount,
        Long overrideBudgetId,
        String overrideAmount,
        String defaultNotes,
        String overrideNotes) {
}

record BudgetRowCategory(String parentName, int depth, boolean hasChildren) {
}

record BudgetRowTarget(String targetAmount, String delta) {
}
