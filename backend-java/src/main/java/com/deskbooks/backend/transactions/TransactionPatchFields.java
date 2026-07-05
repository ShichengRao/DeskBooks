package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class TransactionPatchFields {
    void addDescription(List<TransactionColumnValue> values, JsonNode body) {
        if (body.has("description_raw")) {
            String raw = TransactionJsonText.orNull(body, "description_raw");
            values.add(new TransactionColumnValue("description_raw", raw));
            if (!body.has("description_normalized")) {
                values.add(new TransactionColumnValue(
                        "description_normalized",
                        raw == null ? null : TransactionJsonText.normalizeDescription(raw)));
            }
        }
        addText(values, body, "description_normalized");
    }

    void addText(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJsonText.orNull(body, field)));
        }
    }

    void addDate(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJsonValues.optionalDateString(body, field)));
        }
    }

    void addBigDecimal(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new TransactionColumnValue(
                    field,
                    node == null || node.isNull() ? null : new BigDecimal(node.asText())));
        }
    }

    void addBoolean(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new TransactionColumnValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }

    void addLong(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJsonValues.optionalLong(body, field)));
        }
    }
}
