package com.deskbooks.backend.imports;

import java.util.ArrayList;
import java.util.List;

final class CsvReader {
    private static final int NEXT_CHARACTER_OFFSET = 1;
    private static final char QUOTE = '"';
    private static final char COMMA = ',';
    private static final char NEWLINE = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final String UTF8_BOM = "\uFEFF";

    private final String text;
    private final List<List<String>> rows = new ArrayList<>();
    private final StringBuilder cell = new StringBuilder();
    private List<String> row = new ArrayList<>();
    private boolean quoted;

    private CsvReader(String csvText) {
        this.text = stripBom(csvText);
    }

    static List<List<String>> read(String csvText) {
        return new CsvReader(csvText).readRows();
    }

    private List<List<String>> readRows() {
        for (int index = 0; index < text.length(); index++) {
            index = consume(text.charAt(index), index);
        }
        finishFinalRow();
        return rows;
    }

    private int consume(char ch, int index) {
        if (quoted) {
            return consumeQuoted(ch, index);
        }
        return consumePlain(ch, index);
    }

    private int consumeQuoted(char ch, int index) {
        if (ch != QUOTE) {
            cell.append(ch);
            return index;
        }
        if (hasEscapedQuote(index)) {
            cell.append(QUOTE);
            return index + NEXT_CHARACTER_OFFSET;
        }
        quoted = false;
        return index;
    }

    private boolean hasEscapedQuote(int index) {
        int nextIndex = index + NEXT_CHARACTER_OFFSET;
        return nextIndex < text.length() && text.charAt(nextIndex) == QUOTE;
    }

    private int consumePlain(char ch, int index) {
        if (ch == QUOTE) {
            quoted = true;
        } else if (ch == COMMA) {
            finishCell();
        } else if (ch == NEWLINE) {
            finishRow();
        } else if (ch != CARRIAGE_RETURN) {
            cell.append(ch);
        }
        return index;
    }

    private void finishCell() {
        row.add(cell.toString());
        cell.setLength(0);
    }

    private void finishRow() {
        finishCell();
        rows.add(row);
        row = new ArrayList<>();
    }

    private void finishFinalRow() {
        finishCell();
        if (row.stream().anyMatch(value -> !value.isEmpty())) {
            rows.add(row);
        }
    }

    private static String stripBom(String csvText) {
        return csvText.startsWith(UTF8_BOM) ? csvText.substring(UTF8_BOM.length()) : csvText;
    }
}
