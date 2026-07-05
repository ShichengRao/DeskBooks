package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

final class RulePatchValues {
    static final String MATCH_ACCOUNT_ID = "match_account_id";
    static final String SET_CATEGORY_ID = "set_category_id";

    private static final List<FieldSpec> PATCH_FIELDS = List.of(
            new FieldSpec("name", RulePatchValues::text),
            new FieldSpec("priority", RulePatchValues::integer),
            new FieldSpec("is_active", RulePatchValues::booleanValue),
            new FieldSpec(MATCH_ACCOUNT_ID, RulePatchValues::longValue),
            new FieldSpec("match_description_pattern", RulePatchValues::text),
            new FieldSpec("match_amount_min", RulePatchValues::decimal),
            new FieldSpec("match_amount_max", RulePatchValues::decimal),
            new FieldSpec(SET_CATEGORY_ID, RulePatchValues::longValue),
            new FieldSpec("set_kind", RulePatchValues::text),
            new FieldSpec("set_merchant", RulePatchValues::text),
            new FieldSpec("set_tags", RulePatchValues::json),
            new FieldSpec("notes", RulePatchValues::text));

    List<RuleColumnValue> from(JsonNode body) {
        List<RuleColumnValue> values = new ArrayList<>();
        for (FieldSpec field : PATCH_FIELDS) {
            if (body.has(field.name())) {
                values.add(new RuleColumnValue(field.name(), field.read(body.get(field.name()))));
            }
        }
        return values;
    }

    Long referenceId(JsonNode body, String field) {
        if (body.has(field) && !body.get(field).isNull()) {
            return body.get(field).asLong();
        }
        return null;
    }

    private static Object text(JsonNode node) {
        return nullable(node, value -> RuleSql.blankToNull(value.asText()));
    }

    private static Object integer(JsonNode node) {
        return nullable(node, JsonNode::asInt);
    }

    private static Object booleanValue(JsonNode node) {
        return node != null && !node.isNull() && node.asBoolean();
    }

    private static Object longValue(JsonNode node) {
        return nullable(node, JsonNode::asLong);
    }

    private static Object decimal(JsonNode node) {
        return nullable(node, RulePatchValues::decimalValue);
    }

    private static Object json(JsonNode node) {
        return nullable(node, JsonNode::toString);
    }

    private static Object nullable(JsonNode node, Function<JsonNode, Object> reader) {
        return node == null || node.isNull() ? null : reader.apply(node);
    }

    private static BigDecimal decimalValue(JsonNode node) {
        String text = node.asText();
        return text.isBlank() ? null : new BigDecimal(text);
    }

    private record FieldSpec(String name, Function<JsonNode, Object> reader) {
        Object read(JsonNode node) {
            return reader.apply(node);
        }
    }
}
