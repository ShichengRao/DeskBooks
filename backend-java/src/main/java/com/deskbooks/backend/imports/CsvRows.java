package com.deskbooks.backend.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CsvRows {
    private CsvRows() {
    }

    static CsvData readDictRows(String csvText) {
        List<List<String>> rows = readCsv(csvText);
        if (rows.isEmpty()) {
            return new CsvData(List.of(), List.of());
        }
        List<String> header = rows.get(0).stream().map(cell -> cell.trim().replace("\"", "")).toList();
        List<Map<String, String>> dictRows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().noneMatch(cell -> !cell.trim().isEmpty())) {
                continue;
            }
            dictRows.add(rowMap(header, row));
        }
        return new CsvData(header, dictRows);
    }

    static Map<String, String> rowMap(List<String> header, List<String> cells) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(header.size(), cells.size()); i++) {
            map.put(i < header.size() ? header.get(i) : "col" + i, i < cells.size() ? cells.get(i) : "");
        }
        return map;
    }

    static List<String> header(String csvText) {
        List<List<String>> rows = readCsv(csvText);
        return rows.isEmpty() ? List.of() : rows.get(0).stream().map(String::trim).toList();
    }

    static List<List<String>> readCsv(String csvText) {
        return CsvReader.read(csvText);
    }

    static boolean hasAll(List<String> header, String... names) {
        Set<String> values = headerSet(header);
        for (String name : names) {
            if (!values.contains(name.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    static boolean hasAny(List<String> header, String... names) {
        Set<String> values = headerSet(header);
        for (String name : names) {
            if (values.contains(name.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static Set<String> headerSet(List<String> header) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : header) {
            out.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    static String value(Map<String, String> row, String key) {
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return "";
    }
}

record CsvData(List<String> header, List<Map<String, String>> rows) {
}
