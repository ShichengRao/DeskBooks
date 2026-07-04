package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReconcileMoney {
    private ReconcileMoney() {
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    static String moneyStringOrNull(BigDecimal value) {
        return value == null ? null : moneyString(value);
    }

    static Map<String, String> stringify(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            out.put(entry.getKey(), moneyString(entry.getValue()));
        }
        return out;
    }
}
