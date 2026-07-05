package com.deskbooks.backend.imports;

import java.util.List;

interface CsvImporter {
    String name();

    String label();

    boolean canHandle(String csvText);

    List<ImportDraftRow> parse(String csvText);
}
