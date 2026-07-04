package com.deskbooks.backend.budgets;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BudgetReportCategories {
    CategoryContext load(Connection connection) throws SQLException {
        List<CategoryRow> categories = categories(connection);
        Map<Long, CategoryRow> categoryById = new LinkedHashMap<>();
        Map<Long, List<CategoryRow>> childrenByParent = new LinkedHashMap<>();
        for (CategoryRow category : categories) {
            categoryById.put(category.id(), category);
            if (category.parentId() != null) {
                childrenByParent.computeIfAbsent(category.parentId(), ignored -> new ArrayList<>()).add(category);
            }
        }
        childrenByParent.values().forEach(children -> children.sort(categoryComparator()));
        List<CategoryRow> roots = roots(categories, categoryById);
        List<CategoryRow> ordered = new ArrayList<>();
        for (CategoryRow root : roots) {
            walk(root, childrenByParent, ordered);
        }
        return new CategoryContext(roots, ordered, categoryById, childrenByParent);
    }

    private List<CategoryRow> categories(Connection connection) throws SQLException {
        List<CategoryRow> categories = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, parent_id, sort_order
                FROM categories
                WHERE kind = 'expense'
                ORDER BY sort_order, name
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                categories.add(new CategoryRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        BudgetSqlValues.nullableLong(rs, "parent_id"),
                        rs.getInt("sort_order")));
            }
        }
        return categories;
    }

    private List<CategoryRow> roots(List<CategoryRow> categories, Map<Long, CategoryRow> categoryById) {
        return categories.stream()
                .filter(category -> category.parentId() == null || !categoryById.containsKey(category.parentId()))
                .sorted(categoryComparator())
                .toList();
    }

    private void walk(CategoryRow category, Map<Long, List<CategoryRow>> childrenByParent, List<CategoryRow> ordered) {
        ordered.add(category);
        for (CategoryRow child : childrenByParent.getOrDefault(category.id(), List.of())) {
            walk(child, childrenByParent, ordered);
        }
    }

    private Comparator<CategoryRow> categoryComparator() {
        return Comparator.comparingInt(CategoryRow::sortOrder)
                .thenComparing(category -> category.name().toLowerCase(Locale.ROOT));
    }
}
