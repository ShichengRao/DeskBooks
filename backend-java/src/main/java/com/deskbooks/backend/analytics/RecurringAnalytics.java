package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

final class RecurringAnalytics {
    private RecurringAnalytics() {
    }

    static List<RecurringMerchantResponse> load(
            Connection connection,
            int minOccurrences,
            LocalDate start,
            LocalDate end) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(merchant, description_normalized) AS merchant,
                       COUNT(id) AS occurrences,
                       AVG(amount) AS avg_amount,
                       SUM(amount) AS total_amount,
                       MAX(date) AS last_seen,
                       MIN(date) AS first_seen
                FROM transactions
                WHERE COALESCE(merchant, description_normalized) IS NOT NULL
                  AND is_excluded_from_totals = 0
                  AND (? IS NULL OR date >= ?)
                  AND (? IS NULL OR date <= ?)
                GROUP BY COALESCE(merchant, description_normalized)
                HAVING COUNT(id) >= ?
                ORDER BY COUNT(id) DESC
                """)) {
            bindFilters(statement, minOccurrences, start, end);
            return responses(statement);
        }
    }

    private static void bindFilters(
            PreparedStatement statement,
            int minOccurrences,
            LocalDate start,
            LocalDate end) throws SQLException {
        String startValue = start == null ? null : start.toString();
        String endValue = end == null ? null : end.toString();
        statement.setString(1, startValue);
        statement.setString(2, startValue);
        statement.setString(3, endValue);
        statement.setString(4, endValue);
        statement.setInt(5, minOccurrences);
    }

    private static List<RecurringMerchantResponse> responses(PreparedStatement statement) throws SQLException {
        List<RecurringMerchantResponse> out = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                LocalDate firstSeen = nullableDate(rs.getString("first_seen"));
                LocalDate lastSeen = nullableDate(rs.getString("last_seen"));
                out.add(new RecurringMerchantResponse(
                        rs.getString("merchant"),
                        rs.getInt("occurrences"),
                        moneyString(rs.getBigDecimal("avg_amount")),
                        moneyString(rs.getBigDecimal("total_amount")),
                        lastSeen,
                        cadenceDays(firstSeen, lastSeen, rs.getInt("occurrences"))));
            }
        }
        return out;
    }

    private static LocalDate nullableDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static Double cadenceDays(LocalDate firstSeen, LocalDate lastSeen, int occurrences) {
        if (firstSeen == null || lastSeen == null || occurrences <= 1) {
            return null;
        }
        long spanDays = ChronoUnit.DAYS.between(firstSeen, lastSeen);
        if (spanDays <= 0) {
            return null;
        }
        return spanDays / (double) (occurrences - 1);
    }

    private static String moneyString(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
