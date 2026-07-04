package com.deskbooks.backend.budgets;

import java.time.LocalDate;
import java.util.List;

record BudgetMonthSummaryResponse(
        LocalDate month,
        String plannedTotal,
        String actualTotal,
        String deltaTotal,
        String budgetedActualTotal,
        String unbudgetedActualTotal,
        String uncategorizedActual) {
}

record BudgetReportRowResponse(
        long categoryId,
        String categoryName,
        Long parentId,
        String parentName,
        int depth,
        boolean hasChildren,
        Long defaultBudgetId,
        String defaultAmount,
        Long overrideBudgetId,
        String overrideAmount,
        String targetAmount,
        String actualAmount,
        String delta,
        int transactionCount,
        String defaultNotes,
        String overrideNotes) {
}

record BudgetReportResponse(
        LocalDate start,
        LocalDate end,
        LocalDate focusMonth,
        List<BudgetMonthSummaryResponse> months,
        String plannedTotal,
        String actualTotal,
        String deltaTotal,
        String budgetedActualTotal,
        String unbudgetedActualTotal,
        String uncategorizedActual,
        List<BudgetReportRowResponse> rows) {
}
