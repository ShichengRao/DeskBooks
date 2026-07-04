package com.deskbooks.backend.planning;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

final class PlanningRows {
    private static final Pattern EPOCH_MILLIS = Pattern.compile("\\d{10,}");
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PlanningRows() {
    }

    static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return null;
        }
        if (EPOCH_MILLIS.matcher(value).matches()) {
            return Instant.ofEpochMilli(Long.parseLong(value))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }
        return LocalDate.parse(value);
    }

    static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return null;
        }
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDateTime.parse(value, SQLITE_TIMESTAMP);
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
