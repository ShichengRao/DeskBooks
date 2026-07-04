package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;

final class JournalPatchApplier {
    String apply(Connection connection, long entryId, JsonNode body) throws SQLException {
        List<PatchValue> values = patchValues(body);
        if (!values.isEmpty()) {
            applyUpdate(connection, entryId, values);
        }
        return PlanningPatchValues.textOrNull(body, JournalRows.CHANGE_SUMMARY);
    }

    private List<PatchValue> patchValues(JsonNode body) {
        List<PatchValue> values = new ArrayList<>();
        PlanningPatchValues.addDate(values, body, JournalRows.ENTRY_DATE);
        PlanningPatchValues.addText(values, body, JournalRows.TITLE);
        PlanningPatchValues.addText(values, body, JournalRows.BODY_MARKDOWN);
        PlanningPatchValues.addLong(values, body, JournalRows.GOAL_ID);
        return values;
    }

    private void applyUpdate(Connection connection, long entryId, List<PatchValue> values) throws SQLException {
        StringJoiner assignments = new StringJoiner(", ");
        for (PatchValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        assignments.add("updated_at = CURRENT_TIMESTAMP");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE journal_entries SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (PatchValue value : values) {
                statement.setObject(index++, value.value());
            }
            statement.setLong(index, entryId);
            statement.executeUpdate();
        }
    }
}
