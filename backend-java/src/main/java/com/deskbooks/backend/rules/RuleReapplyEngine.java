package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

final class RuleReapplyEngine {
    private final RuleEngine rules;

    RuleReapplyEngine(RuleEngine rules) {
        this.rules = rules;
    }

    RuleEngine.ReapplyResult reapplyToUnreviewed(Connection connection) throws SQLException {
        List<RuleEngine.RuleRecord> activeRules = rules.loadActiveRules(connection);
        if (activeRules.isEmpty()) {
            return new RuleEngine.ReapplyResult(0, 0);
        }
        Map<Long, Integer> fires = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, account_id, description_raw, description_normalized, merchant,
                       amount, category_id, kind
                FROM transactions
                WHERE is_user_categorized = 0
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                RuleEngine.RuleEval eval = rules.evaluate(
                        activeRules,
                        rs.getLong("account_id"),
                        firstNonNull(rs.getString("description_normalized"), rs.getString("description_raw"), ""),
                        rs.getBigDecimal("amount"));
                if (!eval.matched()) {
                    continue;
                }
                List<ColumnValue> values = changedValues(rs, eval);
                if (values.isEmpty()) {
                    continue;
                }
                values.add(new ColumnValue("matched_rule_id", eval.matchedRuleId()));
                updateTransaction(connection, rs.getLong("id"), values);
                fires.merge(eval.matchedRuleId(), 1, Integer::sum);
            }
        }
        rules.stampRuleFires(connection, expandFires(fires));
        int rowsChanged = fires.values().stream().mapToInt(Integer::intValue).sum();
        return new RuleEngine.ReapplyResult(rowsChanged, fires.size());
    }

    private List<ColumnValue> changedValues(ResultSet rs, RuleEngine.RuleEval eval) throws SQLException {
        List<ColumnValue> values = new ArrayList<>();
        Long currentCategoryId = nullableLong(rs, "category_id");
        if (eval.categoryId() != null && !eval.categoryId().equals(currentCategoryId)) {
            values.add(new ColumnValue("category_id", eval.categoryId()));
        }
        String currentKind = rs.getString("kind");
        if (eval.kind() != null && !eval.kind().equals(currentKind)) {
            values.add(new ColumnValue("kind", eval.kind()));
        }
        String currentMerchant = rs.getString("merchant");
        if (eval.merchant() != null && !eval.merchant().isBlank() && !eval.merchant().equals(currentMerchant)) {
            values.add(new ColumnValue("merchant", eval.merchant()));
        }
        return values;
    }

    private List<Long> expandFires(Map<Long, Integer> fires) {
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : fires.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    private void updateTransaction(Connection connection, long transactionId, List<ColumnValue> values) throws SQLException {
        StringJoiner assignments = new StringJoiner(", ");
        for (ColumnValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        assignments.add("updated_at = CURRENT_TIMESTAMP");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (ColumnValue value : values) {
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

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record ColumnValue(String column, Object value) {
    }
}
