package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final PlanningEndpointRunner endpoint;
    private final GoalStore goals = new GoalStore();
    private final GoalProgressCalculator progressCalculator = new GoalProgressCalculator(goals);

    GoalController(SqliteConnectionProvider connections) {
        endpoint = new PlanningEndpointRunner(connections);
    }

    @GetMapping("")
    List<GoalResponse> listGoals(@RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived) {
        return endpoint.run(connection -> goals.list(connection, includeArchived));
    }

    @PostMapping("")
    GoalResponse createGoal(@Valid @RequestBody GoalRequest body) {
        return endpoint.run(connection -> goals.create(connection, body));
    }

    @GetMapping("/{goalId}")
    GoalResponse getGoal(@PathVariable long goalId) {
        return endpoint.run(connection -> goals.get(connection, goalId));
    }

    @PatchMapping("/{goalId}")
    GoalResponse updateGoal(@PathVariable long goalId, @RequestBody JsonNode body) {
        return endpoint.run(connection -> goals.update(connection, goalId, body));
    }

    @DeleteMapping("/{goalId}")
    Map<String, String> archiveGoal(@PathVariable long goalId) {
        return endpoint.run(connection -> goals.archive(connection, goalId));
    }

    @GetMapping("/{goalId}/revisions")
    List<GoalRevisionResponse> revisions(@PathVariable long goalId) {
        return endpoint.run(connection -> goals.revisions(connection, goalId));
    }

    @GetMapping("/{goalId}/progress")
    GoalProgressResponse progress(@PathVariable long goalId) {
        return endpoint.run(connection -> progressCalculator.progress(connection, goalId));
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

    record GoalProgressResponse(BigDecimal current, BigDecimal target, Double percent, LocalDate asOf) {
    }
}
