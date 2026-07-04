package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class TransactionJson {
    private TransactionJson() {
    }

    static LocalDate requiredDate(JsonNode body, String field) {
        JsonNode node = requiredNode(body, field);
        return LocalDate.parse(node.asText());
    }

    static String optionalDateString(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : LocalDate.parse(node.asText()).toString();
    }

    static long requiredLong(JsonNode body, String field) {
        return requiredNode(body, field).asLong();
    }

    static Long optionalLong(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asLong();
    }

    static BigDecimal requiredDecimal(JsonNode body, String field) {
        return new BigDecimal(requiredNode(body, field).asText());
    }

    static String requiredText(JsonNode body, String field) {
        String value = requiredNode(body, field).asText();
        if (value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    static String textOrDefault(JsonNode body, String field, String defaultValue) {
        String value = textOrNull(body, field);
        return value == null ? defaultValue : value;
    }

    static String textOrNull(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
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

    static String normalizeDescription(String description) {
        return String.join(" ", description.trim().split("\\s+"));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static JsonNode requiredNode(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return node;
    }
}
