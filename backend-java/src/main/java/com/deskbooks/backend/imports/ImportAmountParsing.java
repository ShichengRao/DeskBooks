package com.deskbooks.backend.imports;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class ImportAmountParsing {
    private ImportAmountParsing() {
    }

    static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    static String moneyString(BigDecimal value) {
        return money(value).toPlainString();
    }

    static BigDecimal parseAmount(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replace("$", "").replace(",", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            cleaned = "-" + cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
