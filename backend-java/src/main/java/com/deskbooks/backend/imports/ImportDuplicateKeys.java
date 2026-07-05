package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.ImportParsing.money;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

final class ImportDuplicateKeys {
    private ImportDuplicateKeys() {
    }

    static DuplicateKey from(ResultSet rs) throws SQLException {
        return new DuplicateKey(
                LocalDate.parse(rs.getString("date")),
                money(rs.getBigDecimal("amount")),
                normalizedDescription(rs.getString("description_normalized")));
    }

    static DuplicateKey from(ImportDraftRow row) {
        return new DuplicateKey(
                row.date(),
                money(row.amountValue()),
                normalizedDescription(row.descriptionNormalized()));
    }

    private static String normalizedDescription(String value) {
        return value == null ? "" : value;
    }
}
