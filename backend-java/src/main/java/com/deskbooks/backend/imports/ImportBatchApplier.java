package com.deskbooks.backend.imports;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImportBatchApplier {
    private final ImportBatchStore batches;
    private final ImportTransactionStore transactions;

    ImportBatchApplier(ImportBatchStore batches, ImportTransactionStore transactions) {
        this.batches = batches;
        this.transactions = transactions;
    }

    ImportBatchStore.ImportCounts apply(
            Connection connection,
            ImportApplyRequest body,
            long batchId) throws SQLException {
        Map<DuplicateKey, Integer> existing = batches.existingKeyCounts(connection, body.accountId());
        ImportFileDuplicateCounts fileCounts = new ImportFileDuplicateCounts();
        List<Long> ruleFires = new ArrayList<>();
        int applied = 0;
        int duplicates = 0;
        for (ImportDraftRow row : body.rows()) {
            boolean duplicate = fileCounts.position(row) < existing.getOrDefault(ImportDuplicateKeys.from(row), 0);
            if (duplicate && body.skipDuplicates()) {
                duplicates++;
                continue;
            }
            transactions.insert(connection, body.accountId(), batchId, row);
            applied++;
            if (row.suggestedMatchedRuleId() != null) {
                ruleFires.add(row.suggestedMatchedRuleId());
            }
        }
        return new ImportBatchStore.ImportCounts(applied, duplicates, ruleFires);
    }
}

final class ImportFileDuplicateCounts {
    private final Map<DuplicateKey, Integer> counts = new LinkedHashMap<>();

    int position(ImportDraftRow row) {
        DuplicateKey key = ImportDuplicateKeys.from(row);
        int position = counts.getOrDefault(key, 0);
        counts.put(key, position + 1);
        return position;
    }
}
