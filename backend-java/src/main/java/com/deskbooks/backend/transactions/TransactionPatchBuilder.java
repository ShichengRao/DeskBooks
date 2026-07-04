package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class TransactionPatchBuilder {
    private static final String EXCLUDED = "is_excluded_from_totals";

    private final TransactionPatchCategorization categorization;
    private final TransactionPatchFields fields = new TransactionPatchFields();

    TransactionPatchBuilder(TransactionLookup lookup) {
        this.categorization = new TransactionPatchCategorization(lookup);
    }

    List<TransactionColumnValue> patchValues(Connection connection, JsonNode body) throws SQLException {
        List<TransactionColumnValue> values = new ArrayList<>();
        fields.addDate(values, body, "date");
        fields.addDate(values, body, "post_date");
        fields.addDescription(values, body);
        fields.addText(values, body, "merchant");
        fields.addBigDecimal(values, body, "amount");
        categorization.addPatchCategory(connection, values, body);
        categorization.addPatchKind(values, body);
        fields.addBoolean(values, body, EXCLUDED);
        fields.addText(values, body, "notes");
        fields.addLong(values, body, "transfer_pair_id");
        return values;
    }

    TransactionCategoryInfo bulkCategory(Connection connection, JsonNode body) throws SQLException {
        return categorization.bulkCategory(connection, body);
    }

    List<TransactionColumnValue> bulkValues(JsonNode body, TransactionCategoryInfo category) {
        List<TransactionColumnValue> values = new ArrayList<>();
        categorization.addBulkValues(values, body, category);
        fields.addBoolean(values, body, EXCLUDED);
        return values;
    }
}
