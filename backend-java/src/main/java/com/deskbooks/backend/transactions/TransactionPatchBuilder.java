package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class TransactionPatchBuilder {
    private static final String CATEGORY_ID = "category_id";
    private static final String KIND = "kind";
    private static final String USER_CATEGORIZED = "is_user_categorized";
    private static final String MATCHED_RULE_ID = "matched_rule_id";
    private static final String EXCLUDED = "is_excluded_from_totals";

    private final TransactionLookup lookup;

    TransactionPatchBuilder(TransactionLookup lookup) {
        this.lookup = lookup;
    }

    List<TransactionColumnValue> patchValues(Connection connection, JsonNode body) throws SQLException {
        List<TransactionColumnValue> values = new ArrayList<>();
        addDate(values, body, "date");
        addDate(values, body, "post_date");
        addDescription(values, body);
        addText(values, body, "merchant");
        addBigDecimal(values, body, "amount");
        addPatchCategory(connection, values, body);
        addPatchKind(values, body);
        addBoolean(values, body, EXCLUDED);
        addText(values, body, "notes");
        addLong(values, body, "transfer_pair_id");
        return values;
    }

    TransactionCategoryInfo bulkCategory(Connection connection, JsonNode body) throws SQLException {
        if (body.has(CATEGORY_ID) && !body.get(CATEGORY_ID).isNull()) {
            return lookup.categoryOr404(connection, body.get(CATEGORY_ID).asLong());
        }
        return null;
    }

    List<TransactionColumnValue> bulkValues(JsonNode body, TransactionCategoryInfo category) {
        List<TransactionColumnValue> values = new ArrayList<>();
        if (category != null) {
            addCategorizedValues(values, category.id(), category.kind(), !body.has(KIND));
        }
        if (body.has(KIND) && !body.get(KIND).isNull()) {
            values.add(new TransactionColumnValue(KIND, body.get(KIND).asText()));
            markUserCategorized(values);
        }
        addBoolean(values, body, EXCLUDED);
        return values;
    }

    private void addDescription(List<TransactionColumnValue> values, JsonNode body) {
        if (body.has("description_raw")) {
            String raw = TransactionJson.textOrNull(body, "description_raw");
            values.add(new TransactionColumnValue("description_raw", raw));
            if (!body.has("description_normalized")) {
                values.add(new TransactionColumnValue(
                        "description_normalized",
                        raw == null ? null : TransactionJson.normalizeDescription(raw)));
            }
        }
        addText(values, body, "description_normalized");
    }

    private void addPatchCategory(Connection connection, List<TransactionColumnValue> values, JsonNode body)
            throws SQLException {
        if (body.has(CATEGORY_ID)) {
            Long categoryId = TransactionJson.optionalLong(body, CATEGORY_ID);
            TransactionCategoryInfo category = categoryId == null ? null : lookup.categoryOr404(connection, categoryId);
            addCategorizedValues(values, categoryId, category == null ? null : category.kind(), category != null && !body.has(KIND));
        }
    }

    private void addPatchKind(List<TransactionColumnValue> values, JsonNode body) {
        if (body.has(KIND)) {
            values.add(new TransactionColumnValue(KIND, TransactionJson.textOrNull(body, KIND)));
            markUserCategorized(values);
        }
    }

    private void addCategorizedValues(List<TransactionColumnValue> values, Long categoryId, String defaultKind, boolean includeDefaultKind) {
        values.add(new TransactionColumnValue(CATEGORY_ID, categoryId));
        markUserCategorized(values);
        if (includeDefaultKind) {
            values.add(new TransactionColumnValue(KIND, defaultKind));
        }
    }

    private void markUserCategorized(List<TransactionColumnValue> values) {
        values.add(new TransactionColumnValue(USER_CATEGORIZED, true));
        values.add(new TransactionColumnValue(MATCHED_RULE_ID, null));
    }

    private void addText(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJson.textOrNull(body, field)));
        }
    }

    private void addDate(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJson.optionalDateString(body, field)));
        }
    }

    private void addBigDecimal(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new TransactionColumnValue(
                    field,
                    node == null || node.isNull() ? null : new BigDecimal(node.asText())));
        }
    }

    private void addBoolean(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new TransactionColumnValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }

    private void addLong(List<TransactionColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new TransactionColumnValue(field, TransactionJson.optionalLong(body, field)));
        }
    }
}
