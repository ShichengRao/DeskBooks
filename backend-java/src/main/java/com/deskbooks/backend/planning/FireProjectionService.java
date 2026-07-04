package com.deskbooks.backend.planning;

import static com.deskbooks.backend.planning.FireNumbers.decimal;
import static com.deskbooks.backend.planning.FireNumbers.money;
import static com.deskbooks.backend.planning.FireNumbers.moneyString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FireProjectionService {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal PERCENT = new BigDecimal("100");
    private static final List<String> PROJECTED_CATEGORIES = List.of(
            "bank", "investment", "tax_advantaged", "nonsense", "cash", "credit", "liability");

    FireProjectionResponse project(Connection connection, FireSettingsResponse settings, int maxYears)
            throws SQLException {
        Map<String, BigDecimal> currentByCategory = latestBalancesByCategory(connection);
        BigDecimal currentTotal = total(currentByCategory);
        BigDecimal targetTotal = targetTotal(settings);
        FireTimeline timeline = timeline(currentByCategory, settings, targetTotal, maxYears);

        return new FireProjectionResponse(
                moneyString(targetTotal),
                moneyString(currentTotal),
                stringifyMoney(currentByCategory),
                timeline.retirementYear(),
                timeline.years(),
                notes(currentTotal));
    }

    private Map<String, BigDecimal> latestBalancesByCategory(Connection connection) throws SQLException {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                  a.account_category,
                  COALESCE(SUM(
                    CASE
                      WHEN a.account_category IN ('credit', 'liability') THEN -ABS(ab.balance)
                      ELSE ab.balance
                    END
                  ), 0) AS total
                FROM account_balances ab
                JOIN accounts a ON a.id = ab.account_id
                WHERE ab.snapshot_id = (
                  SELECT id FROM net_worth_snapshots ORDER BY snapshot_date DESC LIMIT 1
                )
                  AND ab.balance IS NOT NULL
                GROUP BY a.account_category
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                totals.put(rs.getString("account_category"), money(decimal(rs, "total")));
            }
        }
        PROJECTED_CATEGORIES.forEach(key -> totals.putIfAbsent(key, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)));
        return totals;
    }

    private FireTimeline timeline(
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

    private BigDecimal targetTotal(FireSettingsResponse settings) {
        BigDecimal withdrawalRate = new BigDecimal(settings.withdrawalRate());
        return withdrawalRate.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : new BigDecimal(settings.annualRetirementSpending()).divide(withdrawalRate, 2, RoundingMode.HALF_UP);
    }

    private double pctOfTarget(BigDecimal total, BigDecimal targetTotal) {
        return targetTotal.compareTo(BigDecimal.ZERO) == 0
                ? 0
                : total.divide(targetTotal, 6, RoundingMode.HALF_UP).multiply(PERCENT).doubleValue();
    }

    private boolean reachedTarget(BigDecimal total, BigDecimal targetTotal) {
        return targetTotal.compareTo(BigDecimal.ZERO) > 0 && total.compareTo(targetTotal) >= 0;
    }

    private BigDecimal total(Map<String, BigDecimal> values) {
        return values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, String> stringifyMoney(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, moneyString(value)));
        return out;
    }

    private List<String> notes(BigDecimal currentTotal) {
        if (currentTotal.compareTo(BigDecimal.ZERO) == 0) {
            return List.of("No net-worth snapshots yet; projection starts from $0.");
        }
        return List.of();
    }

    private record FireTimeline(List<FireProjectionYearResponse> years, Integer retirementYear) {
    }
}
