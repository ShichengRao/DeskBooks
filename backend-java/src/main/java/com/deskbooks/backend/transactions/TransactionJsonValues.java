package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class TransactionJsonValues {
    private TransactionJsonValues() {
    }

    static LocalDate requiredDate(JsonNode body, String field) {
        return LocalDate.parse(TransactionJsonNodes.required(body, field).asText());
    }

    static String optionalDateString(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : LocalDate.parse(node.asText()).toString();
    }

    static long requiredLong(JsonNode body, String field) {
        return TransactionJsonNodes.required(body, field).asLong();
    }

    static Long optionalLong(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asLong();
    }

    static BigDecimal requiredDecimal(JsonNode body, String field) {
        return new BigDecimal(TransactionJsonNodes.required(body, field).asText());
    }

    static boolean booleanOrDefault(JsonNode body, String field, boolean defaultValue) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? defaultValue : node.asBoolean();
    }

    static List<Long> longList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asLong());
        }
        return values;
    }
}
