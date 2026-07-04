package com.deskbooks.backend.imports;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class ImportPreviewParser {
    private static final String AMEX_IMPORTER = "amex";

    private final List<CsvImporter> importers;

    ImportPreviewParser(List<CsvImporter> importers) {
        this.importers = importers;
    }

    ParsedImport parse(byte[] data, String filename, String importerName) {
        if (isWorkbook(filename)) {
            return parseAmexWorkbook(data);
        }
        return parseCsv(data, importerName);
    }

    private ParsedImport parseAmexWorkbook(byte[] data) {
        List<ImportController.ImportDraftRow> rows = AmexWorkbookParser.parse(data);
        if (rows.isEmpty()) {
            throw noImporter();
        }
        return new ParsedImport(AMEX_IMPORTER, rows, List.of(matchedImportersNote(AMEX_IMPORTER)));
    }

    private ParsedImport parseCsv(byte[] data, String importerName) {
        String csvText = new String(data, StandardCharsets.UTF_8);
        List<CsvImporter> matched = importers.stream()
                .filter(importer -> importer.canHandle(csvText))
                .toList();
        CsvImporter chosen = chooseImporter(importerName, matched);
        return new ParsedImport(
                chosen.name(),
                chosen.parse(csvText),
                List.of(matchedImportersNote(matchedImporterNames(matched))));
    }

    private CsvImporter chooseImporter(String importerName, List<CsvImporter> matched) {
        if (importerName != null && !importerName.isBlank()) {
            return importers.stream()
                    .filter(importer -> importer.name().equals(importerName))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "unknown importer: " + importerName));
        }
        if (matched.isEmpty()) {
            throw noImporter();
        }
        return matched.get(0);
    }

    private boolean isWorkbook(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    private String matchedImporterNames(List<CsvImporter> matched) {
        if (matched.isEmpty()) {
            return "";
        }
        return String.join(", ", matched.stream().map(CsvImporter::name).toList());
    }

    private String matchedImportersNote(String names) {
        return "matched importers: " + names;
    }

    private ApiException noImporter() {
        return new ApiException(HttpStatus.BAD_REQUEST, "no importer can handle this file");
    }

    record ParsedImport(
            String importerName,
            List<ImportController.ImportDraftRow> rows,
            List<String> sniffNotes) {
    }
}
