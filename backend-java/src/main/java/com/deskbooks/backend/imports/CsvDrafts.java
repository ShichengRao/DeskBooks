package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.ImportParsing.guessMerchant;
import static com.deskbooks.backend.imports.ImportParsing.money;
import static com.deskbooks.backend.imports.ImportParsing.moneyString;
import static com.deskbooks.backend.imports.ImportParsing.normalize;
import static com.deskbooks.backend.imports.ImportParsing.parseAmount;

import com.deskbooks.backend.imports.ImportController.ImportDraftRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class CsvDrafts {
    private CsvDrafts() {
    }

    static ImportDraftRow draft(
            int index,
            LocalDate date,
            LocalDate postDate,
            String raw,
            BigDecimal amount,
            String kind,
            Map<String, String> source) {
        return draft(index, date, postDate, raw, amount, kind, null, source);
    }

    static ImportDraftRow draft(
            int index,
            LocalDate date,
            LocalDate postDate,
            String raw,
            BigDecimal amount,
            String kind,
            String merchant,
            Map<String, String> source) {
        String normalized = normalize(raw);
        return new ImportDraftRow(
                index,
                date,
                postDate,
                raw,
                normalized,
                merchant == null ? guessMerchant(normalized) : merchant,
                moneyString(amount),
                null,
                kind,
                List.of(),
                null,
                false,
                source);
    }

    static BigDecimal debitCredit(Map<String, String> row, String debitKey, String creditKey) {
        BigDecimal debit = parseAmount(CsvRows.value(row, debitKey));
        BigDecimal credit = parseAmount(CsvRows.value(row, creditKey));
        if (debit != null && debit.compareTo(BigDecimal.ZERO) != 0) {
            return debit.abs().negate();
        }
        if (credit != null && credit.compareTo(BigDecimal.ZERO) != 0) {
            return credit.abs();
        }
        return money(BigDecimal.ZERO);
    }
}
