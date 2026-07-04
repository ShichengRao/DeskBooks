package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rules")
class RuleOperationsController {
    private final SqliteConnectionProvider connections;
    private final RuleEngine ruleEngine;

    RuleOperationsController(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.ruleEngine = ruleEngine;
    }

    @GetMapping("/coverage")
    RuleCoverage coverage() {
        try (Connection connection = connections.open()) {
            return ruleEngine.coverageSummary(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/reapply")
    Map<String, Integer> reapplyRules() {
        try (Connection connection = connections.open()) {
            ReapplyResult result = ruleEngine.reapplyToUnreviewed(connection);
            return Map.of("rows_changed", result.rowsChanged(), "rules_fired", result.rulesFired());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
