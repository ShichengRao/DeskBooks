package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

final class NetWorthWorkbookCells {
    private static final DataFormatter EXCEL_FORMATTER = new DataFormatter();

    private NetWorthWorkbookCells() {
    }

    static BigDecimal decimal(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            } catch (IllegalStateException ignored) {
                // Fall through to string parsing.
            }
        }
        String value = string(cell);
        if (value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static LocalDate date(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                if (DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
            } catch (RuntimeException ignored) {
                // Fall through to ISO string parsing.
            }
        }
        try {
            return LocalDate.parse(string(cell));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static String string(Cell cell) {
        if (cell == null) {
            return "";
        }
        return EXCEL_FORMATTER.formatCellValue(cell).trim();
    }
}
