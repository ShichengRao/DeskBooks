package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.CsvRows.value;
import static com.deskbooks.backend.imports.ImportParsing.normalize;
import static com.deskbooks.backend.imports.ImportParsing.parseDate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

abstract class BankImporter extends DictCsvImporter {
    ImportDraftRow bankDraft(
            int index,
            Map<String, String> row,
            String dateKey,
            String postDateKey,
            BigDecimal amount,
            String description,
            String extra,
            boolean creditCard) {
        LocalDate date = parseDate(value(row, dateKey));
        if (date == null || amount == null) {
            return null;
        }
        String desc = normalize(description);
        return CsvDrafts.draft(
                index,
                date,
                postDateKey == null ? null : parseDate(value(row, postDateKey)),
                description,
                amount,
                ImportKindSuggester.suggest(desc, amount, creditCard, extra),
                row);
    }
}
