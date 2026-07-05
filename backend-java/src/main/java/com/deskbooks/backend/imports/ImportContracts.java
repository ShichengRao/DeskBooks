package com.deskbooks.backend.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

record ImportPathPreviewRequest(@NotNull String path, long accountId, String importerName) {
}

record ImportApplyRequest(
        @NotNull String importerName,
        long accountId,
        @NotNull String sourceFilename,
        List<ImportDraftRow> rows,
        boolean skipDuplicates) {
    ImportApplyRequest {
        rows = rows == null ? List.of() : rows;
    }
}

record ImporterResponse(String name, String label) {
}

record ImportPreviewResponse(
        String importerName,
        long accountId,
        String sourceFilename,
        List<ImportDraftRow> rows,
        List<String> sniffNotes) {
}

record ImportDraftRow(
        int rowIndex,
        LocalDate date,
        LocalDate postDate,
        String descriptionRaw,
        String descriptionNormalized,
        String merchant,
        String amount,
        Long suggestedCategoryId,
        String suggestedKind,
        List<String> suggestedTags,
        Long suggestedMatchedRuleId,
        boolean isDuplicate,
        Map<String, String> raw) {
    ImportDraftRow withDuplicate(boolean duplicate) {
        return new ImportDraftRow(
                rowIndex,
                date,
                postDate,
                descriptionRaw,
                descriptionNormalized,
                merchant,
                amount,
                suggestedCategoryId,
                suggestedKind,
                suggestedTags,
                suggestedMatchedRuleId,
                duplicate,
                raw);
    }

    BigDecimal amountValue() {
        return amount == null ? null : new BigDecimal(amount);
    }
}

record ImportBatchResponse(
        long id,
        String sourceFilename,
        String importerName,
        long accountId,
        LocalDateTime importedAt,
        int rowCountTotal,
        int rowCountApplied,
        int rowCountDuplicate,
        String status,
        String notes) {
}

record DuplicateKey(LocalDate date, BigDecimal amount, String description) {
}
