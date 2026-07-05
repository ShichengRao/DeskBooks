package com.deskbooks.backend.planning;

import static com.deskbooks.backend.planning.FireNumbers.decimal;
import static com.deskbooks.backend.planning.FireNumbers.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FireProjectionService {
    private static final List<String> PROJECTED_CATEGORIES = List.of(
            "bank", "investment", "tax_advantaged", "nonsense", "cash", "credit", "liability");

    private final FireProjectionTimeline timeline = new FireProjectionTimeline();

    FireProjectionResponse project(Connection connection, FireSettingsResponse settings, int maxYears)
            throws SQLException {
        Map<String, BigDecimal> currentByCategory = latestBalancesByCategory(connection);
        BigDecimal currentTotal = timeline.total(currentByCategory);
        BigDecimal targetTotal = targetTotal(settings);
        FireProjectionTimeline.FireTimeline years = timeline.build(currentByCategory, settings, targetTotal, maxYears);

        return new FireProjectionResponse(
                FireNumbers.moneyString(targetTotal),
                FireNumbers.moneyString(currentTotal),
                timeline.stringifyMoney(currentByCategory),
                years.retirementYear(),
                years.years(),
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

    private BigDecimal targetTotal(FireSettingsResponse settings) {
        BigDecimal withdrawalRate = new BigDecimal(settings.withdrawalRate());
        return withdrawalRate.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : new BigDecimal(settings.annualRetirementSpending()).divide(withdrawalRate, 2, RoundingMode.HALF_UP);
    }

    private List<String> notes(BigDecimal currentTotal) {
        if (currentTotal.compareTo(BigDecimal.ZERO) == 0) {
            return List.of("No net-worth snapshots yet; projection starts from $0.");
        }
        return List.of();
    }
}
