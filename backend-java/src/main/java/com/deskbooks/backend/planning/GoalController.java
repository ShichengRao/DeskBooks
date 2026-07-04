package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    private final GoalStore goals = new GoalStore();
    private final GoalProgressCalculator progressCalculator = new GoalProgressCalculator(goals);

    GoalController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<GoalResponse> listGoals(@RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived) {
        try (Connection connection = connections.open()) {
            return goals.list(connection, includeArchived);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    GoalResponse createGoal(@Valid @RequestBody GoalRequest body) {
        try (Connection connection = connections.open()) {
            return goals.create(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{goalId}")
    GoalResponse getGoal(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            return goals.get(connection, goalId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{goalId}")
    GoalResponse updateGoal(@PathVariable long goalId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return goals.update(connection, goalId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{goalId}")
    Map<String, String> archiveGoal(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            return goals.archive(connection, goalId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{goalId}/revisions")
    List<GoalRevisionResponse> revisions(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            return goals.revisions(connection, goalId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{goalId}/progress")
    GoalProgressResponse progress(@PathVariable long goalId) {
        try (Connection connection = connections.open()) {
            return progressCalculator.progress(connection, goalId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
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
