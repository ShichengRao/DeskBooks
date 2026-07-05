package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.CsvDrafts.draft;
import static com.deskbooks.backend.imports.CsvRows.rowMap;
import static com.deskbooks.backend.imports.ImportParsing.normalize;
import static com.deskbooks.backend.imports.ImportParsing.parseAmount;
import static com.deskbooks.backend.imports.ImportParsing.parseDate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ContributionHistoryImporter extends HeaderCsvImporter {
    @Override
    public String name() {
        return "contribution_history";
    }

    @Override
    public String label() {
        return "Fidelity Charitable Contribution History";
    }

    @Override
    boolean canHandleHeader(List<String> header) {
        return header.contains("Contribution ID") && header.contains("Estimated Amount");
    }

    @Override
    public boolean canHandle(String csvText) {
        return headerIndex(CsvRows.readCsv(csvText)) >= 0;
    }

    @Override
    public List<ImportDraftRow> parse(String csvText) {
        List<List<String>> rows = CsvRows.readCsv(csvText);
        int headerIndex = headerIndex(rows);
        if (headerIndex < 0) {
            return List.of();
        }
        List<String> header = rows.get(headerIndex).stream().map(String::trim).toList();
        List<ImportDraftRow> out = new ArrayList<>();
        int rowIndex = 0;
        for (int i = headerIndex + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            if (cells.stream().noneMatch(cell -> !cell.trim().isEmpty())) {
                continue;
            }
            ImportDraftRow draft = parseContribution(rowIndex, header, cells);
            if (draft != null) {
                out.add(draft);
                rowIndex++;
            }
        }
        return out;
    }

    private ImportDraftRow parseContribution(int rowIndex, List<String> header, List<String> cells) {
        Map<String, String> row = rowMap(header, cells);
        LocalDate date = parseDate(row.getOrDefault("Received Date", row.get("Submitted Date")));
        BigDecimal amount = parseAmount(row.getOrDefault("Estimated Amount", row.get("Net Proceeds")));
        if (date == null || amount == null) {
            return null;
        }
        String desc = normalize(row.getOrDefault("Description", "Contribution"));
        String symbol = normalize(row.getOrDefault("Symbol", ""));
        String raw = (symbol + " " + desc).trim();
        return draft(rowIndex, date, null, raw, amount.negate(), "donation", "Contribution", row);
    }

    private int headerIndex(List<List<String>> rows) {
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.contains("Contribution ID") && row.contains("Estimated Amount")) {
                return i;
            }
        }
        return -1;
    }
}
