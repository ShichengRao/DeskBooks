package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import tools.jackson.databind.JsonNode;

final class RulePatchBuilder {
    private final RuleLookup lookup;
    private final RulePatchValues values = new RulePatchValues();

    RulePatchBuilder(RuleLookup lookup) {
        this.lookup = lookup;
    }

    List<RuleColumnValue> patchValues(Connection connection, JsonNode body) throws SQLException {
        lookup.validateReferences(
                connection,
                values.referenceId(body, RulePatchValues.MATCH_ACCOUNT_ID),
                values.referenceId(body, RulePatchValues.SET_CATEGORY_ID));

        return values.from(body);
    }
}
