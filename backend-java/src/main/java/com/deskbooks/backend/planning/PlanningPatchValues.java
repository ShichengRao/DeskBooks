package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

final class PlanningPatchValues {
    private PlanningPatchValues() {
    }

    static String textOrNull(JsonNode body, String field) {
        if (!body.has(field)) {
            return null;
        }
        return (String) valueOrNull(body, field, JsonNode::asText);
    }

    static void addText(List<PatchValue> values, JsonNode body, String field) {
        add(values, body, field, JsonNode::asText);
    }

    static void addBigDecimal(List<PatchValue> values, JsonNode body, String field) {
        add(values, body, field, node -> new BigDecimal(node.asText()));
    }

    static void addDate(List<PatchValue> values, JsonNode body, String field) {
        add(values, body, field, JsonNode::asText);
    }

    static void addInteger(List<PatchValue> values, JsonNode body, String field) {
        add(values, body, field, JsonNode::asInt);
    }

    static void addLong(List<PatchValue> values, JsonNode body, String field) {
        add(values, body, field, JsonNode::asLong);
    }

    static void addBoolean(List<PatchValue> values, JsonNode body, String field) {
        add(values, body, field, JsonNode::asBoolean);
    }

    private static void add(List<PatchValue> values, JsonNode body, String field, Function<JsonNode, Object> mapper) {
        if (body.has(field)) {
            values.add(new PatchValue(field, valueOrNull(body, field, mapper)));
        }
    }

    private static Object valueOrNull(JsonNode body, String field, Function<JsonNode, Object> mapper) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : mapper.apply(node);
    }
}
