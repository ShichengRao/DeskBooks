package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.deskbooks.backend.workbooks.WorkbookCells;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;

final class NetWorthWorkbookCells {
    private static final DataFormatter EXCEL_FORMATTER = new DataFormatter();

    private NetWorthWorkbookCells() {
    }

    static BigDecimal decimal(Cell cell) {
        return WorkbookCells.decimal(cell, EXCEL_FORMATTER, NetWorthWorkbookCells::parseDecimal);
    }

    static LocalDate date(Cell cell) {
        return WorkbookCells.date(cell, EXCEL_FORMATTER, NetWorthWorkbookCells::parseIsoDate);
    }

    static String string(Cell cell) {
        return WorkbookCells.string(cell, EXCEL_FORMATTER);
    }

    private static BigDecimal parseDecimal(String value) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static LocalDate parseIsoDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
