package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;

final class GoalPatchApplier {
    private static final String LINKED_ACCOUNT_IDS = "linked_account_ids";
    private static final String ARCHIVED = "archived";
    private static final String STATUS = "status";

    List<String> apply(Connection connection, long goalId, JsonNode body) throws SQLException {
        return applyUpdate(connection, goalId, patchValues(body));
    }

    private List<String> applyUpdate(Connection connection, long goalId, List<PatchValue> values) throws SQLException {
        List<String> changed = new ArrayList<>();
        if (values.isEmpty()) {
            return changed;
        }
        StringJoiner assignments = new StringJoiner(", ");
        for (PatchValue value : values) {
            assignments.add(value.column() + " = ?");
            changed.add(value.column());
        }
        assignments.add("updated_at = CURRENT_TIMESTAMP");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE goals SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (PatchValue value : values) {
                statement.setObject(index++, value.value());
            }
            statement.setLong(index, goalId);
            statement.executeUpdate();
        }
        return changed;
    }

    private List<PatchValue> patchValues(JsonNode body) {
        List<PatchValue> values = new ArrayList<>();
        PlanningPatchValues.addText(values, body, "title");
        PlanningPatchValues.addBigDecimal(values, body, "target_amount");
        PlanningPatchValues.addDate(values, body, "target_date");
        PlanningPatchValues.addText(values, body, "kind");
        PlanningPatchValues.addText(values, body, STATUS);
        if (body.has(LINKED_ACCOUNT_IDS)) {
            values.add(new PatchValue(LINKED_ACCOUNT_IDS, PlanningJson.longListJson(body.get(LINKED_ACCOUNT_IDS))));
        }
        PlanningPatchValues.addText(values, body, "notes_markdown");
        PlanningPatchValues.addInteger(values, body, "sort_order");
        PlanningPatchValues.addBoolean(values, body, ARCHIVED);
        return values;
    }
}
