package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuleFireStamper {
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    void stamp(Connection connection, List<Long> ruleIds) throws SQLException {
        Map<Long, Integer> counts = countsByRule(ruleIds);
        if (counts.isEmpty()) {
            return;
        }
        String now = LocalDateTime.now().format(SQLITE_TIMESTAMP);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE rules
                SET apply_count = apply_count + ?, last_applied_at = ?
                WHERE id = ?
                """)) {
            for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
                statement.setInt(1, entry.getValue());
                statement.setString(2, now);
                statement.setLong(3, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Map<Long, Integer> countsByRule(List<Long> ruleIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long ruleId : ruleIds) {
            if (ruleId != null) {
                counts.merge(ruleId, 1, Integer::sum);
            }
        }
        return counts;
    }
}
