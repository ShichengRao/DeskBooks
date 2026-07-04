package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class BudgetMoney {
    private BudgetMoney() {
    }

    static String format(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    static String formatOrNull(BigDecimal value) {
        return value == null ? null : format(value);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
