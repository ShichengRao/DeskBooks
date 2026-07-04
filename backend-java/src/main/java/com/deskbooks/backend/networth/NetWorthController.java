package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/snapshots")
class NetWorthController {
    private final SqliteConnectionProvider connections;
    private final NetWorthReader reader = new NetWorthReader();
    private final NetWorthSeries series = new NetWorthSeries();

    NetWorthController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<NetWorthSnapshotResponse> listSnapshots() {
        try (Connection connection = connections.open()) {
            return reader.list(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    NetWorthSnapshotResponse createSnapshot(@Valid @RequestBody NetWorthSnapshotRequest body) {
        try (Connection connection = connections.open()) {
            if (snapshotExists(connection, body.snapshotDate())) {
                throw new ApiException(HttpStatus.CONFLICT, "snapshot for this date already exists");
            }

            try {
                connection.setAutoCommit(false);
                long snapshotId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO net_worth_snapshots (snapshot_date, notes)
                        VALUES (?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, body.snapshotDate().toString());
                    statement.setString(2, body.notes());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        snapshotId = keys.getLong(1);
                    }
                }
                upsertBalances(connection, snapshotId, balancesOrEmpty(body.balances()));
                connection.commit();
                return reader.get(connection, snapshotId);
            } catch (SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/import-workbook")
    NetWorthWorkbookImportResult importWorkbook(@Valid @RequestBody NetWorthWorkbookImportRequest body) {
        try (Connection connection = connections.open()) {
            return NetWorthWorkbookImporter.importWorkbook(connection, body);
        } catch (java.io.IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read workbook");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{snapshotId}")
    NetWorthSnapshotResponse updateSnapshot(@PathVariable long snapshotId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            requireSnapshot(connection, snapshotId);
            try {
                connection.setAutoCommit(false);
                if (body.has("snapshot_date") && !body.get("snapshot_date").isNull()) {
                    LocalDate snapshotDate = LocalDate.parse(body.get("snapshot_date").asText());
                    if (snapshotExistsForDifferentId(connection, snapshotDate, snapshotId)) {
                        throw new ApiException(HttpStatus.CONFLICT, "snapshot for this date already exists");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE net_worth_snapshots SET snapshot_date = ? WHERE id = ?
                            """)) {
                        statement.setString(1, snapshotDate.toString());
                        statement.setLong(2, snapshotId);
                        statement.executeUpdate();
                    }
                }
                if (body.has("notes") && !body.get("notes").isNull()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE net_worth_snapshots SET notes = ? WHERE id = ?
                            """)) {
                        statement.setString(1, body.get("notes").asText());
                        statement.setLong(2, snapshotId);
                        statement.executeUpdate();
                    }
                }
                if (body.has("balances") && !body.get("balances").isNull()) {
                    replaceBalances(connection, snapshotId, balancesFromJson(body.get("balances")));
                }
                connection.commit();
                return reader.get(connection, snapshotId);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{snapshotId}")
    Map<String, String> deleteSnapshot(@PathVariable long snapshotId) {
        try (Connection connection = connections.open()) {
            requireSnapshot(connection, snapshotId);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM net_worth_snapshots WHERE id = ?")) {
                statement.setLong(1, snapshotId);
                statement.executeUpdate();
            }
            return Map.of("status", "deleted");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/series")
    List<NetWorthSeriesPointResponse> series(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }

        try (Connection connection = connections.open()) {
            return series.list(connection, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private void upsertBalances(Connection connection, long snapshotId, List<AccountBalanceRequest> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO account_balances (snapshot_id, account_id, balance, notes)
                VALUES (?, ?, ?, ?)
                """)) {
            for (AccountBalanceRequest balance : balances) {
                statement.setLong(1, snapshotId);
                statement.setLong(2, balance.accountId());
                statement.setBigDecimal(3, balance.balance());
                statement.setString(4, balance.notes());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceBalances(Connection connection, long snapshotId, List<AccountBalanceRequest> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM account_balances WHERE snapshot_id = ?
                """)) {
            statement.setLong(1, snapshotId);
            statement.executeUpdate();
        }
        upsertBalances(connection, snapshotId, balances);
    }

    private List<AccountBalanceRequest> balancesFromJson(JsonNode balancesNode) {
        List<AccountBalanceRequest> balances = new ArrayList<>();
        Set<Long> seenAccountIds = new LinkedHashSet<>();
        for (JsonNode node : balancesNode) {
            long accountId = node.get("account_id").asLong();
            if (!seenAccountIds.add(accountId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate account balance in snapshot");
            }
            JsonNode balanceNode = node.get("balance");
            BigDecimal balance = balanceNode == null || balanceNode.isNull() ? null : new BigDecimal(balanceNode.asText());
            JsonNode notesNode = node.get("notes");
            String notes = notesNode == null || notesNode.isNull() ? null : notesNode.asText();
            balances.add(new AccountBalanceRequest(accountId, balance, notes));
        }
        return balances;
    }

    private boolean snapshotExists(Connection connection, LocalDate snapshotDate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM net_worth_snapshots WHERE snapshot_date = ?
                """)) {
            statement.setString(1, snapshotDate.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean snapshotExistsForDifferentId(Connection connection, LocalDate snapshotDate, long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM net_worth_snapshots WHERE snapshot_date = ? AND id <> ?
                """)) {
            statement.setString(1, snapshotDate.toString());
            statement.setLong(2, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void requireSnapshot(Connection connection, long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM net_worth_snapshots WHERE id = ?")) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "snapshot not found");
                }
            }
        }
    }

    private List<AccountBalanceRequest> balancesOrEmpty(List<AccountBalanceRequest> balances) {
        return balances == null ? List.of() : balances;
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original exception carries the actionable failure.
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record NetWorthSnapshotRequest(
            @NotNull LocalDate snapshotDate,
            String notes,
            List<@Valid AccountBalanceRequest> balances) {
    }

    record AccountBalanceRequest(
            @NotNull Long accountId,
            BigDecimal balance,
            String notes) {
    }

    record NetWorthWorkbookImportRequest(
            @NotNull String path,
            Map<String, String> accountMap) {
    }

    record NetWorthWorkbookImportResult(
            int imported,
            int skippedExisting,
            List<String> missingAccounts) {
    }

    record NetWorthSnapshotResponse(
            long id,
            LocalDate snapshotDate,
            String notes,
            List<AccountBalanceResponse> balances) {
    }

    record AccountBalanceResponse(
            long accountId,
            String balance,
            String notes) {
    }

    record NetWorthSeriesPointResponse(
            LocalDate snapshotDate,
            String total,
            Map<String, String> byCategory,
            Map<String, String> byAccount,
            String taxable,
            String taxAdvantaged) {
    }
}
