package com.deskbooks.backend.imports;

import com.deskbooks.backend.imports.ImportController.ImportDraftRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class DictCsvImporter extends HeaderCsvImporter {
    @Override
    public List<ImportDraftRow> parse(String csvText) {
        CsvData data = CsvRows.readDictRows(csvText);
        List<ImportDraftRow> out = new ArrayList<>();
        for (int i = 0; i < data.rows().size(); i++) {
            ImportDraftRow row = parseRow(i, data.rows().get(i));
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    abstract ImportDraftRow parseRow(int index, Map<String, String> row);
}
