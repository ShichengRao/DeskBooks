package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/goals")
class GoalController {
    private final SqliteConnectionProvider connections;

    GoalController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<GoalResponse> listGoals(@RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived) {
        String sql = """
                SELECT * FROM goals
                """ + (includeArchived ? "" : " WHERE archived = 0")
                + " ORDER BY sort_order, created_at DESC";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<GoalResponse> goals = new ArrayList<>();
            while (rs.next()) {
                goals.add(goalFrom(rs));
            }
            return goals;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    GoalResponse createGoal(@Valid @RequestBody GoalRequest body) {
        try (Connection connection = connections.open()) {
            GoalResponse created;
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
                statement.setString(6, PlanningSql.longListJson(body.linkedAccountIds()));
                statement.setString(7, body.notesMarkdown());
                statement.setInt(8, body.sortOrder() == null ? 0 : body.sortOrder());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    created = getGoal(connection, keys.getLong(1));
                }
            }
            insertRevision(connection, created.id(), goalSnapshot(created), "created");
            return getGoal(connection, created.id());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{goalId}")
    GoalResponse getGoal(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            return getGoal(connection, goalId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{goalId}")
    GoalResponse updateGoal(@PathVariable long goalId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            GoalResponse before = getGoal(connection, goalId);
            List<PatchValue> values = patchValues(body);
            String changeSummary = PlanningSql.textOrNull(body, "change_summary");
            List<String> changed = new ArrayList<>();
            if (!values.isEmpty()) {
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
            }
            GoalResponse after = getGoal(connection, goalId);
            if (!changed.isEmpty() && !after.equals(before)) {
                insertRevision(
                        connection,
                        goalId,
                        goalSnapshot(after),
                        changeSummary == null || changeSummary.isBlank()
                                ? "updated: " + String.join(", ", changed)
                                : changeSummary);
            }
            return after;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{goalId}")
    Map<String, String> archiveGoal(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            GoalResponse goal = getGoal(connection, goalId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE goals SET archived = 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                statement.setLong(1, goalId);
                statement.executeUpdate();
            }
            GoalResponse archived = new GoalResponse(
                    goal.id(), goal.title(), goal.targetAmount(), goal.targetDate(), goal.kind(), goal.status(),
                    goal.linkedAccountIds(), goal.notesMarkdown(), goal.sortOrder(), true, goal.createdAt(), LocalDateTime.now());
            insertRevision(connection, goalId, goalSnapshot(archived), "archived");
            return Map.of("status", "archived");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{goalId}/revisions")
    List<GoalRevisionResponse> revisions(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            getGoal(connection, goalId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM goal_revisions WHERE goal_id = ? ORDER BY changed_at DESC, id DESC
                    """)) {
                statement.setLong(1, goalId);
                try (ResultSet rs = statement.executeQuery()) {
                    List<GoalRevisionResponse> revisions = new ArrayList<>();
                    while (rs.next()) {
                        revisions.add(new GoalRevisionResponse(
                                rs.getLong("id"),
                                rs.getLong("goal_id"),
                                PlanningSql.jsonObject(rs.getString("snapshot")),
                                PlanningSql.localDateTime(rs, "changed_at"),
                                rs.getString("change_summary")));
                    }
                    return revisions;
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{goalId}/progress")
    GoalProgressResponse progress(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            GoalResponse goal = getGoal(connection, goalId);
            if (goal.linkedAccountIds() == null || goal.linkedAccountIds().isEmpty()) {
                return new GoalProgressResponse(null, goal.targetAmount(), null, null);
            }
            try (PreparedStatement latest = connection.prepareStatement("""
                    SELECT id, snapshot_date FROM net_worth_snapshots ORDER BY snapshot_date DESC LIMIT 1
                    """);
                    ResultSet latestRs = latest.executeQuery()) {
                if (!latestRs.next()) {
                    return new GoalProgressResponse(null, goal.targetAmount(), null, null);
                }
                long snapshotId = latestRs.getLong("id");
                BigDecimal current = BigDecimal.ZERO;
                try (PreparedStatement balances = connection.prepareStatement("""
                        SELECT balance FROM account_balances WHERE snapshot_id = ? AND account_id = ?
                        """)) {
                    for (Long accountId : goal.linkedAccountIds()) {
                        balances.setLong(1, snapshotId);
                        balances.setLong(2, accountId);
                        try (ResultSet rs = balances.executeQuery()) {
                            if (rs.next() && rs.getBigDecimal("balance") != null) {
                                current = current.add(rs.getBigDecimal("balance"));
                            }
                        }
                    }
                }
                Double percent = null;
                BigDecimal targetAmount = goal.targetAmount() == null ? null : new BigDecimal(goal.targetAmount());
                if (targetAmount != null && targetAmount.compareTo(BigDecimal.ZERO) != 0) {
                    percent = current.divide(targetAmount, 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).doubleValue();
                }
                return new GoalProgressResponse(moneyString(current), goal.targetAmount(), percent, LocalDate.parse(latestRs.getString("snapshot_date")));
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private GoalResponse getGoal(Connection connection, long goalId) throws SQLException {
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

    private GoalResponse goalFrom(ResultSet rs) throws SQLException {
        return new GoalResponse(
                rs.getLong("id"),
                rs.getString("title"),
                moneyString(rs.getBigDecimal("target_amount")),
                PlanningSql.localDate(rs, "target_date"),
                rs.getString("kind"),
                rs.getString("status"),
                PlanningSql.longList(rs.getString("linked_account_ids")),
                rs.getString("notes_markdown"),
                rs.getInt("sort_order"),
                rs.getBoolean("archived"),
                PlanningSql.localDateTime(rs, "created_at"),
                PlanningSql.localDateTime(rs, "updated_at"));
    }

    private List<PatchValue> patchValues(JsonNode body) {
        List<PatchValue> values = new ArrayList<>();
        PlanningSql.addText(values, body, "title");
        PlanningSql.addBigDecimal(values, body, "target_amount");
        PlanningSql.addDate(values, body, "target_date");
        PlanningSql.addText(values, body, "kind");
        PlanningSql.addText(values, body, "status");
        if (body.has("linked_account_ids")) {
            values.add(new PatchValue("linked_account_ids", PlanningSql.longListJson(body.get("linked_account_ids"))));
        }
        PlanningSql.addText(values, body, "notes_markdown");
        PlanningSql.addInteger(values, body, "sort_order");
        PlanningSql.addBoolean(values, body, "archived");
        return values;
    }

    private Map<String, Object> goalSnapshot(GoalResponse goal) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", goal.title());
        snapshot.put("target_amount", goal.targetAmount());
        snapshot.put("target_date", goal.targetDate() == null ? null : goal.targetDate().toString());
        snapshot.put("kind", goal.kind());
        snapshot.put("status", goal.status());
        snapshot.put("linked_account_ids", goal.linkedAccountIds() == null ? List.of() : goal.linkedAccountIds());
        snapshot.put("notes_markdown", goal.notesMarkdown());
        snapshot.put("sort_order", goal.sortOrder());
        snapshot.put("archived", goal.archived());
        return snapshot;
    }

    private void insertRevision(Connection connection, long goalId, Map<String, Object> snapshot, String summary) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO goal_revisions (goal_id, snapshot, change_summary) VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, goalId);
            statement.setString(2, PlanningSql.jsonString(snapshot));
            statement.setString(3, summary);
            statement.executeUpdate();
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    private String moneyString(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    record GoalRequest(
            @NotBlank String title,
            BigDecimal targetAmount,
            LocalDate targetDate,
            String kind,
            String status,
            List<Long> linkedAccountIds,
            String notesMarkdown,
            Integer sortOrder) {
    }

    record GoalResponse(
            long id,
            String title,
            String targetAmount,
            LocalDate targetDate,
            String kind,
            String status,
            List<Long> linkedAccountIds,
            String notesMarkdown,
            int sortOrder,
            boolean archived,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record GoalRevisionResponse(
            long id,
            long goalId,
            Map<String, Object> snapshot,
            LocalDateTime changedAt,
            String changeSummary) {
    }

    record GoalProgressResponse(String current, String target, Double percent, LocalDate asOf) {
    }
}
