package com.deskbooks.backend.planning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class JournalImportParser {
    private final JournalPageReader pageReader = new JournalPageReader();
    private final JournalImportDrafts draftBuilder = new JournalImportDrafts();

    JournalImportPreviewResponse preview(JournalImportPreviewRequest body) {
        Path path = Path.of(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        List<JournalImportDraftResponse> drafts = draftBuilder.drafts(path, pageReader.pages(path));
        if (drafts.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no journal text found");
        }
        return new JournalImportPreviewResponse(path.getFileName().toString(), drafts);
    }
}
