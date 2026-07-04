package com.deskbooks.backend.categories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;

final class CategoryUpdateApplier {
    private final CategoryParentValidator parents;

    CategoryUpdateApplier(CategoryParentValidator parents) {
        this.parents = parents;
    }

    void apply(Connection connection, long categoryId, JsonNode body) throws SQLException {
        validateNewParent(connection, categoryId, body);
        applyCategoryPatch(connection, categoryId, categoryPatchValues(body));
        applyKindToUncategorizedTransactions(connection, categoryId, body);
    }

    private void validateNewParent(Connection connection, long categoryId, JsonNode body) throws SQLException {
        if (body.has(CategoryRows.PARENT_ID)) {
            JsonNode node = body.get(CategoryRows.PARENT_ID);
            parents.validate(connection, categoryId, node == null || node.isNull() ? null : node.asLong());
        }
    }

    private void applyCategoryPatch(Connection connection, long categoryId, List<PatchValue> values) throws SQLException {
        if (values.isEmpty()) {
            return;
        }

        StringJoiner assignments = new StringJoiner(", ");
        for (PatchValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE categories SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (PatchValue value : values) {
                statement.setObject(index++, value.value());
            }
            statement.setLong(index, categoryId);
            statement.executeUpdate();
        }
    }

    private void applyKindToUncategorizedTransactions(Connection connection, long categoryId, JsonNode body) throws SQLException {
        if (!body.has(CategoryRows.KIND) || body.get(CategoryRows.KIND).isNull()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions
                SET kind = ?
                WHERE category_id = ? AND is_user_categorized = 0
                """)) {
            statement.setString(1, body.get(CategoryRows.KIND).asText());
            statement.setLong(2, categoryId);
            statement.executeUpdate();
        }
    }

    private List<PatchValue> categoryPatchValues(JsonNode body) {
        List<PatchValue> values = new ArrayList<>();
        addIfPresent(values, body, "name", JsonNode::asText);
        addIfPresent(values, body, CategoryRows.PARENT_ID, JsonNode::asLong);
        addIfPresent(values, body, CategoryRows.KIND, JsonNode::asText);
        addIfPresent(values, body, "color", JsonNode::asText);
        addIfPresent(values, body, "sort_order", JsonNode::asInt);
        addIfPresent(values, body, CategoryRows.ARCHIVED, JsonNode::asBoolean);
        return values;
    }

    private void addIfPresent(List<PatchValue> values, JsonNode body, String field, NodeReader reader) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : reader.read(node)));
        }
    }

    @FunctionalInterface
    private interface NodeReader {
        Object read(JsonNode node);
    }

    private record PatchValue(String column, Object value) {
    }
}
