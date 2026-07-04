package com.deskbooks.backend.workbooks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.function.Function;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

@SuppressWarnings("PMD.LawOfDemeter")
public final class WorkbookCells {
    private WorkbookCells() {
    }

    public static BigDecimal decimal(
            Cell cell,
            DataFormatter formatter,
            Function<String, BigDecimal> textParser) {
        if (isBlank(cell)) {
            return null;
        }
        if (isNumericLike(cell.getCellType())) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            } catch (IllegalStateException ignored) {
                // Fall through to formatted text parsing.
            }
        }
        return textParser.apply(string(cell, formatter));
    }

    public static LocalDate date(
            Cell cell,
            DataFormatter formatter,
            Function<String, LocalDate> textParser) {
        if (isBlank(cell)) {
            return null;
        }
        if (isNumericLike(cell.getCellType())) {
            try {
                if (DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
            } catch (IllegalStateException ignored) {
                // Fall through to formatted text parsing.
            }
        }
        return textParser.apply(string(cell, formatter));
    }

    public static String string(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static boolean isBlank(Cell cell) {
        return cell == null || cell.getCellType() == CellType.BLANK;
    }

    private static boolean isNumericLike(CellType type) {
        return type == CellType.NUMERIC || type == CellType.FORMULA;
    }
}
