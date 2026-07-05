package com.deskbooks.backend.transactions;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class TransactionJsonText {
    private TransactionJsonText() {
    }

    static String required(JsonNode body, String field) {
        String value = TransactionJsonNodes.required(body, field).asText();
        if (value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    static String orDefault(JsonNode body, String field, String defaultValue) {
        String value = orNull(body, field);
        return value == null ? defaultValue : value;
    }

    static String orNull(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    static String normalizeDescription(String description) {
        return String.join(" ", description.trim().split("\\s+"));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
