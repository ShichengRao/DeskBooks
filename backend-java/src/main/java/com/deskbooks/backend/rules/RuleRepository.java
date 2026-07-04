package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class RuleRepository {
    List<RuleRecord> loadActive(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, priority, is_active, match_account_id, match_description_pattern,
                       match_amount_min, match_amount_max, set_category_id, set_kind, set_merchant,
                       set_tags, notes, apply_count, last_applied_at
                FROM rules
                WHERE is_active = 1
                ORDER BY priority ASC
                """);
                ResultSet rs = statement.executeQuery()) {
            List<RuleRecord> rules = new ArrayList<>();
            while (rs.next()) {
                rules.add(RuleRows.from(rs));
            }
            return rules;
        }
    }
}
