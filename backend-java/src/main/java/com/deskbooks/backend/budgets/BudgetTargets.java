package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BudgetTargets {
    BigDecimal rollupTargetFor(
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
        BigDecimal target = childRollupTarget(month, categoryId, context, defaultByCategory, overrideByMonthCategory, cache);
        if (target == null) {
            target = directTargetFor(month, categoryId, defaultByCategory, overrideByMonthCategory);
        }
        cache.put(key, target);
        return target;
    }

    Set<Long> descendants(CategoryContext context, long categoryId) {
        Set<Long> out = new LinkedHashSet<>();
        out.add(categoryId);
        for (CategoryRow child : context.childrenByParent().getOrDefault(categoryId, List.of())) {
            out.addAll(descendants(context, child.id()));
        }
        return out;
    }

    int depth(CategoryContext context, CategoryRow category) {
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

    private BigDecimal childRollupTarget(
            LocalDate month,
            long categoryId,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            Map<MonthCategoryKey, BigDecimal> cache) {
        List<BigDecimal> childTargets = new ArrayList<>();
        for (CategoryRow child : context.childrenByParent().getOrDefault(categoryId, List.of())) {
            BigDecimal target = rollupTargetFor(
                    month,
                    child.id(),
                    context,
                    defaultByCategory,
                    overrideByMonthCategory,
                    cache);
            if (target != null) {
                childTargets.add(target);
            }
        }
        return childTargets.isEmpty()
                ? null
                : childTargets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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
}
