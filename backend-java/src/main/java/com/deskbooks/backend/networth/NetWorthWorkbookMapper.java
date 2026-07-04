package com.deskbooks.backend.networth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.deskbooks.backend.foundation.ApiException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.HttpStatus;

final class NetWorthWorkbookMapper {
    private NetWorthWorkbookMapper() {
    }

    static NetWorthWorkbookMapping mapRows(
            Workbook workbook,
            Map<String, String> requestedMap,
            Map<String, Long> accountByName) {
        Map<String, String> accountMap = requestedMap == null ? Map.of() : requestedMap;
        if (!accountMap.isEmpty()) {
            return mappedRequestedRows(workbook, accountMap, accountByName);
        }

        List<NetWorthWorkbookRow> rows = new ArrayList<>();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if ("Dates".equals(sheet.getSheetName())) {
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

    private static NetWorthWorkbookMapping mappedRequestedRows(
            Workbook workbook,
            Map<String, String> accountMap,
            Map<String, Long> accountByName) {
        List<NetWorthWorkbookRow> rows = new ArrayList<>();
        Set<String> missingAccounts = new LinkedHashSet<>();
        List<String> invalidKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : accountMap.entrySet()) {
            addMappedRequestedRow(workbook, accountByName, rows, missingAccounts, invalidKeys, entry);
        }
        if (!invalidKeys.isEmpty()) {
            Collections.sort(invalidKeys);
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid account_map row key(s): " + String.join(", ", invalidKeys));
        }
        List<String> missing = new ArrayList<>(missingAccounts);
        Collections.sort(missing);
        return new NetWorthWorkbookMapping(rows, missing);
    }

    private static void addMappedRequestedRow(
            Workbook workbook,
            Map<String, Long> accountByName,
            List<NetWorthWorkbookRow> rows,
            Set<String> missingAccounts,
            List<String> invalidKeys,
            Map.Entry<String, String> entry) {
        String accountName = entry.getValue();
        Long accountId = accountByName.get(accountName);
        if (accountId == null) {
            missingAccounts.add(accountName);
            return;
        }
        String key = entry.getKey();
        int bang = key == null ? -1 : key.lastIndexOf('!');
        if (bang <= 0 || bang == key.length() - 1) {
            invalidKeys.add(key);
            return;
        }
        addMappedSheetRow(workbook, rows, invalidKeys, accountId, key, bang);
    }

    private static void addMappedSheetRow(
            Workbook workbook,
            List<NetWorthWorkbookRow> rows,
            List<String> invalidKeys,
            long accountId,
            String key,
            int bang) {
        String sheetName = key.substring(0, bang);
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            invalidKeys.add(key);
            return;
        }
        try {
            int oneBasedRow = Integer.parseInt(key.substring(bang + 1));
            if (oneBasedRow < 1) {
                invalidKeys.add(key);
                return;
            }
            rows.add(new NetWorthWorkbookRow(sheet, oneBasedRow - 1, accountId));
        } catch (NumberFormatException exception) {
            invalidKeys.add(key);
        }
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
