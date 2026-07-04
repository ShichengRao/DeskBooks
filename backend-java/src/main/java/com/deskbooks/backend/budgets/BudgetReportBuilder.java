package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BudgetReportBuilder {
    private final BudgetTargets targets = new BudgetTargets();
    private final BudgetReportRows rows = new BudgetReportRows(targets);
    private final BudgetReportCategories categories = new BudgetReportCategories();
    private final BudgetReportSpending spending = new BudgetReportSpending();
    private final BudgetReportSummaries summaries = new BudgetReportSummaries(targets);

    BudgetReportResponse report(
            Connection connection,
            LocalDate startValue,
            LocalDate endValue,
            LocalDate focusMonthValue) throws SQLException {
        List<LocalDate> months = BudgetReportMonths.range(startValue, endValue);
        if (months.isEmpty()) {
            months = List.of(BudgetReportMonths.normalize(startValue));
        }
        LocalDate focusMonth = BudgetReportMonths.focusWithinRange(focusMonthValue, months);

        CategoryContext context = categories.load(connection);
        BudgetReportSettings settings = BudgetReportSettings.load(connection, months.getFirst(), months.getLast());
        SpendingContext spendingContext = spending.collect(connection, months);

        Map<MonthCategoryKey, BigDecimal> rollupCache = new HashMap<>();
        List<LocalDate> rowMonths = focusMonth == null ? months : List.of(focusMonth);
        List<BudgetReportRowResponse> reportRows = rows.rows(
                context,
                settings.defaults(),
                settings.overrides(),
                spendingContext,
                rollupCache,
                focusMonth,
                rowMonths);

        List<BudgetMonthSummaryResponse> reportSummaries = summaries.summaries(
                months,
                context,
                settings.defaults(),
                settings.overrides(),
                spendingContext,
                rollupCache);
        BudgetRangeTotals rangeTotals = BudgetReportSummaries.rangeTotals(reportSummaries);

        return new BudgetReportResponse(
                months.getFirst(),
                months.getLast(),
                focusMonth,
                reportSummaries,
                BudgetMoney.format(rangeTotals.planned()),
                BudgetMoney.format(rangeTotals.actual()),
                BudgetMoney.format(rangeTotals.planned().subtract(rangeTotals.actual())),
                BudgetMoney.format(rangeTotals.budgetedActual()),
                BudgetMoney.format(rangeTotals.actual().subtract(rangeTotals.budgetedActual())),
                BudgetMoney.format(rangeTotals.uncategorized()),
                reportRows);
    }
}
