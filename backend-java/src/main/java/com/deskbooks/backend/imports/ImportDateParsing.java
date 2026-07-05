package com.deskbooks.backend.imports;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

final class ImportDateParsing {
    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            new DateTimeFormatterBuilder()
                    .appendPattern("M/d/")
                    .appendValue(ChronoField.YEAR, 2, 4, SignStyle.NORMAL)
                    .toFormatter(Locale.US));

    private ImportDateParsing() {
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().replace("\"", "");
        LocalDate offsetDate = parseOffsetDate(trimmed);
        if (offsetDate != null) {
            return offsetDate;
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // Continue.
            }
        }
        return null;
    }

    private static LocalDate parseOffsetDate(String trimmed) {
        try {
            return trimmed.contains("T") ? OffsetDateTime.parse(trimmed).toLocalDate() : null;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
