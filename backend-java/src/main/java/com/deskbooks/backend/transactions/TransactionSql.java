package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

final class TransactionSql {
    private TransactionSql() {
    }

    static int bindParams(PreparedStatement statement, List<?> params, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object param : params) {
            bindParam(statement, index++, param);
        }
        return index;
    }

    static void bindParam(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof LocalDate localDate) {
            statement.setString(index, localDate.toString());
        } else if (value instanceof BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof Long longValue) {
            statement.setLong(index, longValue);
        } else if (value instanceof Integer intValue) {
            statement.setInt(index, intValue);
        } else if (value instanceof Boolean boolValue) {
            statement.setBoolean(index, boolValue);
        } else {
            statement.setObject(index, value);
        }
    }

    static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
