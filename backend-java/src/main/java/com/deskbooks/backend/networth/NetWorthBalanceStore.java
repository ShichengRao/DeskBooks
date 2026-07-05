package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class NetWorthBalanceStore {
    void upsert(
            Connection connection,
            long snapshotId,
            List<AccountBalanceRequest> balances) throws SQLException {
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

    void replace(
            Connection connection,
            long snapshotId,
            List<AccountBalanceRequest> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM account_balances WHERE snapshot_id = ?
                """)) {
            statement.setLong(1, snapshotId);
            statement.executeUpdate();
        }
        upsert(connection, snapshotId, balances);
    }

    NetWorthSnapshotPatch patchFromJson(JsonNode body) {
        boolean hasSnapshotDate = hasNonNull(body, "snapshot_date");
        boolean hasNotes = hasNonNull(body, "notes");
        boolean hasBalances = hasNonNull(body, "balances");
        return new NetWorthSnapshotPatch(
                hasSnapshotDate,
                hasSnapshotDate ? LocalDate.parse(body.get("snapshot_date").asText()) : null,
                hasNotes,
                hasNotes ? body.get("notes").asText() : null,
                hasBalances,
                hasBalances ? balancesFromJson(body.get("balances")) : List.of());
    }

    private List<AccountBalanceRequest> balancesFromJson(JsonNode balancesNode) {
        List<AccountBalanceRequest> balances = new ArrayList<>();
        Set<Long> seenAccountIds = new LinkedHashSet<>();
        for (JsonNode node : balancesNode) {
            long accountId = node.get("account_id").asLong();
            if (!seenAccountIds.add(accountId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate account balance in snapshot");
            }
            balances.add(balanceFromJson(node, accountId));
        }
        return balances;
    }

    private AccountBalanceRequest balanceFromJson(JsonNode node, long accountId) {
        JsonNode balanceNode = node.get("balance");
        BigDecimal balance = balanceNode == null || balanceNode.isNull() ? null : new BigDecimal(balanceNode.asText());
        JsonNode notesNode = node.get("notes");
        String notes = notesNode == null || notesNode.isNull() ? null : notesNode.asText();
        return new AccountBalanceRequest(accountId, balance, notes);
    }

    private boolean hasNonNull(JsonNode body, String field) {
        return body.has(field) && !body.get(field).isNull();
    }
}

record NetWorthSnapshotPatch(
        boolean hasSnapshotDate,
        LocalDate snapshotDate,
        boolean hasNotes,
        String notes,
        boolean hasBalances,
        List<AccountBalanceRequest> balances) {
}
