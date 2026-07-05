package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

final class RuleReapplyUpdater {
    List<RuleReapplyColumnValue> changedValues(ResultSet rs, RuleEval eval) throws SQLException {
        List<RuleReapplyColumnValue> values = new ArrayList<>();
        Long currentCategoryId = nullableLong(rs, "category_id");
        if (eval.categoryId() != null && !eval.categoryId().equals(currentCategoryId)) {
            values.add(new RuleReapplyColumnValue("category_id", eval.categoryId()));
        }
        String currentKind = rs.getString("kind");
        if (eval.kind() != null && !eval.kind().equals(currentKind)) {
            values.add(new RuleReapplyColumnValue("kind", eval.kind()));
        }
        String currentMerchant = rs.getString("merchant");
        if (eval.merchant() != null && !eval.merchant().isBlank() && !eval.merchant().equals(currentMerchant)) {
            values.add(new RuleReapplyColumnValue("merchant", eval.merchant()));
        }
        return values;
    }

    void updateTransaction(Connection connection, long transactionId, List<RuleReapplyColumnValue> values)
            throws SQLException {
        StringJoiner assignments = new StringJoiner(", ");
        for (RuleReapplyColumnValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        assignments.add("updated_at = CURRENT_TIMESTAMP");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (RuleReapplyColumnValue value : values) {
                if (value.value() == null) {
                    statement.setObject(index++, null);
                } else {
                    statement.setObject(index++, value.value());
                }
            }
            statement.setLong(index, transactionId);
            statement.executeUpdate();
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

record RuleReapplyColumnValue(String column, Object value) {
}
