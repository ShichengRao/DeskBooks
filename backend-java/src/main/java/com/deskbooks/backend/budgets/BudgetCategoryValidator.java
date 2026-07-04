package com.deskbooks.backend.budgets;

import com.deskbooks.backend.foundation.ApiException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.http.HttpStatus;

final class BudgetCategoryValidator {
    void validate(Connection connection, long categoryId, BigDecimal amount) throws SQLException {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "budget amount must be zero or greater");
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT kind FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                validateCategoryRow(rs);
            }
        }
    }

    private void validateCategoryRow(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
        }
        if (!"expense".equals(rs.getString("kind"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "budgets can only target expense categories");
        }
    }
}
