package com.deskbooks.backend.budgets;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class BudgetReportMonths {
    private BudgetReportMonths() {
    }

    static List<LocalDate> range(LocalDate start, LocalDate end) {
        LocalDate current = normalize(start);
        LocalDate finalMonth = normalize(end);
        List<LocalDate> out = new ArrayList<>();
        while (!current.isAfter(finalMonth)) {
            out.add(current);
            current = endExclusive(current);
        }
        return out;
    }

    static LocalDate focusWithinRange(LocalDate focusMonthValue, List<LocalDate> months) {
        LocalDate focusMonth = focusMonthValue == null ? null : normalize(focusMonthValue);
        if (focusMonth != null && (focusMonth.isBefore(months.getFirst()) || focusMonth.isAfter(months.getLast()))) {
            return months.getLast();
        }
        return focusMonth;
    }

    static LocalDate normalize(LocalDate value) {
        return BudgetSqlValues.normalizeMonth(value);
    }

    static LocalDate endExclusive(LocalDate month) {
        return normalize(month).plusMonths(1);
    }
}
