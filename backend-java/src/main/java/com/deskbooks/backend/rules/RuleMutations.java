package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;

final class RuleMutations {
    private final RuleReader reader;
    private final RuleEngine ruleEngine;
    private final RuleLookup lookup = new RuleLookup();
    private final RulePatchBuilder patches = new RulePatchBuilder(lookup);

    RuleMutations(RuleReader reader, RuleEngine ruleEngine) {
        this.reader = reader;
        this.ruleEngine = ruleEngine;
    }

    RuleEngine.RuleRecord create(Connection connection, RuleController.RuleRequest body) throws SQLException {
        lookup.validateReferences(connection, body.matchAccountId(), body.setCategoryId());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rules (
                  name, priority, is_active, match_account_id, match_description_pattern,
                  match_amount_min, match_amount_max, set_category_id, set_kind,
                  set_merchant, set_tags, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, body.name());
            statement.setInt(2, body.priority() == null ? 100 : body.priority());
            statement.setBoolean(3, body.isActive() == null || body.isActive());
            RuleSql.setNullableLong(statement, 4, body.matchAccountId());
            statement.setString(5, RuleSql.blankToNull(body.matchDescriptionPattern()));
            statement.setBigDecimal(6, body.matchAmountMin());
            statement.setBigDecimal(7, body.matchAmountMax());
            RuleSql.setNullableLong(statement, 8, body.setCategoryId());
            statement.setString(9, RuleSql.blankToNull(body.setKind()));
            statement.setString(10, RuleSql.blankToNull(body.setMerchant()));
            statement.setString(11, ruleEngine.tagsJson(body.setTags()));
            statement.setString(12, RuleSql.blankToNull(body.notes()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return reader.get(connection, keys.getLong(1));
            }
        }
    }

    RuleEngine.RuleRecord update(Connection connection, long ruleId, JsonNode body) throws SQLException {
        lookup.requireRule(connection, ruleId);
        List<RuleColumnValue> values = patches.patchValues(connection, body);
        if (!values.isEmpty()) {
            applyRuleUpdate(connection, ruleId, values);
        }
        return reader.get(connection, ruleId);
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

    private void applyRuleUpdate(
            Connection connection,
            long ruleId,
            List<RuleColumnValue> values) throws SQLException {
        StringJoiner assignments = new StringJoiner(", ");
        for (RuleColumnValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE rules SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (RuleColumnValue value : values) {
                RuleSql.bindParam(statement, index++, value.value());
            }
            statement.setLong(index, ruleId);
            statement.executeUpdate();
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

record RuleColumnValue(String column, Object value) {
}
