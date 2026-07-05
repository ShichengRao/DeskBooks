package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import tools.jackson.databind.JsonNode;

record TransactionCreatePayload(
        long accountId,
        String date,
        String postDate,
        String descriptionRaw,
        String descriptionNormalized,
        String merchant,
        BigDecimal amount,
        Long categoryId,
        String kind,
        boolean excludedFromTotals,
        String notes) {
    static TransactionCreatePayload from(
            Connection connection,
            JsonNode body,
            TransactionLookup lookup) throws SQLException {
        long accountId = TransactionJsonValues.requiredLong(body, "account_id");
        lookup.requireAccount(connection, accountId);

        Long categoryId = TransactionJsonValues.optionalLong(body, "category_id");
        TransactionCategoryInfo category = categoryId == null ? null : lookup.categoryOr404(connection, categoryId);

        String descriptionRaw = TransactionJsonText.required(body, "description_raw");
        return new TransactionCreatePayload(
                accountId,
                TransactionJsonValues.requiredDate(body, "date").toString(),
                TransactionJsonValues.optionalDateString(body, "post_date"),
                descriptionRaw,
                normalizedDescription(body, descriptionRaw),
                TransactionJsonText.blankToNull(TransactionJsonText.orNull(body, "merchant")),
                TransactionJsonValues.requiredDecimal(body, "amount"),
                categoryId,
                createKind(body, category),
                TransactionJsonValues.booleanOrDefault(body, "is_excluded_from_totals", false),
                TransactionJsonText.blankToNull(TransactionJsonText.orNull(body, "notes")));
    }

    void bind(PreparedStatement statement) throws SQLException {
        statement.setLong(1, accountId);
        statement.setString(2, date);
        statement.setString(3, postDate);
        statement.setString(4, descriptionRaw);
        statement.setString(5, descriptionNormalized);
        statement.setString(6, merchant);
        statement.setBigDecimal(7, amount);
        TransactionSql.setNullableLong(statement, 8, categoryId);
        statement.setString(9, kind);
        statement.setBoolean(10, excludedFromTotals);
        statement.setString(11, notes);
    }

    private static String createKind(JsonNode body, TransactionCategoryInfo category) {
        String kind = TransactionJsonText.orDefault(body, "kind", "uncategorized");
        if (category != null && !body.has("kind")) {
            return category.kind();
        }
        return kind;
    }

    private static String normalizedDescription(JsonNode body, String descriptionRaw) {
        String normalized = TransactionJsonText.orNull(body, "description_normalized");
        if (normalized == null || normalized.isBlank()) {
            return TransactionJsonText.normalizeDescription(descriptionRaw);
        }
        return normalized;
    }
}
