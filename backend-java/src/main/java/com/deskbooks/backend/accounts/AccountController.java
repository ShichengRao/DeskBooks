package com.deskbooks.backend.accounts;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/accounts")
class AccountController {
    private static final List<String> OUT_COLUMNS = List.of(
            "id", "name", "institution", "account_category", "type", "currency",
            "sign_convention", "url", "notes", "is_closed", "opened_at", "closed_at", "sort_order");

    private final SqliteConnectionProvider connections;

    AccountController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<AccountResponse> listAccounts(
            @RequestParam(name = "include_closed", defaultValue = "true") boolean includeClosed) {
        String sql = "SELECT " + String.join(", ", OUT_COLUMNS)
                + " FROM accounts"
                + (includeClosed ? "" : " WHERE is_closed = 0")
                + " ORDER BY sort_order, name";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<AccountResponse> accounts = new ArrayList<>();
            while (rs.next()) {
                accounts.add(accountFrom(rs));
            }
            return accounts;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    AccountResponse createAccount(@Valid @RequestBody AccountRequest body) {
        String sql = """
                INSERT INTO accounts (
                  name, institution, account_category, type, currency, sign_convention,
                  url, notes, is_closed, opened_at, closed_at, sort_order
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindAccount(statement, body);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return getAccount(connection, keys.getLong(1));
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{accountId}")
    AccountResponse getAccount(@PathVariable long accountId) {
        try (Connection connection = connections.open()) {
            return getAccount(connection, accountId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{accountId}")
    AccountResponse updateAccount(@PathVariable long accountId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            requireAccount(connection, accountId);
            List<PatchValue> values = accountPatchValues(body);
            if (!values.isEmpty()) {
                StringJoiner assignments = new StringJoiner(", ");
                for (PatchValue value : values) {
                    assignments.add(value.column() + " = ?");
                }
                String sql = "UPDATE accounts SET " + assignments + " WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    for (PatchValue value : values) {
                        statement.setObject(index++, value.value());
                    }
                    statement.setLong(index, accountId);
                    statement.executeUpdate();
                }
            }
            return getAccount(connection, accountId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{accountId}")
    Map<String, String> deleteAccount(@PathVariable long accountId) {
        try (Connection connection = connections.open()) {
            requireAccount(connection, accountId);
            boolean hasTransactions;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM transactions WHERE account_id = ? LIMIT 1")) {
                statement.setLong(1, accountId);
                try (ResultSet rs = statement.executeQuery()) {
                    hasTransactions = rs.next();
                }
            }

            if (hasTransactions) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE accounts SET is_closed = 1 WHERE id = ?")) {
                    statement.setLong(1, accountId);
                    statement.executeUpdate();
                }
                return Map.of("status", "closed_instead_of_deleted");
            }

            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
                statement.setLong(1, accountId);
                statement.executeUpdate();
            }
            return Map.of("status", "deleted");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private void bindAccount(PreparedStatement statement, AccountRequest body) throws SQLException {
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

    private AccountResponse getAccount(Connection connection, long accountId) throws SQLException {
        String sql = "SELECT " + String.join(", ", OUT_COLUMNS) + " FROM accounts WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "account not found");
                }
                return accountFrom(rs);
            }
        }
    }

    private void requireAccount(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "account not found");
                }
            }
        }
    }

    private List<PatchValue> accountPatchValues(JsonNode body) {
        List<PatchValue> values = new ArrayList<>();
        addText(values, body, "name");
        addText(values, body, "institution");
        addText(values, body, "account_category");
        addText(values, body, "type");
        addText(values, body, "currency");
        addText(values, body, "sign_convention");
        addText(values, body, "url");
        addText(values, body, "notes");
        addBoolean(values, body, "is_closed");
        addDate(values, body, "opened_at");
        addDate(values, body, "closed_at");
        addInteger(values, body, "sort_order");
        return values;
    }

    private AccountResponse accountFrom(ResultSet rs) throws SQLException {
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

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : LocalDate.parse(value);
    }

    private void addText(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asText()));
        }
    }

    private void addBoolean(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }

    private void addInteger(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : node.asInt()));
        }
    }

    private void addDate(List<PatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new PatchValue(field, node == null || node.isNull() ? null : Date.valueOf(node.asText())));
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record AccountRequest(
            @NotBlank String name,
            String institution,
            @NotBlank String accountCategory,
            @NotBlank String type,
            String currency,
            String signConvention,
            String url,
            String notes,
            Boolean isClosed,
            LocalDate openedAt,
            LocalDate closedAt,
            Integer sortOrder) {
    }

    record AccountResponse(
            long id,
            String name,
            String institution,
            String accountCategory,
            String type,
            String currency,
            String signConvention,
            String url,
            String notes,
            boolean isClosed,
            LocalDate openedAt,
            LocalDate closedAt,
            int sortOrder) {
    }

    record PatchValue(String column, Object value) {
    }
}
