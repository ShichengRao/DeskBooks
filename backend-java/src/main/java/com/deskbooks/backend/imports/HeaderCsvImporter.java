package com.deskbooks.backend.imports;

import java.util.List;

abstract class HeaderCsvImporter implements CsvImporter {
    @Override
    public boolean canHandle(String csvText) {
        return canHandleHeader(CsvRows.header(csvText));
    }

    abstract boolean canHandleHeader(List<String> header);
}
