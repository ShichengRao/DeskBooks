package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

final class NetWorthMoney {
    private static final String CREDIT = "credit";
    private static final String LIABILITY = "liability";

    private NetWorthMoney() {
    }

    static BigDecimal signedBalance(String category, BigDecimal balance) {
        if (CREDIT.equals(category) || LIABILITY.equals(category)) {
            return balance.abs().negate();
        }
        return balance;
    }

    static Map<String, String> stringify(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, format(value)));
        return out;
    }

    static String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
