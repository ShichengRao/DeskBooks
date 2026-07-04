package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/transactions")
class TransactionController {
    private final SqliteConnectionProvider connections;
    private final TransactionReader reader = new TransactionReader();
    private final TransactionRelations relations = new TransactionRelations();
    private final TransactionMutations mutations = new TransactionMutations(reader, relations);

    TransactionController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<TransactionResponse> listTransactions(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "account_category", required = false) List<String> accountCategory,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(name = "kind", required = false) List<String> kind,
            @RequestParam(name = "amount_min", required = false) BigDecimal amountMin,
            @RequestParam(name = "amount_max", required = false) BigDecimal amountMax,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "exclude_excluded", defaultValue = "false") boolean excludeExcluded,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (limit < 1 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "limit must be >= 1 and offset must be >= 0");
        }

        try (Connection connection = connections.open()) {
            TransactionFilters.FilterSql filters = TransactionFilters.build(
                    start, end, accountId, accountCategory, categoryId, kind, amountMin, amountMax, q, excludeExcluded);
            String sql = "SELECT " + reader.selectColumns()
                    + " FROM transactions t"
                    + (filters.joinAccounts() ? " JOIN accounts a ON a.id = t.account_id" : "")
                    + filters.whereSql()
                    + " ORDER BY t.date DESC, t.id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = TransactionSql.bindParams(statement, filters.params(), 1);
                statement.setInt(index++, limit);
                statement.setInt(index, offset);
                try (ResultSet rs = statement.executeQuery()) {
                    List<TransactionResponse> transactions = new ArrayList<>();
                    while (rs.next()) {
                        transactions.add(reader.from(connection, rs));
                    }
                    return transactions;
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/count")
    Map<String, Long> countTransactions(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "account_category", required = false) List<String> accountCategory,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(name = "kind", required = false) List<String> kind,
            @RequestParam(name = "amount_min", required = false) BigDecimal amountMin,
            @RequestParam(name = "amount_max", required = false) BigDecimal amountMax,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "exclude_excluded", defaultValue = "false") boolean excludeExcluded) {
        try (Connection connection = connections.open()) {
            TransactionFilters.FilterSql filters = TransactionFilters.build(
                    start, end, accountId, accountCategory, categoryId, kind, amountMin, amountMax, q, excludeExcluded);
            String sql = "SELECT COUNT(t.id) AS count FROM transactions t"
                    + (filters.joinAccounts() ? " JOIN accounts a ON a.id = t.account_id" : "")
                    + filters.whereSql();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                TransactionSql.bindParams(statement, filters.params(), 1);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return Map.of("count", rs.getLong("count"));
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    TransactionResponse createTransaction(@RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return mutations.create(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{transactionId}")
    TransactionResponse getTransaction(@PathVariable long transactionId) {
        try (Connection connection = connections.open()) {
            return reader.get(connection, transactionId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/{transactionId}/split")
    TransactionResponse setTransactionSplit(@PathVariable long transactionId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return mutations.setSplit(connection, transactionId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/bulk/update")
    Map<String, Integer> bulkUpdate(@RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return mutations.bulkUpdate(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{transactionId}")
    TransactionResponse updateTransaction(@PathVariable long transactionId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return mutations.update(connection, transactionId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/pair")
    Map<String, String> pairTransactions(@RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return mutations.pair(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/{transactionId}/unpair")
    Map<String, String> unpairTransaction(@PathVariable long transactionId) {
        try (Connection connection = connections.open()) {
            return mutations.unpair(connection, transactionId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{transactionId}")
    Map<String, String> deleteTransaction(@PathVariable long transactionId) {
        try (Connection connection = connections.open()) {
            return mutations.delete(connection, transactionId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record TransactionResponse(
            long id,
            long accountId,
            LocalDate date,
            LocalDate postDate,
            String descriptionRaw,
            String descriptionNormalized,
            String merchant,
            String amount,
            Long categoryId,
            String kind,
            boolean isUserCategorized,
            boolean isExcludedFromTotals,
            String notes,
            Long transferPairId,
            Long importBatchId,
            Long matchedRuleId,
            List<TagResponse> tags,
            TransactionSplitResponse split) {
    }

    record TagResponse(long id, String name, String color) {
    }

    record TransactionSplitResponse(long transactionId, String groupName, String personalShare, String notes) {
    }

}
