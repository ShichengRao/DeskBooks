package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rules/proposals")
class RuleProposalController {
    private final SqliteConnectionProvider connections;
    private final RuleEngine ruleEngine;

    RuleProposalController(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.ruleEngine = ruleEngine;
    }

    @GetMapping("")
    List<RuleProposal> listRuleProposals(
            @RequestParam(name = "min_support", defaultValue = "3") int minSupport,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        try (Connection connection = connections.open()) {
            return ruleEngine.generateRuleProposals(connection, Math.max(1, minSupport), Math.max(0, limit));
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/backtest")
    RuleProposal backtestRuleProposal(@Valid @RequestBody RuleProposalRequest body) {
        try (Connection connection = connections.open()) {
            return ruleEngine.backtestRuleProposal(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/reject")
    Map<String, Object> rejectRuleProposal(@Valid @RequestBody RuleProposalRequest body) {
        try (Connection connection = connections.open()) {
            boolean created = ruleEngine.rejectRuleProposal(connection, body);
            return Map.of("status", "rejected", "created", created);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
