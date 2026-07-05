package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

final class RuleDeletionMutations {
    private final RuleLookup lookup;

    RuleDeletionMutations(RuleLookup lookup) {
        this.lookup = lookup;
    }

    Map<String, Integer> bulkDelete(Connection connection, RuleController.RuleBulkDelete body) throws SQLException {
        List<Long> ids = body.ids() == null ? List.of() : body.ids().stream().distinct().sorted().toList();
        if (ids.isEmpty()) {
            return Map.of("deleted", 0);
        }
        try {
            connection.setAutoCommit(false);
            clearMatchedRuleIds(connection, ids);
            int deleted = deleteRules(connection, ids);
            connection.commit();
            return Map.of("deleted", deleted);
        } catch (SQLException exception) {
            rollback(connection);
            throw exception;
        }
    }

    Map<String, String> delete(Connection connection, long ruleId) throws SQLException {
        lookup.requireRule(connection, ruleId);
        try {
            connection.setAutoCommit(false);
            clearMatchedRuleIds(connection, List.of(ruleId));
            deleteRules(connection, List.of(ruleId));
            connection.commit();
            return Map.of("status", "deleted");
        } catch (SQLException exception) {
            rollback(connection);
            throw exception;
        }
    }

    private void clearMatchedRuleIds(Connection connection, List<Long> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET matched_rule_id = NULL WHERE matched_rule_id IN (" + RuleSql.placeholders(ids.size()) + ")")) {
            RuleSql.bindIds(statement, ids);
            statement.executeUpdate();
        }
    }

    private int deleteRules(Connection connection, List<Long> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM rules WHERE id IN (" + RuleSql.placeholders(ids.size()) + ")")) {
            RuleSql.bindIds(statement, ids);
            return statement.executeUpdate();
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
