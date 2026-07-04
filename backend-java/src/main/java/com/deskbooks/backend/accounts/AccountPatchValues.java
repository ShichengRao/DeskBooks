package com.deskbooks.backend.accounts;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class AccountPatchValues {
    private AccountPatchValues() {
    }

    static List<AccountPatchValue> from(JsonNode body) {
        List<AccountPatchValue> values = new ArrayList<>();
        addText(values, body, "name");
        addText(values, body, "institution");
        addText(values, body, "account_category");
        addText(values, body, "type");
        addText(values, body, "currency");
        addText(values, body, "sign_convention");
        addText(values, body, "url");
        addText(values, body, "notes");
        addBoolean(values, body, "is_closed");
        addDate(values, body, "opened_at");
        addDate(values, body, "closed_at");
        addInteger(values, body, "sort_order");
        return values;
    }

    private static void addText(List<AccountPatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new AccountPatchValue(field, node == null || node.isNull() ? null : node.asText()));
        }
    }

    private static void addBoolean(List<AccountPatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new AccountPatchValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }

    private static void addInteger(List<AccountPatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new AccountPatchValue(field, node == null || node.isNull() ? null : node.asInt()));
        }
    }

    private static void addDate(List<AccountPatchValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new AccountPatchValue(field, node == null || node.isNull() ? null : Date.valueOf(node.asText())));
        }
    }
}

record AccountPatchValue(String column, Object value) {
}
