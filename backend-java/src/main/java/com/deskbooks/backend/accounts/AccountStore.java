package com.deskbooks.backend.accounts;

import com.deskbooks.backend.accounts.AccountController.AccountRequest;
import com.deskbooks.backend.accounts.AccountController.AccountResponse;
import com.deskbooks.backend.db.SqliteConnectionProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

final class AccountStore {
    private static final List<String> OUT_COLUMNS = List.of(
            "id", "name", "institution", "account_category", "type", "currency",
            "sign_convention", "url", "notes", "is_closed", "opened_at", "closed_at", "sort_order");

    private final SqliteConnectionProvider connections;
    private final AccountLookup lookup = new AccountLookup(OUT_COLUMNS);
    private final AccountPatchApplier patchApplier = new AccountPatchApplier();

    AccountStore(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    List<AccountResponse> list(boolean includeClosed) throws SQLException {
        String sql = "SELECT " + String.join(", ", OUT_COLUMNS)
                + " FROM accounts"
                + (includeClosed ? "" : " WHERE is_closed = 0")
                + " ORDER BY sort_order, name";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<AccountResponse> accounts = new ArrayList<>();
            while (rs.next()) {
                accounts.add(AccountMapper.from(rs));
            }
            return accounts;
        }
    }

    AccountResponse create(AccountRequest body) throws SQLException {
        String sql = """
                INSERT INTO accounts (
                  name, institution, account_category, type, currency, sign_convention,
                  url, notes, is_closed, opened_at, closed_at, sort_order
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            AccountBinder.bind(statement, body);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return lookup.get(connection, keys.getLong(1));
            }
        }
    }

    AccountResponse get(long accountId) throws SQLException {
        try (Connection connection = connections.open()) {
            return lookup.get(connection, accountId);
        }
    }

    AccountResponse update(long accountId, JsonNode body) throws SQLException {
        try (Connection connection = connections.open()) {
            lookup.require(connection, accountId);
            List<AccountPatchValue> values = AccountPatchValues.from(body);
            if (!values.isEmpty()) {
                patchApplier.apply(connection, accountId, values);
            }
            return lookup.get(connection, accountId);
        }
    }

    Map<String, String> delete(long accountId) throws SQLException {
        try (Connection connection = connections.open()) {
            lookup.require(connection, accountId);
            if (hasTransactions(connection, accountId)) {
                close(connection, accountId);
                return Map.of("status", "closed_instead_of_deleted");
            }
            deleteRow(connection, accountId);
            return Map.of("status", "deleted");
        }
    }

    private boolean hasTransactions(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM transactions WHERE account_id = ? LIMIT 1")) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void close(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET is_closed = 1 WHERE id = ?")) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
        }
    }

    private void deleteRow(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
        }
    }
}
