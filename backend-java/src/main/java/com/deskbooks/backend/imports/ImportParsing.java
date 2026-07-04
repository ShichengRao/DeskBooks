package com.deskbooks.backend.imports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

final class ImportParsing {
    private static final DataFormatter EXCEL_FORMATTER = new DataFormatter(Locale.US);

    private ImportParsing() {
    }

    static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    static String moneyString(BigDecimal value) {
        return money(value).toPlainString();
    }

    static BigDecimal parseAmount(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replace("$", "").replace(",", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            cleaned = "-" + cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static BigDecimal cellAmount(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            } catch (IllegalStateException ignored) {
                // Fall through to formatted text parsing.
            }
        }
        return parseAmount(cellString(cell));
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().replace("\"", "");
        try {
            if (trimmed.contains("T")) {
                return OffsetDateTime.parse(trimmed).toLocalDate();
            }
        } catch (DateTimeParseException ignored) {
            // Try the local-date formats below.
        }
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                new DateTimeFormatterBuilder()
                        .appendPattern("M/d/")
                        .appendValue(ChronoField.YEAR, 2, 4, SignStyle.NORMAL)
                        .toFormatter(Locale.US));
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // Continue.
            }
        }
        return null;
    }

    static LocalDate cellDate(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                if (DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
            } catch (RuntimeException ignored) {
                // Fall through to formatted text parsing.
            }
        }
        return parseDate(cellString(cell));
    }

    static String cellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return EXCEL_FORMATTER.formatCellValue(cell).trim();
    }

    static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    static String guessMerchant(String raw) {
        String value = normalize(raw)
                .replaceFirst("(?i)^(DD \\*|TST\\*|SQ \\*|SP \\*|PY \\*|PAYPAL \\*|VENMO \\*)", "")
                .replaceFirst("\\s+[A-Z]{2}\\s*$", "")
                .replaceFirst("\\s+\\d{6,}\\s*$", "")
                .replaceAll("\\s+#\\d+", "")
                .trim();
        if (value.isEmpty()) {
            return value;
        }
        StringBuilder title = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            title.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(" ");
        }
        return title.toString().trim();
    }
}
