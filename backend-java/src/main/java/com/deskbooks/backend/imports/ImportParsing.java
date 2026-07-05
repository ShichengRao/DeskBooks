package com.deskbooks.backend.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

import com.deskbooks.backend.workbooks.WorkbookCells;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;

final class ImportParsing {
    private static final DataFormatter EXCEL_FORMATTER = new DataFormatter(Locale.US);

    private ImportParsing() {
    }

    static BigDecimal money(BigDecimal value) {
        return ImportAmountParsing.money(value);
    }

    static String moneyString(BigDecimal value) {
        return ImportAmountParsing.moneyString(value);
    }

    static BigDecimal parseAmount(String value) {
        return ImportAmountParsing.parseAmount(value);
    }

    static BigDecimal cellAmount(Cell cell) {
        return WorkbookCells.decimal(cell, EXCEL_FORMATTER, ImportParsing::parseAmount);
    }

    static LocalDate parseDate(String value) {
        return ImportDateParsing.parseDate(value);
    }

    static LocalDate cellDate(Cell cell) {
        return WorkbookCells.date(cell, EXCEL_FORMATTER, ImportParsing::parseDate);
    }

    static String cellString(Cell cell) {
        return WorkbookCells.string(cell, EXCEL_FORMATTER);
    }

    static String normalize(String raw) {
        return ImportTextParsing.normalize(raw);
    }

    static String guessMerchant(String raw) {
        return ImportTextParsing.guessMerchant(raw);
    }
}
