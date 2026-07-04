package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class GoalStore {
    private static final String LINKED_ACCOUNT_IDS = "linked_account_ids";
    private static final String ARCHIVED = "archived";
    private static final String STATUS = "status";
    private final GoalRevisions revisionStore = new GoalRevisions();

    List<GoalController.GoalResponse> list(Connection connection, boolean includeArchived) throws SQLException {
        String sql = """
                SELECT * FROM goals
                """ + (includeArchived ? "" : " WHERE archived = 0")
                + " ORDER BY sort_order, created_at DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<GoalController.GoalResponse> goals = new ArrayList<>();
            while (rs.next()) {
                goals.add(goalFrom(rs));
            }
            return goals;
        }
    }

    GoalController.GoalResponse create(Connection connection, GoalController.GoalRequest body) throws SQLException {
        GoalController.GoalResponse created;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO goals (
                  title, target_amount, target_date, kind, status, linked_account_ids,
                  notes_markdown, sort_order, archived
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, body.title());
            statement.setBigDecimal(2, body.targetAmount());
            statement.setString(3, body.targetDate() == null ? null : body.targetDate().toString());
            statement.setString(4, body.kind() == null ? "savings" : body.kind());
            statement.setString(5, body.status() == null ? "active" : body.status());
            statement.setString(6, PlanningJson.longListJson(body.linkedAccountIds()));
            statement.setString(7, body.notesMarkdown());
            statement.setInt(8, body.sortOrder() == null ? 0 : body.sortOrder());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                created = get(connection, keys.getLong(1));
            }
        }
        revisionStore.insert(connection, created, "created");
        return get(connection, created.id());
    }

    GoalController.GoalResponse get(Connection connection, long goalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM goals WHERE id = ?")) {
            statement.setLong(1, goalId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "goal not found");
                }
                return goalFrom(rs);
            }
        }
    }

    GoalController.GoalResponse update(Connection connection, long goalId, JsonNode body) throws SQLException {
        GoalController.GoalResponse before = get(connection, goalId);
        List<PatchValue> values = patchValues(body);
        List<String> changed = applyUpdate(connection, goalId, values);
        GoalController.GoalResponse after = get(connection, goalId);
        if (!changed.isEmpty() && !after.equals(before)) {
            revisionStore.insert(connection, after, revisionStore.updateSummary(body, changed));
        }
        return after;
    }

    Map<String, String> archive(Connection connection, long goalId) throws SQLException {
        get(connection, goalId);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE goals SET archived = 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            statement.setLong(1, goalId);
            statement.executeUpdate();
        }
        revisionStore.insert(connection, get(connection, goalId), ARCHIVED);
        return Map.of(STATUS, ARCHIVED);
    }

    List<GoalController.GoalRevisionResponse> revisions(Connection connection, long goalId) throws SQLException {
        get(connection, goalId);
        return revisionStore.list(connection, goalId);
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

    private GoalController.GoalResponse goalFrom(ResultSet rs) throws SQLException {
        return new GoalController.GoalResponse(
                rs.getLong("id"),
                rs.getString("title"),
                GoalMoney.string(rs.getBigDecimal("target_amount")),
                PlanningRows.localDate(rs, "target_date"),
                rs.getString("kind"),
                rs.getString(STATUS),
                PlanningJson.longList(rs.getString(LINKED_ACCOUNT_IDS)),
                rs.getString("notes_markdown"),
                rs.getInt("sort_order"),
                rs.getBoolean(ARCHIVED),
                PlanningRows.localDateTime(rs, "created_at"),
                PlanningRows.localDateTime(rs, "updated_at"));
    }
}
