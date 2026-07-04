package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class RuleReader {
    private static final String SELECT_COLUMNS = """
            id, name, priority, is_active, match_account_id, match_description_pattern,
            match_amount_min, match_amount_max, set_category_id, set_kind, set_merchant,
            set_tags, notes, apply_count, last_applied_at
            """;

    List<RuleRecord> list(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s
                FROM rules
                ORDER BY priority ASC
                """.formatted(SELECT_COLUMNS));
                ResultSet rs = statement.executeQuery()) {
            List<RuleRecord> rules = new ArrayList<>();
            while (rs.next()) {
                rules.add(RuleRows.from(rs));
            }
            return rules;
        }
    }

    RuleRecord get(Connection connection, long ruleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s
                FROM rules
                WHERE id = ?
                """.formatted(SELECT_COLUMNS))) {
            statement.setLong(1, ruleId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "rule not found");
                }
                return RuleRows.from(rs);
            }
        }
    }
}
