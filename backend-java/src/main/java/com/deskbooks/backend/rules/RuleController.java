package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/rules")
class RuleController {
    private final SqliteConnectionProvider connections;
    private final RuleEngine ruleEngine;
    private final RuleReader reader;
    private final RuleMutations mutations;

    RuleController(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.ruleEngine = ruleEngine;
        this.reader = new RuleReader(ruleEngine);
        this.mutations = new RuleMutations(reader, ruleEngine);
    }

    @GetMapping("")
    List<RuleEngine.RuleRecord> listRules() {
        try (Connection connection = connections.open()) {
            return reader.list(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/proposals")
    List<RuleEngine.RuleProposal> listRuleProposals(
            @RequestParam(name = "min_support", defaultValue = "3") int minSupport,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        try (Connection connection = connections.open()) {
            return ruleEngine.generateRuleProposals(connection, Math.max(1, minSupport), Math.max(0, limit));
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/coverage")
    RuleEngine.RuleCoverage coverage() {
        try (Connection connection = connections.open()) {
            return ruleEngine.coverageSummary(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/proposals/backtest")
    RuleEngine.RuleProposal backtestRuleProposal(@Valid @RequestBody RuleEngine.RuleProposalRequest body) {
        try (Connection connection = connections.open()) {
            return ruleEngine.backtestRuleProposal(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/proposals/reject")
    Map<String, Object> rejectRuleProposal(@Valid @RequestBody RuleEngine.RuleProposalRequest body) {
        try (Connection connection = connections.open()) {
            boolean created = ruleEngine.rejectRuleProposal(connection, body);
            return Map.of("status", "rejected", "created", created);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    RuleEngine.RuleRecord createRule(@Valid @RequestBody RuleRequest body) {
        try (Connection connection = connections.open()) {
            return mutations.create(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{ruleId}")
    RuleEngine.RuleRecord updateRule(@PathVariable long ruleId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return mutations.update(connection, ruleId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/bulk-delete")
    Map<String, Integer> bulkDeleteRules(@RequestBody RuleBulkDelete body) {
        try (Connection connection = connections.open()) {
            return mutations.bulkDelete(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{ruleId}")
    Map<String, String> deleteRule(@PathVariable long ruleId) {
        try (Connection connection = connections.open()) {
            return mutations.delete(connection, ruleId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/reapply")
    Map<String, Integer> reapplyRules() {
        try (Connection connection = connections.open()) {
            RuleEngine.ReapplyResult result = ruleEngine.reapplyToUnreviewed(connection);
            return Map.of("rows_changed", result.rowsChanged(), "rules_fired", result.rulesFired());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record RuleRequest(
            @NotBlank String name,
            Integer priority,
            Boolean isActive,
            Long matchAccountId,
            String matchDescriptionPattern,
            BigDecimal matchAmountMin,
            BigDecimal matchAmountMax,
            Long setCategoryId,
            String setKind,
            String setMerchant,
            List<String> setTags,
            String notes) {
    }

    record RuleBulkDelete(List<Long> ids) {
    }
}
