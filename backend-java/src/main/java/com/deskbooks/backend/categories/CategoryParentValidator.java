package com.deskbooks.backend.categories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class CategoryParentValidator {
    private static final String PARENT_ID = "parent_id";

    void validate(Connection connection, Long categoryId, Long parentId) throws SQLException {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(categoryId)) {
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
                long parentId = rs.getLong(PARENT_ID);
                return rs.wasNull() ? null : parentId;
            }
        }
    }

    private static final class MissingCategory {
        private static final long ID = -1L;
    }
}
