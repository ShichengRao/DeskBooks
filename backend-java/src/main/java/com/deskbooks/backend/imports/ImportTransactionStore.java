package com.deskbooks.backend.imports;

import static com.deskbooks.backend.imports.ImportParsing.money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class ImportTransactionStore {
    void insert(
            Connection connection,
            long accountId,
            long batchId,
            ImportDraftRow row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                  account_id, date, post_date, description_raw, description_normalized,
                  merchant, amount, category_id, kind, is_user_categorized,
                  is_excluded_from_totals, notes, import_batch_id, matched_rule_id, raw,
                  updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, NULL, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            bindTransaction(statement, accountId, batchId, row);
            statement.executeUpdate();
        }
    }

    void deleteBatch(Connection connection, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM transactions WHERE import_batch_id = ?")) {
            statement.setLong(1, batchId);
            statement.executeUpdate();
        }
    }

    private void bindTransaction(
            PreparedStatement statement,
            long accountId,
            long batchId,
            ImportDraftRow row) throws SQLException {
        statement.setLong(1, accountId);
        statement.setString(2, row.date().toString());
        statement.setString(3, row.postDate() == null ? null : row.postDate().toString());
        statement.setString(4, row.descriptionRaw());
        statement.setString(5, row.descriptionNormalized());
        statement.setString(6, row.merchant());
        statement.setBigDecimal(7, money(row.amountValue()));
        bindNullableLong(statement, 8, row.suggestedCategoryId());
        statement.setString(9, row.suggestedKind() == null ? "uncategorized" : row.suggestedKind());
        statement.setLong(10, batchId);
        bindNullableLong(statement, 11, row.suggestedMatchedRuleId());
        statement.setString(12, ImportSqlValues.rawJson(row.raw()));
    }

    private void bindNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }
}
