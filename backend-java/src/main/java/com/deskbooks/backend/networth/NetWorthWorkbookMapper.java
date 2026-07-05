package com.deskbooks.backend.networth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.HttpStatus;

final class NetWorthWorkbookMapper {
    private static final String DATES_SHEET = "Dates";

    private NetWorthWorkbookMapper() {
    }

    static NetWorthWorkbookMapping mapRows(
            Workbook workbook,
            Map<String, String> requestedMap,
            Map<String, Long> accountByName) {
        Map<String, String> accountMap = requestedMap == null ? Map.of() : requestedMap;
        if (!accountMap.isEmpty()) {
            return NetWorthRequestedWorkbookMapper.mapRows(workbook, accountMap, accountByName);
        }

        List<NetWorthWorkbookRow> rows = new ArrayList<>();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (DATES_SHEET.equals(sheet.getSheetName())) {
                continue;
            }
            addAutoMappedRows(sheet, accountByName, rows);
        }
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "No account rows were found. Add a JSON account map using keys like \"Sheet name!12\" and account names as values.");
        }
        return new NetWorthWorkbookMapping(rows, List.of());
    }

    private static void addAutoMappedRows(
            Sheet sheet,
            Map<String, Long> accountByName,
            List<NetWorthWorkbookRow> rows) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            String label = NetWorthWorkbookCells.string(row == null ? null : row.getCell(0)).trim();
            Long accountId = accountByName.get(label);
            if (accountId != null) {
                rows.add(new NetWorthWorkbookRow(sheet, rowIndex, accountId));
            }
        }
    }
}

record NetWorthWorkbookRow(Sheet sheet, int rowIndex, long accountId) {
}

record NetWorthWorkbookMapping(List<NetWorthWorkbookRow> rows, List<String> missingAccounts) {
}
