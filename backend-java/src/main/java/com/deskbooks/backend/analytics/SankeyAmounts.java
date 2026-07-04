package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

final class SankeyAmounts {
    private SankeyAmounts() {
    }

    static BigDecimal sumValues(Map<String, BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values.values()) {
            total = total.add(value);
        }
        return total;
    }

    static List<Map.Entry<String, BigDecimal>> sortedEntriesDescending(Map<String, BigDecimal> values) {
        return values.entrySet().stream()
                .sorted((left, right) -> right.getValue().compareTo(left.getValue()))
                .toList();
    }

    static BigDecimal apportionedValue(BigDecimal shareBasis, BigDecimal totalShare, BigDecimal totalValue) {
        return shareBasis.multiply(totalValue).divide(totalShare, 10, RoundingMode.HALF_UP);
    }
}
