package com.deskbooks.backend.imports;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.deskbooks.backend.imports.ImportParsing.cellAmount;
import static com.deskbooks.backend.imports.ImportParsing.cellDate;
import static com.deskbooks.backend.imports.ImportParsing.cellString;
import static com.deskbooks.backend.imports.ImportParsing.guessMerchant;
import static com.deskbooks.backend.imports.ImportParsing.moneyString;
import static com.deskbooks.backend.imports.ImportParsing.normalize;

import com.deskbooks.backend.foundation.ApiException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;

final class AmexWorkbookParser {
    private AmexWorkbookParser() {
    }

    static List<ImportDraftRow> parse(byte[] data) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = firstTransactionSheet(workbook);
            if (sheet == null) {
                return List.of();
            }

            int headerRowIndex = headerRowIndex(sheet);
            if (headerRowIndex < 0) {
                return List.of();
            }

            return parseRows(sheet, headerRowIndex);
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read xlsx file");
        }
    }

    private static Sheet firstTransactionSheet(Workbook workbook) {
        Sheet sheet = workbook.getSheet("Transaction Details");
        if (sheet != null) {
            return sheet;
        }
        return workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
    }

    private static int headerRowIndex(Sheet sheet) {
        int maxHeaderRow = Math.min(sheet.getLastRowNum(), 29);
        for (int rowIndex = 0; rowIndex <= maxHeaderRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if ("date".equalsIgnoreCase(cellString(row == null ? null : row.getCell(0)).trim())) {
                return rowIndex;
            }
        }
        return -1;
    }

    private static List<ImportDraftRow> parseRows(Sheet sheet, int headerRowIndex) {
        List<ImportDraftRow> rows = new ArrayList<>();
        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            ImportDraftRow row = parseRow(sheet.getRow(rowIndex), rowIndex, headerRowIndex);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static ImportDraftRow parseRow(Row row, int rowIndex, int headerRowIndex) {
        if (row == null) {
            return null;
        }
        LocalDate date = cellDate(row.getCell(0));
        String rawDescription = cellString(row.getCell(1));
        BigDecimal amount = cellAmount(row.getCell(2));
        if (date == null || rawDescription.isBlank() || amount == null) {
            return null;
        }

        String normalized = normalize(rawDescription);
        return new ImportDraftRow(
                rowIndex - headerRowIndex - 1,
                date,
                null,
                rawDescription,
                normalized,
                guessMerchant(normalized),
                moneyString(amount.negate()),
                null,
                suggestedKind(normalized),
                List.of(),
                null,
                false,
                Map.of("row", String.valueOf(rowIndex + 1)));
    }

    private static String suggestedKind(String normalizedDescription) {
        String upper = normalizedDescription.toUpperCase(Locale.ROOT);
        return upper.contains("AUTOPAY PAYMENT")
                || upper.contains("PAYMENT - THANK YOU")
                || upper.contains("PAYMENT RECEIVED")
                ? "cc_payment"
                : "uncategorized";
    }
}
