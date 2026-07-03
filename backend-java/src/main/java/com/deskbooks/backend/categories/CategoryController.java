package com.deskbooks.backend.categories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/categories")
class CategoryController {
    private static final List<String> OUT_COLUMNS = List.of(
            "id", "name", "parent_id", "kind", "color", "sort_order", "archived");

    private final SqliteConnectionProvider connections;

    CategoryController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<CategoryResponse> listCategories(
            @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived) {
        String sql = "SELECT " + String.join(", ", OUT_COLUMNS)
                + " FROM categories"
                + (includeArchived ? "" : " WHERE archived = 0")
                + " ORDER BY sort_order, name";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<CategoryResponse> categories = new ArrayList<>();
            while (rs.next()) {
                categories.add(categoryFrom(rs));
            }
            return categories;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    CategoryResponse createCategory(@Valid @RequestBody CategoryRequest body) {
        try (Connection connection = connections.open()) {
            validateParent(connection, null, body.parentId());
            String sql = """
                    INSERT INTO categories (name, parent_id, kind, color, sort_order, archived)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, body.name());
                statement.setObject(2, body.parentId());
                statement.setString(3, body.kind());
                statement.setString(4, body.color());
                statement.setInt(5, body.sortOrder() == null ? 0 : body.sortOrder());
                statement.setBoolean(6, body.archived() != null && body.archived());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return getCategory(connection, keys.getLong(1));
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{categoryId}")
    CategoryResponse updateCategory(@PathVariable long categoryId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            requireCategory(connection, categoryId, "category not found");
            if (body.has("parent_id")) {
                JsonNode node = body.get("parent_id");
                validateParent(connection, categoryId, node == null || node.isNull() ? null : node.asLong());
            }

            List<PatchValue> values = categoryPatchValues(body);
            if (!values.isEmpty()) {
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

            if (body.has("kind") && !body.get("kind").isNull()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE transactions
                        SET kind = ?
                        WHERE category_id = ? AND is_user_categorized = 0
                        """)) {
                    statement.setString(1, body.get("kind").asText());
                    statement.setLong(2, categoryId);
                    statement.executeUpdate();
                }
            }

            return getCategory(connection, categoryId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{categoryId}")
    Map<String, String> deleteCategory(@PathVariable long categoryId) {
        try (Connection connection = connections.open()) {
            requireCategory(connection, categoryId, "category not found");
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE categories SET archived = 1 WHERE id = ?")) {
                statement.setLong(1, categoryId);
                statement.executeUpdate();
            }
            return Map.of("status", "archived");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private void validateParent(Connection connection, Long categoryId, Long parentId) throws SQLException {
        if (parentId == null) {
            return;
        }
        if (categoryId != null && parentId.equals(categoryId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "category cannot be its own parent");
        }

        long currentId = parentId;
        Set<Long> seen = new HashSet<>();
        while (true) {
            if (categoryId != null && currentId == categoryId) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "category parent cannot be a descendant");
            }
            if (!seen.add(currentId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "category hierarchy contains a cycle");
            }
            Long nextParentId = parentIdFor(connection, currentId);
            if (nextParentId == null) {
                return;
            }
            if (nextParentId == MissingCategory.ID) {
                throw new ApiException(HttpStatus.NOT_FOUND, "parent category not found");
            }
            currentId = nextParentId;
        }
    }

    private Long parentIdFor(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT parent_id FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return MissingCategory.ID;
                }
                long parentId = rs.getLong("parent_id");
                return rs.wasNull() ? null : parentId;
            }
        }
    }

    private CategoryResponse getCategory(Connection connection, long categoryId) throws SQLException {
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

    private void requireCategory(Connection connection, long categoryId, String message) throws SQLException {
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
        addLong(values, body, "parent_id");
        addText(values, body, "kind");
        addText(values, body, "color");
        addInteger(values, body, "sort_order");
        addBoolean(values, body, "archived");
        return values;
    }

    private CategoryResponse categoryFrom(ResultSet rs) throws SQLException {
        long parentId = rs.getLong("parent_id");
        return new CategoryResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.wasNull() ? null : parentId,
                rs.getString("kind"),
                rs.getString("color"),
                rs.getInt("sort_order"),
                rs.getBoolean("archived"));
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

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record CategoryRequest(
            @NotBlank String name,
            Long parentId,
            @NotBlank String kind,
            String color,
            Integer sortOrder,
            Boolean archived) {
    }

    record CategoryResponse(
            long id,
            String name,
            Long parentId,
            String kind,
            String color,
            int sortOrder,
            boolean archived) {
    }

    record PatchValue(String column, Object value) {
    }

    private static final class MissingCategory {
        private static final long ID = -1L;
    }
}
