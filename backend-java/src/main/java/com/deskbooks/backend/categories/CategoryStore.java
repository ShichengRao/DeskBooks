package com.deskbooks.backend.categories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class CategoryStore {
    private final CategoryParentValidator parents = new CategoryParentValidator();
    private final CategoryUpdateApplier updates = new CategoryUpdateApplier(parents);

    List<CategoryController.CategoryResponse> list(Connection connection, boolean includeArchived) throws SQLException {
        String sql = "SELECT " + CategoryRows.selectColumns()
                + " FROM categories"
                + (includeArchived ? "" : " WHERE archived = 0")
                + " ORDER BY sort_order, name";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<CategoryController.CategoryResponse> categories = new ArrayList<>();
            while (rs.next()) {
                categories.add(CategoryRows.from(rs));
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
        updates.apply(connection, categoryId, body);
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

    private CategoryController.CategoryResponse get(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + CategoryRows.selectColumns() + " FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
                }
                return CategoryRows.from(rs);
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
}
