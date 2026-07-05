package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BudgetReportRowAmounts {
    private final BudgetTargets targets;

    BudgetReportRowAmounts(BudgetTargets targets) {
        this.targets = targets;
    }

    BudgetRowActual actual(
            CategoryRow category,
            CategoryContext context,
            SpendingContext spending,
            List<LocalDate> rowMonths) {
        Set<Long> ids = targets.descendants(context, category.id());
        BigDecimal actual = BigDecimal.ZERO;
        int transactionCount = 0;
        for (LocalDate rowMonth : rowMonths) {
            for (Long id : ids) {
                MonthCategoryKey key = new MonthCategoryKey(rowMonth, id);
                actual = actual.add(spending.actualByMonthExact().getOrDefault(key, BigDecimal.ZERO));
                transactionCount += spending.countByMonthExact().getOrDefault(key, 0);
            }
        }
        return new BudgetRowActual(actual, transactionCount);
    }

    BudgetRowTarget targetInfo(
            CategoryRow category,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            Map<MonthCategoryKey, BigDecimal> rollupCache,
            List<LocalDate> rowMonths,
            BigDecimal actual) {
        BigDecimal target = targetForRow(
                category,
                context,
                defaultByCategory,
                overrideByMonthCategory,
                rollupCache,
                rowMonths);
        return new BudgetRowTarget(
                BudgetMoney.formatOrNull(target),
                target == null ? null : BudgetMoney.format(target.subtract(actual)));
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
            BigDecimal target = targets.rollupTargetFor(
                    rowMonth,
                    category.id(),
                    context,
                    defaultByCategory,
                    overrideByMonthCategory,
                    rollupCache);
            if (target != null) {
                rowTargets.add(target);
            }
        }
        return rowTargets.isEmpty()
                ? null
                : rowTargets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
