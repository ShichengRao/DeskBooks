package com.deskbooks.backend.planning;

import static com.deskbooks.backend.planning.FireNumbers.money;
import static com.deskbooks.backend.planning.FireNumbers.moneyString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FireProjectionTimeline {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal PERCENT = new BigDecimal("100");

    FireTimeline build(
            Map<String, BigDecimal> currentByCategory,
            FireSettingsResponse settings,
            BigDecimal targetTotal,
            int maxYears) {
        List<FireProjectionYearResponse> years = new ArrayList<>();
        Map<String, BigDecimal> running = new LinkedHashMap<>(currentByCategory);
        Integer retirementYear = null;
        int startYear = Year.now().getValue();
        int boundedYears = Math.max(0, Math.min(maxYears, 100));
        for (int i = 0; i <= boundedYears; i++) {
            int year = startYear + i;
            BigDecimal total = total(running);
            double pct = pctOfTarget(total, targetTotal);
            if (retirementYear == null && reachedTarget(total, targetTotal)) {
                retirementYear = year;
            }
            years.add(new FireProjectionYearResponse(
                    year,
                    null,
                    moneyString(total),
                    stringifyMoney(running),
                    pct));
            running = grow(running, settings);
        }
        return new FireTimeline(years, retirementYear);
    }

    BigDecimal total(Map<String, BigDecimal> values) {
        return values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    Map<String, String> stringifyMoney(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, moneyString(value)));
        return out;
    }

    private Map<String, BigDecimal> grow(Map<String, BigDecimal> current, FireSettingsResponse settings) {
        Map<String, BigDecimal> next = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : current.entrySet()) {
            next.put(entry.getKey(), money(entry.getValue().multiply(ONE.add(rateFor(entry.getKey(), settings)))));
        }
        return next;
    }

    private BigDecimal rateFor(String accountCategory, FireSettingsResponse settings) {
        return switch (accountCategory) {
            case "bank" -> new BigDecimal(settings.growthBank());
            case "investment" -> new BigDecimal(settings.growthInvestment());
            case "tax_advantaged" -> new BigDecimal(settings.growthTaxAdvantaged());
            case "nonsense" -> new BigDecimal(settings.growthNonsense());
            case "cash" -> new BigDecimal(settings.growthCash());
            case "credit", "liability" -> new BigDecimal(settings.growthCredit());
            default -> BigDecimal.ZERO;
        };
    }

    private double pctOfTarget(BigDecimal total, BigDecimal targetTotal) {
        return targetTotal.compareTo(BigDecimal.ZERO) == 0
                ? 0
                : total.divide(targetTotal, 6, RoundingMode.HALF_UP).multiply(PERCENT).doubleValue();
    }

    private boolean reachedTarget(BigDecimal total, BigDecimal targetTotal) {
        return targetTotal.compareTo(BigDecimal.ZERO) > 0 && total.compareTo(targetTotal) >= 0;
    }

    record FireTimeline(List<FireProjectionYearResponse> years, Integer retirementYear) {
    }
}
