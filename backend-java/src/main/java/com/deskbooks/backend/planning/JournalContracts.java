package com.deskbooks.backend.planning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

record JournalEntryRequest(
        @NotNull LocalDate entryDate,
        @NotBlank String title,
        @NotBlank String bodyMarkdown,
        Long goalId) {
}

record JournalEntryResponse(
        long id,
        LocalDate entryDate,
        String title,
        String bodyMarkdown,
        Long goalId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

record JournalEntryRevisionResponse(
        long id,
        long entryId,
        String title,
        String bodyMarkdown,
        LocalDate entryDate,
        Long goalId,
        LocalDateTime changedAt,
        String changeSummary) {
}

record JournalImportPreviewRequest(@NotBlank String path) {
}

record JournalImportDraftResponse(int pageNumber, String title, String bodyMarkdown) {
}

record JournalImportPreviewResponse(String sourceFilename, List<JournalImportDraftResponse> drafts) {
}
