package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class GoalMoney {
    private GoalMoney() {
    }

    static String string(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
