package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

final class RuleSql {
    private RuleSql() {
    }

    static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    static void bindParam(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof Long longValue) {
            statement.setLong(index, longValue);
        } else if (value instanceof Integer intValue) {
            statement.setInt(index, intValue);
        } else if (value instanceof Boolean bool) {
            statement.setBoolean(index, bool);
        } else {
            statement.setObject(index, value);
        }
    }

    static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    static void bindIds(PreparedStatement statement, List<Long> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            statement.setLong(i + 1, ids.get(i));
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
