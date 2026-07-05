package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class TransactionPatchCategorization {
    private static final String CATEGORY_ID = "category_id";
    private static final String KIND = "kind";
    private static final String USER_CATEGORIZED = "is_user_categorized";
    private static final String MATCHED_RULE_ID = "matched_rule_id";

    private final TransactionLookup lookup;

    TransactionPatchCategorization(TransactionLookup lookup) {
        this.lookup = lookup;
    }

    TransactionCategoryInfo bulkCategory(Connection connection, JsonNode body) throws SQLException {
        if (body.has(CATEGORY_ID) && !body.get(CATEGORY_ID).isNull()) {
            return lookup.categoryOr404(connection, body.get(CATEGORY_ID).asLong());
        }
        return null;
    }

    void addBulkValues(List<TransactionColumnValue> values, JsonNode body, TransactionCategoryInfo category) {
        if (category != null) {
            addCategorizedValues(values, category.id(), category.kind(), !body.has(KIND));
        }
        if (body.has(KIND) && !body.get(KIND).isNull()) {
            values.add(new TransactionColumnValue(KIND, body.get(KIND).asText()));
            markUserCategorized(values);
        }
    }

    void addPatchCategory(Connection connection, List<TransactionColumnValue> values, JsonNode body)
            throws SQLException {
        if (body.has(CATEGORY_ID)) {
            Long categoryId = TransactionJsonValues.optionalLong(body, CATEGORY_ID);
            TransactionCategoryInfo category = categoryId == null ? null : lookup.categoryOr404(connection, categoryId);
            addCategorizedValues(
                    values,
                    categoryId,
                    category == null ? null : category.kind(),
                    category != null && !body.has(KIND));
        }
    }

    void addPatchKind(List<TransactionColumnValue> values, JsonNode body) {
        if (body.has(KIND)) {
            values.add(new TransactionColumnValue(KIND, TransactionJsonText.orNull(body, KIND)));
            markUserCategorized(values);
        }
    }

    private void addCategorizedValues(
            List<TransactionColumnValue> values,
            Long categoryId,
            String defaultKind,
            boolean includeDefaultKind) {
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
}
