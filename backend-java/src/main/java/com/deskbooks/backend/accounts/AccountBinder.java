package com.deskbooks.backend.accounts;

import com.deskbooks.backend.accounts.AccountController.AccountRequest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class AccountBinder {
    private AccountBinder() {
    }

    static void bind(PreparedStatement statement, AccountRequest body) throws SQLException {
        statement.setString(1, body.name());
        statement.setString(2, body.institution());
        statement.setString(3, body.accountCategory());
        statement.setString(4, body.type());
        statement.setString(5, body.currency() == null ? "USD" : body.currency());
        statement.setString(6, body.signConvention() == null ? "outflow_negative" : body.signConvention());
        statement.setString(7, body.url());
        statement.setString(8, body.notes());
        statement.setBoolean(9, body.isClosed() != null && body.isClosed());
        statement.setObject(10, body.openedAt() == null ? null : Date.valueOf(body.openedAt()));
        statement.setObject(11, body.closedAt() == null ? null : Date.valueOf(body.closedAt()));
        statement.setInt(12, body.sortOrder() == null ? 0 : body.sortOrder());
    }
}
