package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class RulePatchBuilder {
    private static final String MATCH_ACCOUNT_ID = "match_account_id";
    private static final String SET_CATEGORY_ID = "set_category_id";

    private final RuleLookup lookup;

    RulePatchBuilder(RuleLookup lookup) {
        this.lookup = lookup;
    }

    List<RuleColumnValue> patchValues(Connection connection, JsonNode body) throws SQLException {
        lookup.validateReferences(
                connection,
                referenceId(body, MATCH_ACCOUNT_ID),
                referenceId(body, SET_CATEGORY_ID));

        List<RuleColumnValue> values = new ArrayList<>();
        addText(values, body, "name");
        addInteger(values, body, "priority");
        addBoolean(values, body, "is_active");
        addLong(values, body, MATCH_ACCOUNT_ID);
        addText(values, body, "match_description_pattern");
        addDecimal(values, body, "match_amount_min");
        addDecimal(values, body, "match_amount_max");
        addLong(values, body, SET_CATEGORY_ID);
        addText(values, body, "set_kind");
        addText(values, body, "set_merchant");
        addTags(values, body);
        addText(values, body, "notes");
        return values;
    }

    private Long referenceId(JsonNode body, String field) {
        if (body.has(field) && !body.get(field).isNull()) {
            return body.get(field).asLong();
        }
        return null;
    }

    private void addText(List<RuleColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new RuleColumnValue(field, node == null || node.isNull() ? null : RuleSql.blankToNull(node.asText())));
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
}
