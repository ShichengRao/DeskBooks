package com.deskbooks.backend.analytics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class SankeyCategoryGroups {
    private SankeyCategoryGroups() {
    }

    static Map<Long, CategoryGroup> load(Connection connection) throws SQLException {
        Map<Long, CategoryRow> categories = categories(connection);
        Map<Long, CategoryGroup> out = new HashMap<>();
        for (CategoryRow category : categories.values()) {
            CategoryRow parent = category.parentId() == null ? null : categories.get(category.parentId());
            out.put(category.id(), new CategoryGroup(category.name(), parent == null ? category.name() : parent.name()));
        }
        return out;
    }

    private static Map<Long, CategoryRow> categories(Connection connection) throws SQLException {
        Map<Long, CategoryRow> categories = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, parent_id
                FROM categories
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Long parentId = nullableLong(rs, "parent_id");
                categories.put(rs.getLong("id"), new CategoryRow(rs.getLong("id"), rs.getString("name"), parentId));
            }
        }
        return categories;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record CategoryRow(long id, String name, Long parentId) {
    }
}

record CategoryGroup(String leaf, String group) {
}
