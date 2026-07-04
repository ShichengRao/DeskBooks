package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.ImportParsing.money;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.rules.RuleEngine;
import com.deskbooks.backend.rules.RuleEval;
import com.deskbooks.backend.rules.RuleRecord;

final class ImportPreviewMarker {
    private final RuleEngine ruleEngine;
    private final ImportBatchStore batches;

    ImportPreviewMarker(RuleEngine ruleEngine, ImportBatchStore batches) {
        this.ruleEngine = ruleEngine;
        this.batches = batches;
    }

    ImportController.ImportPreviewResponse previewRows(
            Connection connection,
            List<ImportController.ImportDraftRow> rows,
            String importerName,
            long accountId,
            String filename,
            List<String> sniffNotes) throws SQLException {
        List<RuleRecord> activeRules = ruleEngine.loadActiveRules(connection);
        Map<ImportController.DuplicateKey, Integer> existing = batches.existingKeyCounts(connection, accountId);
        Map<ImportController.DuplicateKey, Integer> fileCounts = new LinkedHashMap<>();
        List<ImportController.ImportDraftRow> markedRows = new ArrayList<>();
        for (ImportController.ImportDraftRow row : rows) {
            row = withRuleSuggestion(row, ruleEngine.evaluate(
                    activeRules,
                    accountId,
                    row.descriptionNormalized() == null ? row.descriptionRaw() : row.descriptionNormalized(),
                    row.amountValue()));
            ImportController.DuplicateKey key = new ImportController.DuplicateKey(
                    row.date(),
                    money(row.amountValue()),
                    row.descriptionNormalized() == null ? "" : row.descriptionNormalized());
            int position = fileCounts.getOrDefault(key, 0);
            fileCounts.put(key, position + 1);
            boolean duplicate = position < existing.getOrDefault(key, 0);
            markedRows.add(row.withDuplicate(duplicate));
        }
        return new ImportController.ImportPreviewResponse(
                importerName,
                accountId,
                filename,
                markedRows,
                sniffNotes);
    }

    private ImportController.ImportDraftRow withRuleSuggestion(ImportController.ImportDraftRow row, RuleEval eval) {
        if (!eval.matched()) {
            return row;
        }
        return new ImportController.ImportDraftRow(
                row.rowIndex(),
                row.date(),
                row.postDate(),
                row.descriptionRaw(),
                row.descriptionNormalized(),
                eval.merchant() == null || eval.merchant().isBlank() ? row.merchant() : eval.merchant(),
                row.amount(),
                eval.categoryId() == null ? row.suggestedCategoryId() : eval.categoryId(),
                eval.kind() == null ? row.suggestedKind() : eval.kind(),
                eval.tags() == null ? row.suggestedTags() : eval.tags(),
                eval.matchedRuleId(),
                row.isDuplicate(),
                row.raw());
    }
}
