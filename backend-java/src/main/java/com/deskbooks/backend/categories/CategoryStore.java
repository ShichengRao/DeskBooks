package com.deskbooks.backend.categories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class CategoryStore {
    private static final String PARENT_ID = "parent_id";
    private static final String KIND = "kind";
    private static final String ARCHIVED = "archived";
    private static final List<String> OUT_COLUMNS = List.of(
            "id", "name", PARENT_ID, KIND, "color", "sort_order", ARCHIVED);

    private final CategoryParentValidator parents = new CategoryParentValidator();

    List<CategoryController.CategoryResponse> list(Connection connection, boolean includeArchived) throws SQLException {
        String sql = "SELECT " + String.join(", ", OUT_COLUMNS)
                + " FROM categories"
                + (includeArchived ? "" : " WHERE archived = 0")
                + " ORDER BY sort_order, name";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<CategoryController.CategoryResponse> categories = new ArrayList<>();
            while (rs.next()) {
                categories.add(categoryFrom(rs));
            }
            return categories;
        }
    }

    CategoryController.CategoryResponse create(Connection connection, CategoryController.CategoryRequest body) throws SQLException {
        parents.validate(connection, null, body.parentId());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO categories (name, parent_id, kind, color, sort_order, archived)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, body.name());
            statement.setObject(2, body.parentId());
            statement.setString(3, body.kind());
            statement.setString(4, body.color());
            statement.setInt(5, body.sortOrder() == null ? 0 : body.sortOrder());
            statement.setBoolean(6, body.archived() != null && body.archived());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return get(connection, keys.getLong(1));
            }
        }
    }

    CategoryController.CategoryResponse update(Connection connection, long categoryId, JsonNode body) throws SQLException {
        require(connection, categoryId, "category not found");
        validateNewParent(connection, categoryId, body);
        applyCategoryPatch(connection, categoryId, categoryPatchValues(body));
        applyKindToUncategorizedTransactions(connection, categoryId, body);
        return get(connection, categoryId);
    }

    void archive(Connection connection, long categoryId) throws SQLException {
        require(connection, categoryId, "category not found");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE categories SET archived = 1 WHERE id = ?")) {
            statement.setLong(1, categoryId);
            statement.executeUpdate();
        }
    }

    private void validateNewParent(Connection connection, long categoryId, JsonNode body) throws SQLException {
        if (body.has(PARENT_ID)) {
            JsonNode node = body.get(PARENT_ID);
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
        if (!body.has(KIND) || body.get(KIND).isNull()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions
                SET kind = ?
                WHERE category_id = ? AND is_user_categorized = 0
                """)) {
            statement.setString(1, body.get(KIND).asText());
            statement.setLong(2, categoryId);
            statement.executeUpdate();
        }
    }

    private CategoryController.CategoryResponse get(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + String.join(", ", OUT_COLUMNS) + " FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
                }
                return categoryFrom(rs);
            }
        }
    }

    private void require(Connection connection, long categoryId, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, message);
                }
            }
        }
    }

    private List<PatchValue> categoryPatchValues(JsonNode body) {
        List<PatchValue> values = new ArrayList<>();
        addText(values, body, "name");
        addLong(values, body, PARENT_ID);
        addText(values, body, KIND);
        addText(values, body, "color");
        addInteger(values, body, "sort_order");
        addBoolean(values, body, ARCHIVED);
        return values;
    }

    private CategoryController.CategoryResponse categoryFrom(ResultSet rs) throws SQLException {
        long parentId = rs.getLong(PARENT_ID);
        boolean parentWasNull = rs.wasNull();
        return new CategoryController.CategoryResponse(
                rs.getLong("id"),
                rs.getString("name"),
                parentWasNull ? null : parentId,
                rs.getString(KIND),
                rs.getString("color"),
                rs.getInt("sort_order"),
                rs.getBoolean(ARCHIVED));
    }

    private void addText(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asText()));
        }
    }

    private void addLong(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asLong()));
        }
    }

    private void addInteger(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asInt()));
        }
    }

    private void addBoolean(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }

    private record PatchValue(String column, Object value) {
    }
}
