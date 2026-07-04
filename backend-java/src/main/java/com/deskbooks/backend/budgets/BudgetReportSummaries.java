package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BudgetReportSummaries {
    private final BudgetTargets targets;

    BudgetReportSummaries(BudgetTargets targets) {
        this.targets = targets;
    }

    List<BudgetMonthSummaryResponse> summaries(
            List<LocalDate> months,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache) {
        List<BudgetMonthSummaryResponse> summaries = new ArrayList<>();
        for (LocalDate month : months) {
            summaries.add(monthSummary(
                    month,
                    context,
                    defaultByCategory,
                    overrideByMonthCategory,
                    spending,
                    rollupCache));
        }
        return summaries;
    }

    static BudgetRangeTotals rangeTotals(List<BudgetMonthSummaryResponse> summaries) {
        return new BudgetRangeTotals(
                total(summaries, BudgetMonthSummaryResponse::plannedTotal),
                total(summaries, BudgetMonthSummaryResponse::actualTotal),
                total(summaries, BudgetMonthSummaryResponse::budgetedActualTotal),
                total(summaries, BudgetMonthSummaryResponse::uncategorizedActual));
    }

    private BudgetMonthSummaryResponse monthSummary(
            LocalDate month,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache) {
        BudgetPlannedSummary planned = plannedSummary(
                month,
                context,
                defaultByCategory,
                overrideByMonthCategory,
                rollupCache);
        BigDecimal actualTotal = actualTotal(month, spending);
        BigDecimal budgetedActualTotal = budgetedActual(month, spending, planned.coveredCategoryIds());
        BigDecimal uncategorized = spending.uncategorizedByMonth().getOrDefault(month, BigDecimal.ZERO);
        return new BudgetMonthSummaryResponse(
                month,
                BudgetMoney.format(planned.amount()),
                BudgetMoney.format(actualTotal),
                BudgetMoney.format(planned.amount().subtract(actualTotal)),
                BudgetMoney.format(budgetedActualTotal),
                BudgetMoney.format(actualTotal.subtract(budgetedActualTotal)),
                BudgetMoney.format(uncategorized));
    }

    private BudgetPlannedSummary plannedSummary(
            LocalDate month,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
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
            if (target != null) {
                plannedTotal = plannedTotal.add(target);
                coveredCategoryIds.addAll(targets.descendants(context, root.id()));
            }
        }
        return new BudgetPlannedSummary(plannedTotal, coveredCategoryIds);
    }

    private BigDecimal actualTotal(LocalDate month, SpendingContext spending) {
        return spending.transactionRowsByMonth().getOrDefault(month, List.of()).stream()
                .map(TransactionSpending::spending)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal budgetedActual(LocalDate month, SpendingContext spending, Set<Long> coveredCategoryIds) {
        BigDecimal total = BigDecimal.ZERO;
        for (TransactionSpending row : spending.transactionRowsByMonth().getOrDefault(month, List.of())) {
            if (row.categoryId() != null && coveredCategoryIds.contains(row.categoryId())) {
                total = total.add(row.spending());
            }
        }
        return total;
    }

    private static BigDecimal total(
            List<BudgetMonthSummaryResponse> summaries,
            java.util.function.Function<BudgetMonthSummaryResponse, String> value) {
        return summaries.stream()
                .map(summary -> new BigDecimal(value.apply(summary)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

record BudgetPlannedSummary(BigDecimal amount, Set<Long> coveredCategoryIds) {
}

record BudgetRangeTotals(
        BigDecimal planned,
        BigDecimal actual,
        BigDecimal budgetedActual,
        BigDecimal uncategorized) {
}
