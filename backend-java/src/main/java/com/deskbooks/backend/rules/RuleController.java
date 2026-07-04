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
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/rules")
class RuleController {
    private final SqliteConnectionProvider connections;
    private final RuleReader reader;
    private final RuleMutations mutations;

    RuleController(SqliteConnectionProvider connections) {
        this.connections = connections;
        this.reader = new RuleReader();
        this.mutations = new RuleMutations(reader);
    }

    @GetMapping("")
    List<RuleRecord> listRules() {
        try (Connection connection = connections.open()) {
            return reader.list(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    RuleRecord createRule(@Valid @RequestBody RuleRequest body) {
        try (Connection connection = connections.open()) {
            return mutations.create(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{ruleId}")
    RuleRecord updateRule(@PathVariable long ruleId, @RequestBody JsonNode body) {
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
