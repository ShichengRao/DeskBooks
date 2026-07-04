package com.deskbooks.backend.planning;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class JournalImportDrafts {
    List<JournalImportDraftResponse> drafts(Path path, List<String> pages) {
        String baseTitle = stripExtension(path.getFileName().toString());
        List<JournalImportDraftResponse> drafts = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i).trim();
            if (!page.isBlank()) {
                drafts.add(new JournalImportDraftResponse(
                        i + 1,
                        baseTitle + " page " + (i + 1),
                        page));
            }
        }
        return drafts;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }
}
