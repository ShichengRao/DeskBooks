package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

final class TransactionBulkMutations {
    private final TransactionLookup lookup;
    private final TransactionPatchBuilder patches;
    private final TransactionRelations relations;
    private final TransactionUpdateWriter writer;

    TransactionBulkMutations(
            TransactionLookup lookup,
            TransactionPatchBuilder patches,
            TransactionRelations relations,
            TransactionUpdateWriter writer) {
        this.lookup = lookup;
        this.patches = patches;
        this.relations = relations;
        this.writer = writer;
    }

    Map<String, Integer> bulkUpdate(Connection connection, JsonNode body) throws SQLException {
        List<Long> ids = TransactionJsonValues.longList(body.get("ids"));
        if (ids.isEmpty()) {
            return Map.of("updated", 0);
        }

        TransactionCategoryInfo category = patches.bulkCategory(connection, body);
        Set<Long> found = lookup.existingTransactions(connection, ids);
        if (found.isEmpty()) {
            return Map.of("updated", 0);
        }
        for (Long id : found) {
            applyBulkUpdate(connection, id, body, category);
        }
        return Map.of("updated", found.size());
    }

    private void applyBulkUpdate(
            Connection connection,
            long transactionId,
            JsonNode body,
            TransactionCategoryInfo category) throws SQLException {
        List<TransactionColumnValue> values = patches.bulkValues(body, category);
        if (!values.isEmpty()) {
            writer.update(connection, transactionId, values);
        }

        relations.applyBulkChanges(connection, transactionId, body);
    }
}
