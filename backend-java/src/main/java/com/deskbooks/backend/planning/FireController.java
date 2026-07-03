package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/fire")
class FireController {
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final SqliteConnectionProvider connections;

    FireController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("/settings")
    FireSettingsResponse getSettings() {
        try (Connection connection = connections.open()) {
            ensureSettings(connection);
            return settings(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/settings")
    FireSettingsResponse putSettings(@Valid @RequestBody FireSettingsRequest body) {
        try (Connection connection = connections.open()) {
            ensureSettings(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE fire_settings
                    SET growth_bank = ?,
                        growth_investment = ?,
                        growth_tax_advantaged = ?,
                        growth_nonsense = ?,
                        growth_cash = ?,
                        growth_credit = ?,
                        annual_retirement_spending = ?,
                        withdrawal_rate = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = 1
                    """)) {
                statement.setBigDecimal(1, body.growthBank());
                statement.setBigDecimal(2, body.growthInvestment());
                statement.setBigDecimal(3, body.growthTaxAdvantaged());
                statement.setBigDecimal(4, body.growthNonsense());
                statement.setBigDecimal(5, body.growthCash());
                statement.setBigDecimal(6, body.growthCredit());
                statement.setBigDecimal(7, body.annualRetirementSpending());
                statement.setBigDecimal(8, body.withdrawalRate());
                statement.executeUpdate();
            }
            return settings(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/projection")
    FireProjectionResponse projection(@RequestParam(name = "max_years", defaultValue = "60") int maxYears) {
        try (Connection connection = connections.open()) {
            ensureSettings(connection);
            FireSettingsResponse settings = settings(connection);
            Map<String, BigDecimal> currentByCategory = latestBalancesByCategory(connection);
            BigDecimal currentTotal = currentByCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal withdrawalRate = new BigDecimal(settings.withdrawalRate());
            BigDecimal targetTotal = withdrawalRate.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : new BigDecimal(settings.annualRetirementSpending()).divide(withdrawalRate, 2, RoundingMode.HALF_UP);

            List<FireProjectionYearResponse> years = new ArrayList<>();
            Map<String, BigDecimal> running = new LinkedHashMap<>(currentByCategory);
            Integer retirementYear = null;
            int startYear = Year.now().getValue();
            int boundedYears = Math.max(0, Math.min(maxYears, 100));
            for (int i = 0; i <= boundedYears; i++) {
                int year = startYear + i;
                BigDecimal total = running.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                double pct = targetTotal.compareTo(BigDecimal.ZERO) == 0
                        ? 0
                        : total.divide(targetTotal, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).doubleValue();
                if (retirementYear == null && targetTotal.compareTo(BigDecimal.ZERO) > 0 && total.compareTo(targetTotal) >= 0) {
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

            List<String> notes = new ArrayList<>();
            if (currentTotal.compareTo(BigDecimal.ZERO) == 0) {
                notes.add("No net-worth snapshots yet; projection starts from $0.");
            }
            return new FireProjectionResponse(
                    moneyString(targetTotal),
                    moneyString(currentTotal),
                    stringifyMoney(currentByCategory),
                    retirementYear,
                    years,
                    notes);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private void ensureSettings(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO fire_settings (id) VALUES (1)
                """)) {
            insert.executeUpdate();
        }
    }

    private FireSettingsResponse settings(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM fire_settings WHERE id = 1");
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return new FireSettingsResponse(
                    rateString(decimal(rs, "growth_bank")),
                    rateString(decimal(rs, "growth_investment")),
                    rateString(decimal(rs, "growth_tax_advantaged")),
                    rateString(decimal(rs, "growth_nonsense")),
                    rateString(decimal(rs, "growth_cash")),
                    rateString(decimal(rs, "growth_credit")),
                    moneyString(decimal(rs, "annual_retirement_spending")),
                    rateString(decimal(rs, "withdrawal_rate")),
                    PlanningSql.localDateTime(rs, "updated_at"));
        }
    }

    private Map<String, BigDecimal> latestBalancesByCategory(Connection connection) throws SQLException {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.account_category, COALESCE(SUM(ab.balance), 0) AS total
                FROM account_balances ab
                JOIN accounts a ON a.id = ab.account_id
                WHERE ab.snapshot_id = (
                  SELECT id FROM net_worth_snapshots ORDER BY snapshot_date DESC LIMIT 1
                )
                GROUP BY a.account_category
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                totals.put(rs.getString("account_category"), money(decimal(rs, "total")));
            }
        }
        for (String key : List.of("bank", "investment", "tax_advantaged", "nonsense", "cash", "credit")) {
            totals.putIfAbsent(key, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        return totals;
    }

    private Map<String, BigDecimal> grow(Map<String, BigDecimal> current, FireSettingsResponse settings) {
        Map<String, BigDecimal> next = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : current.entrySet()) {
            BigDecimal rate = switch (entry.getKey()) {
                case "bank" -> new BigDecimal(settings.growthBank());
                case "investment" -> new BigDecimal(settings.growthInvestment());
                case "tax_advantaged" -> new BigDecimal(settings.growthTaxAdvantaged());
                case "nonsense" -> new BigDecimal(settings.growthNonsense());
                case "cash" -> new BigDecimal(settings.growthCash());
                case "credit" -> new BigDecimal(settings.growthCredit());
                default -> BigDecimal.ZERO;
            };
            next.put(entry.getKey(), money(entry.getValue().multiply(ONE.add(rate))));
        }
        return next;
    }

    private Map<String, String> stringifyMoney(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, moneyString(value)));
        return out;
    }

    private BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyString(BigDecimal value) {
        return money(value).toPlainString();
    }

    private String rateString(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record FireSettingsRequest(
            @NotNull BigDecimal growthBank,
            @NotNull BigDecimal growthInvestment,
            @NotNull BigDecimal growthTaxAdvantaged,
            @NotNull BigDecimal growthNonsense,
            @NotNull BigDecimal growthCash,
            @NotNull BigDecimal growthCredit,
            @NotNull BigDecimal annualRetirementSpending,
            @NotNull BigDecimal withdrawalRate) {
    }

    record FireSettingsResponse(
            String growthBank,
            String growthInvestment,
            String growthTaxAdvantaged,
            String growthNonsense,
            String growthCash,
            String growthCredit,
            String annualRetirementSpending,
            String withdrawalRate,
            LocalDateTime updatedAt) {
    }

    record FireProjectionYearResponse(
            int year,
            Integer age,
            String total,
            Map<String, String> byCategory,
            double pctOfTarget) {
    }

    record FireProjectionResponse(
            String targetTotal,
            String currentTotal,
            Map<String, String> currentByCategory,
            Integer retirementYear,
            List<FireProjectionYearResponse> years,
            List<String> notes) {
    }
}
