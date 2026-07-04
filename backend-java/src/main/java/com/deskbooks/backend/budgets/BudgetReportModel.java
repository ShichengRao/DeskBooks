package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

record CategoryRow(long id, String name, Long parentId, int sortOrder) {
}

record CategoryContext(
        List<CategoryRow> roots,
        List<CategoryRow> orderedCategories,
        Map<Long, CategoryRow> categoryById,
        Map<Long, List<CategoryRow>> childrenByParent) {
}

record BudgetDefault(long id, long categoryId, BigDecimal amount, String notes) {
}

record BudgetOverride(long id, LocalDate month, long categoryId, BigDecimal amount, String notes) {
}

record MonthCategoryKey(LocalDate month, long categoryId) {
}

record TransactionSpending(Long categoryId, BigDecimal spending) {
}

record SpendingContext(
        Map<MonthCategoryKey, BigDecimal> actualByMonthExact,
        Map<MonthCategoryKey, Integer> countByMonthExact,
        Map<LocalDate, List<TransactionSpending>> transactionRowsByMonth,
        Map<LocalDate, BigDecimal> uncategorizedByMonth) {
}
