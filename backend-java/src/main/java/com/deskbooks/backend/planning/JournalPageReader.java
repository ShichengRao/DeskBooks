package com.deskbooks.backend.planning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class JournalPageReader {
    private final JournalDocxPageReader docx = new JournalDocxPageReader();

    List<String> pages(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (isTextImport(filename)) {
                return splitTextPages(Files.readString(path));
            }
            if (filename.endsWith(".docx")) {
                return docx.pages(path);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read journal import text");
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "supported journal imports: .txt, .md, .markdown, .docx");
    }

    private boolean isTextImport(String filename) {
        return filename.endsWith(".txt") || filename.endsWith(".md") || filename.endsWith(".markdown");
    }

    private List<String> splitTextPages(String text) {
        if (text.contains("\f")) {
            return List.of(text.split("\\f"));
        }
        List<String> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            String marker = line.trim().toLowerCase(Locale.ROOT);
            if (isPageMarker(marker)) {
                pages.add(String.join("\n", current));
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        pages.add(String.join("\n", current));
        return JournalPages.nonBlank(pages);
    }

    private boolean isPageMarker(String marker) {
        return marker.equals("--- page ---") || marker.equals("=== page ===");
    }
}
