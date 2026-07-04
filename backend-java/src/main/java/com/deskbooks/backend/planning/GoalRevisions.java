package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

final class GoalRevisions {
    private static final String LINKED_ACCOUNT_IDS = "linked_account_ids";

    List<GoalController.GoalRevisionResponse> list(Connection connection, long goalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM goal_revisions WHERE goal_id = ? ORDER BY changed_at DESC, id DESC
                """)) {
            statement.setLong(1, goalId);
            try (ResultSet rs = statement.executeQuery()) {
                List<GoalController.GoalRevisionResponse> revisions = new ArrayList<>();
                while (rs.next()) {
                    revisions.add(revisionFrom(rs));
                }
                return revisions;
            }
        }
    }

    void insert(Connection connection, GoalController.GoalResponse goal, String summary) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO goal_revisions (goal_id, snapshot, change_summary) VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, goal.id());
            statement.setString(2, PlanningSql.jsonString(snapshot(goal)));
            statement.setString(3, summary);
            statement.executeUpdate();
        }
    }

    String updateSummary(JsonNode body, List<String> changed) {
        String changeSummary = PlanningSql.textOrNull(body, "change_summary");
        return changeSummary == null || changeSummary.isBlank()
                ? "updated: " + String.join(", ", changed)
                : changeSummary;
    }

    private GoalController.GoalRevisionResponse revisionFrom(ResultSet rs) throws SQLException {
        return new GoalController.GoalRevisionResponse(
                rs.getLong("id"),
                rs.getLong("goal_id"),
                PlanningSql.jsonObject(rs.getString("snapshot")),
                PlanningSql.localDateTime(rs, "changed_at"),
                rs.getString("change_summary"));
    }

    private Map<String, Object> snapshot(GoalController.GoalResponse goal) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", goal.title());
        snapshot.put("target_amount", goal.targetAmount());
        snapshot.put("target_date", goal.targetDate() == null ? null : goal.targetDate().toString());
        snapshot.put("kind", goal.kind());
        snapshot.put("status", goal.status());
        snapshot.put(LINKED_ACCOUNT_IDS, goal.linkedAccountIds() == null ? List.of() : goal.linkedAccountIds());
        snapshot.put("notes_markdown", goal.notesMarkdown());
        snapshot.put("sort_order", goal.sortOrder());
        snapshot.put("archived", goal.archived());
        return snapshot;
    }
}
