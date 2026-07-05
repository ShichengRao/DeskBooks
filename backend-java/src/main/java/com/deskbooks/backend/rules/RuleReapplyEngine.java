package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuleReapplyEngine {
    private final RuleEngine rules;
    private final RuleReapplyUpdater updater = new RuleReapplyUpdater();

    RuleReapplyEngine(RuleEngine rules) {
        this.rules = rules;
    }

    ReapplyResult reapplyToUnreviewed(Connection connection) throws SQLException {
        List<RuleRecord> activeRules = rules.loadActiveRules(connection);
        if (activeRules.isEmpty()) {
            return new ReapplyResult(0, 0);
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
                RuleEval eval = rules.evaluate(
                        activeRules,
                        rs.getLong("account_id"),
                        firstNonNull(rs.getString("description_normalized"), rs.getString("description_raw"), ""),
                        rs.getBigDecimal("amount"));
                if (!eval.matched()) {
                    continue;
                }
                List<RuleReapplyColumnValue> values = updater.changedValues(rs, eval);
                if (values.isEmpty()) {
                    continue;
                }
                values.add(new RuleReapplyColumnValue("matched_rule_id", eval.matchedRuleId()));
                updater.updateTransaction(connection, rs.getLong("id"), values);
                fires.merge(eval.matchedRuleId(), 1, Integer::sum);
            }
        }
        rules.stampRuleFires(connection, expandFires(fires));
        int rowsChanged = fires.values().stream().mapToInt(Integer::intValue).sum();
        return new ReapplyResult(rowsChanged, fires.size());
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

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
