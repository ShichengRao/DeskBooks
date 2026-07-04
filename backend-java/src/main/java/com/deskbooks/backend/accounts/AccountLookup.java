package com.deskbooks.backend.accounts;

import com.deskbooks.backend.accounts.AccountController.AccountResponse;
import com.deskbooks.backend.foundation.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.http.HttpStatus;

final class AccountLookup {
    private final List<String> outColumns;

    AccountLookup(List<String> outColumns) {
        this.outColumns = outColumns;
    }

    AccountResponse get(Connection connection, long accountId) throws SQLException {
        String sql = "SELECT " + String.join(", ", outColumns) + " FROM accounts WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw accountNotFound();
                }
                return AccountMapper.from(rs);
            }
        }
    }

    void require(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw accountNotFound();
                }
            }
        }
    }

    private ApiException accountNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "account not found");
    }
}
