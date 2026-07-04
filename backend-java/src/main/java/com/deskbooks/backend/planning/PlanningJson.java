package com.deskbooks.backend.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

final class PlanningJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JavaType LONG_LIST_TYPE = MAPPER.getTypeFactory()
            .constructCollectionType(ArrayList.class, Long.class);
    private static final JavaType OBJECT_MAP_TYPE = MAPPER.getTypeFactory()
            .constructMapType(LinkedHashMap.class, String.class, Object.class);

    private PlanningJson() {
    }

    static List<Long> longList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, LONG_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    static String longListJson(List<Long> values) {
        if (values == null) {
            return null;
        }
        return string(values);
    }

    static String longListJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<Long> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asLong());
        }
        return longListJson(values);
    }

    static Map<String, Object> object(String json) {
        try {
            return MAPPER.readValue(json, OBJECT_MAP_TYPE);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    static String string(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize JSON", exception);
        }
    }
}
