package com.deskbooks.backend.categories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

final class CategoryRows {
    static final String ARCHIVED = "archived";
    static final String KIND = "kind";
    static final String PARENT_ID = "parent_id";

    private static final List<String> OUT_COLUMNS = List.of(
            "id", "name", PARENT_ID, KIND, "color", "sort_order", ARCHIVED);

    private CategoryRows() {
    }

    static String selectColumns() {
        return String.join(", ", OUT_COLUMNS);
    }

    static CategoryController.CategoryResponse from(ResultSet rs) throws SQLException {
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
}
