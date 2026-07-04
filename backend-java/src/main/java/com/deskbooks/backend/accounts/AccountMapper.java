package com.deskbooks.backend.accounts;

import com.deskbooks.backend.accounts.AccountController.AccountResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

final class AccountMapper {
    private AccountMapper() {
    }

    static AccountResponse from(ResultSet rs) throws SQLException {
        return new AccountResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("institution"),
                rs.getString("account_category"),
                rs.getString("type"),
                rs.getString("currency"),
                rs.getString("sign_convention"),
                rs.getString("url"),
                rs.getString("notes"),
                rs.getBoolean("is_closed"),
                localDate(rs, "opened_at"),
                localDate(rs, "closed_at"),
                rs.getInt("sort_order"));
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : LocalDate.parse(value);
    }
}
