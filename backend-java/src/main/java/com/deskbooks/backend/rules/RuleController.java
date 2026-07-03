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

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/rules")
class RuleController {
    private final SqliteConnectionProvider connections;
    private final RuleEngine ruleEngine;

    RuleController(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.ruleEngine = ruleEngine;
    }

    @GetMapping("")
    List<RuleEngine.RuleRecord> listRules() {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, name, priority, is_active, match_account_id, match_description_pattern,
                               match_amount_min, match_amount_max, set_category_id, set_kind, set_merchant,
                               set_tags, notes, apply_count, last_applied_at
                        FROM rules
                        ORDER BY priority ASC
                        """);
                ResultSet rs = statement.executeQuery()) {
            List<RuleEngine.RuleRecord> rules = new ArrayList<>();
            while (rs.next()) {
                rules.add(ruleEngine.ruleFrom(rs));
            }
            return rules;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/proposals")
    List<RuleEngine.RuleProposal> listRuleProposals(
            @RequestParam(name = "min_support", defaultValue = "3") int minSupport,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        try (Connection connection = connections.open()) {
            return ruleEngine.generateRuleProposals(connection, Math.max(1, minSupport), Math.max(0, limit));
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/coverage")
    RuleEngine.RuleCoverage coverage() {
        try (Connection connection = connections.open()) {
            return ruleEngine.coverageSummary(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/proposals/backtest")
    RuleEngine.RuleProposal backtestRuleProposal(@Valid @RequestBody RuleEngine.RuleProposalRequest body) {
        try (Connection connection = connections.open()) {
            return ruleEngine.backtestRuleProposal(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/proposals/reject")
    Map<String, Object> rejectRuleProposal(@Valid @RequestBody RuleEngine.RuleProposalRequest body) {
        try (Connection connection = connections.open()) {
            boolean created = ruleEngine.rejectRuleProposal(connection, body);
            return Map.of("status", "rejected", "created", created);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    RuleEngine.RuleRecord createRule(@Valid @RequestBody RuleRequest body) {
        try (Connection connection = connections.open()) {
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
                    return getRule(connection, keys.getLong(1));
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{ruleId}")
    RuleEngine.RuleRecord updateRule(@PathVariable long ruleId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            requireRule(connection, ruleId);
            if (body.has("match_account_id") && !body.get("match_account_id").isNull()) {
                requireAccount(connection, body.get("match_account_id").asLong());
            }
            if (body.has("set_category_id") && !body.get("set_category_id").isNull()) {
                requireCategory(connection, body.get("set_category_id").asLong());
            }
            List<ColumnValue> values = patchValues(body);
            if (!values.isEmpty()) {
                StringJoiner assignments = new StringJoiner(", ");
                for (ColumnValue value : values) {
                    assignments.add(value.column() + " = ?");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE rules SET " + assignments + " WHERE id = ?")) {
                    int index = 1;
                    for (ColumnValue value : values) {
                        bindParam(statement, index++, value.value());
                    }
                    statement.setLong(index, ruleId);
                    statement.executeUpdate();
                }
            }
            return getRule(connection, ruleId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/bulk-delete")
    Map<String, Integer> bulkDeleteRules(@RequestBody RuleBulkDelete body) {
        List<Long> ids = body.ids() == null ? List.of() : body.ids().stream().distinct().sorted().toList();
        if (ids.isEmpty()) {
            return Map.of("deleted", 0);
        }
        try (Connection connection = connections.open()) {
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
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{ruleId}")
    Map<String, String> deleteRule(@PathVariable long ruleId) {
        try (Connection connection = connections.open()) {
            requireRule(connection, ruleId);
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE transactions SET matched_rule_id = NULL WHERE matched_rule_id = ?
                        """)) {
                    statement.setLong(1, ruleId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM rules WHERE id = ?")) {
                    statement.setLong(1, ruleId);
                    statement.executeUpdate();
                }
                connection.commit();
                return Map.of("status", "deleted");
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/reapply")
    Map<String, Integer> reapplyRules() {
        try (Connection connection = connections.open()) {
            RuleEngine.ReapplyResult result = ruleEngine.reapplyToUnreviewed(connection);
            return Map.of("rows_changed", result.rowsChanged(), "rules_fired", result.rulesFired());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private RuleEngine.RuleRecord getRule(Connection connection, long ruleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, priority, is_active, match_account_id, match_description_pattern,
                       match_amount_min, match_amount_max, set_category_id, set_kind, set_merchant,
                       set_tags, notes, apply_count, last_applied_at
                FROM rules
                WHERE id = ?
                """)) {
            statement.setLong(1, ruleId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "rule not found");
                }
                return ruleEngine.ruleFrom(rs);
            }
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM rules WHERE id = ?")) {
            statement.setLong(1, ruleId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "rule not found");
                }
            }
        }
    }

    private void requireAccount(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "account not found");
                }
            }
        }
    }

    private void requireCategory(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
                }
            }
        }
    }

    private List<ColumnValue> patchValues(JsonNode body) {
        List<ColumnValue> values = new ArrayList<>();
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

    private void addText(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new ColumnValue(field, node == null || node.isNull() ? null : blankToNull(node.asText())));
        }
    }

    private void addInteger(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new ColumnValue(field, node == null || node.isNull() ? null : node.asInt()));
        }
    }

    private void addBoolean(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new ColumnValue(field, node != null && !node.isNull() && node.asBoolean()));
        }
    }

    private void addLong(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new ColumnValue(field, node == null || node.isNull() ? null : node.asLong()));
        }
    }

    private void addDecimal(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new ColumnValue(field, node == null || node.isNull() || node.asText().isBlank() ? null : new BigDecimal(node.asText())));
        }
    }

    private void addTags(List<ColumnValue> values, JsonNode body) {
        if (body.has("set_tags")) {
            JsonNode node = body.get("set_tags");
            values.add(new ColumnValue("set_tags", node == null || node.isNull() ? null : node.toString()));
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

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record RuleRequest(
            @NotBlank String name,
            Integer priority,
            Boolean isActive,
            Long matchAccountId,
            String matchDescriptionPattern,
            BigDecimal matchAmountMin,
            BigDecimal matchAmountMax,
            Long setCategoryId,
            String setKind,
            String setMerchant,
            List<String> setTags,
            String notes) {
    }

    record RuleBulkDelete(List<Long> ids) {
    }

    record ColumnValue(String column, Object value) {
    }
}
