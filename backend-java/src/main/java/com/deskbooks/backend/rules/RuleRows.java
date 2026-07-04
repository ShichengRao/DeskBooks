package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class RuleRows {
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RuleRows() {
    }

    static RuleRecord from(ResultSet rs) throws SQLException {
        return new RuleRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("priority"),
                rs.getBoolean("is_active"),
                nullableLong(rs, "match_account_id"),
                rs.getString("match_description_pattern"),
                moneyString(rs.getBigDecimal("match_amount_min")),
                moneyString(rs.getBigDecimal("match_amount_max")),
                nullableLong(rs, "set_category_id"),
                rs.getString("set_kind"),
                rs.getString("set_merchant"),
                RuleTags.fromJson(rs.getString("set_tags")),
                rs.getString("notes"),
                rs.getInt("apply_count"),
                localDateTime(rs.getString("last_applied_at")));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String moneyString(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static LocalDateTime localDateTime(String value) {
        if (value == null) {
            return null;
        }
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDateTime.parse(value, SQLITE_TIMESTAMP);
    }
}
