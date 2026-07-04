package com.deskbooks.backend.planning;

import static com.deskbooks.backend.planning.FireNumbers.decimal;
import static com.deskbooks.backend.planning.FireNumbers.moneyString;
import static com.deskbooks.backend.planning.FireNumbers.rateString;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class FireSettingsStore {
    void ensure(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO fire_settings (id) VALUES (1)
                """)) {
            insert.executeUpdate();
        }
    }

    FireSettingsResponse get(Connection connection) throws SQLException {
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
                    PlanningRows.localDateTime(rs, "updated_at"));
        }
    }

    FireSettingsResponse update(Connection connection, FireSettingsRequest body) throws SQLException {
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
        return get(connection);
    }
}
