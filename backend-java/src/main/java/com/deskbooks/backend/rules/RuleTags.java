package com.deskbooks.backend.rules;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

final class RuleTags {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleTags() {
    }

    static String toJson(List<String> tags) {
        if (tags == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(tags);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    static List<String> fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
