package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class RuleMutations {
    private final RuleReader reader;
    private final RuleEngine ruleEngine;

    RuleMutations(RuleReader reader, RuleEngine ruleEngine) {
        this.reader = reader;
        this.ruleEngine = ruleEngine;
    }

    RuleEngine.RuleRecord create(Connection connection, RuleController.RuleRequest body) throws SQLException {
        validateReferences(connection, body.matchAccountId(), body.setCategoryId());
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
            setNullableLong(statement, 4, body.matchAccountId());
            statement.setString(5, blankToNull(body.matchDescriptionPattern()));
            statement.setBigDecimal(6, body.matchAmountMin());
            statement.setBigDecimal(7, body.matchAmountMax());
            setNullableLong(statement, 8, body.setCategoryId());
            statement.setString(9, blankToNull(body.setKind()));
            statement.setString(10, blankToNull(body.setMerchant()));
            statement.setString(11, ruleEngine.tagsJson(body.setTags()));
            statement.setString(12, blankToNull(body.notes()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return reader.get(connection, keys.getLong(1));
            }
        }
    }

    RuleEngine.RuleRecord update(Connection connection, long ruleId, JsonNode body) throws SQLException {
        requireRule(connection, ruleId);
        if (body.has("match_account_id") && !body.get("match_account_id").isNull()) {
            requireAccount(connection, body.get("match_account_id").asLong());
        }
        if (body.has("set_category_id") && !body.get("set_category_id").isNull()) {
            requireCategory(connection, body.get("set_category_id").asLong());
        }
        List<RuleColumnValue> values = patchValues(body);
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
        } catch (SQLException | RuntimeException exception) {
            rollback(connection);
            throw exception;
        }
    }

    Map<String, String> delete(Connection connection, long ruleId) throws SQLException {
        requireRule(connection, ruleId);
        try {
            connection.setAutoCommit(false);
            clearMatchedRuleIds(connection, List.of(ruleId));
            deleteRules(connection, List.of(ruleId));
            connection.commit();
            return Map.of("status", "deleted");
        } catch (SQLException | RuntimeException exception) {
            rollback(connection);
            throw exception;
        }
    }

    private void validateReferences(Connection connection, Long accountId, Long categoryId) throws SQLException {
        if (accountId != null) {
            requireAccount(connection, accountId);
        }
        if (categoryId != null) {
            requireCategory(connection, categoryId);
        }
    }

    private void requireRule(Connection connection, long ruleId) throws SQLException {
        requireExists(connection, "rules", ruleId, "rule not found");
    }

    private void requireAccount(Connection connection, long accountId) throws SQLException {
        requireExists(connection, "accounts", accountId, "account not found");
    }

    private void requireCategory(Connection connection, long categoryId) throws SQLException {
        requireExists(connection, "categories", categoryId, "category not found");
    }

    private void requireExists(Connection connection, String table, long id, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, message);
                }
            }
        }
    }

    private List<RuleColumnValue> patchValues(JsonNode body) {
        List<RuleColumnValue> values = new ArrayList<>();
        addText(values, body, "name");
        addInteger(values, body, "priority");
        addBoolean(values, body, "is_active");
        addLong(values, body, "match_account_id");
        addText(values, body, "match_description_pattern");
        addDecimal(values, body, "match_amount_min");
        addDecimal(values, body, "match_amount_max");
        addLong(values, body, "set_category_id");
        addText(values, body, "set_kind");
        addText(values, body, "set_merchant");
        addTags(values, body);
        addText(values, body, "notes");
        return values;
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
                bindParam(statement, index++, value.value());
            }
            statement.setLong(index, ruleId);
            statement.executeUpdate();
        }
    }

    private void addText(List<RuleColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new RuleColumnValue(field, node == null || node.isNull() ? null : blankToNull(node.asText())));
        }
    }

    private void addInteger(List<RuleColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new RuleColumnValue(field, node == null || node.isNull() ? null : node.asInt()));
        }
    }

    private void addBoolean(List<RuleColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new RuleColumnValue(field, node != null && !node.isNull() && node.asBoolean()));
        }
    }

    private void addLong(List<RuleColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new RuleColumnValue(field, node == null || node.isNull() ? null : node.asLong()));
        }
    }

    private void addDecimal(List<RuleColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new RuleColumnValue(
                    field,
                    node == null || node.isNull() || node.asText().isBlank() ? null : new BigDecimal(node.asText())));
        }
    }

    private void addTags(List<RuleColumnValue> values, JsonNode body) {
        if (body.has("set_tags")) {
            JsonNode node = body.get("set_tags");
            values.add(new RuleColumnValue("set_tags", node == null || node.isNull() ? null : node.toString()));
        }
    }

    private void clearMatchedRuleIds(Connection connection, List<Long> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET matched_rule_id = NULL WHERE matched_rule_id IN (" + placeholders(ids.size()) + ")")) {
            bindIds(statement, ids);
            statement.executeUpdate();
        }
    }

    private int deleteRules(Connection connection, List<Long> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM rules WHERE id IN (" + placeholders(ids.size()) + ")")) {
            bindIds(statement, ids);
            return statement.executeUpdate();
        }
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private void bindIds(PreparedStatement statement, List<Long> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            statement.setLong(i + 1, ids.get(i));
        }
    }

    private void bindParam(PreparedStatement statement, int index, Object value) throws SQLException {
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

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
