package com.deskbooks.backend.budgets;

import com.deskbooks.backend.foundation.ApiException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;

record BudgetWindow(LocalDate start, LocalDate end, LocalDate focusMonth) {
    static BudgetWindow from(LocalDate start, LocalDate end, LocalDate focusMonth, LocalDate month) {
        LocalDate effectiveStart = start;
        LocalDate effectiveEnd = end;
        LocalDate effectiveFocus = focusMonth;
        if (month != null && start == null && end == null) {
            effectiveStart = month;
            effectiveEnd = month;
            effectiveFocus = month;
        }
        if (effectiveStart == null || effectiveEnd == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "provide start/end or month");
        }
        if (effectiveEnd.isBefore(effectiveStart)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }
        return new BudgetWindow(effectiveStart, effectiveEnd, effectiveFocus);
    }
}
