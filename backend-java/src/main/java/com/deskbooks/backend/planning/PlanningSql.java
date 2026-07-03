package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

final class PlanningSql {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PlanningSql() {
    }

    static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return null;
        }
        if (value.matches("\\d{10,}")) {
            return java.time.Instant.ofEpochMilli(Long.parseLong(value))
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();
        }
        return LocalDate.parse(value);
    }

    static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return null;
        }
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDateTime.parse(value, SQLITE_TIMESTAMP);
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static List<Long> longList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    static String longListJson(List<Long> values) {
        if (values == null) {
            return null;
        }
        return jsonString(values);
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

    static Map<String, Object> jsonObject(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    static String jsonString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize JSON", exception);
        }
    }

    static String textOrNull(JsonNode body, String field) {
        if (!body.has(field)) {
            return null;
        }
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    static void addText(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asText()));
        }
    }

    static void addBigDecimal(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : new BigDecimal(node.asText())));
        }
    }

    static void addDate(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asText()));
        }
    }

    static void addInteger(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asInt()));
        }
    }

    static void addLong(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asLong()));
        }
    }

    static void addBoolean(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }
}
