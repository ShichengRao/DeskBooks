package com.deskbooks.backend.planning;

import java.util.List;

final class JournalPages {
    private JournalPages() {
    }

    static List<String> nonBlank(List<String> pages) {
        return pages.stream()
                .map(String::trim)
                .filter(page -> !page.isBlank())
                .toList();
    }
}
