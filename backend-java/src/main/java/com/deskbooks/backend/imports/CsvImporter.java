package com.deskbooks.backend.imports;

import java.util.List;

interface CsvImporter {
    String name();

    String label();

    boolean canHandle(String csvText);

    List<ImportController.ImportDraftRow> parse(String csvText);
}
