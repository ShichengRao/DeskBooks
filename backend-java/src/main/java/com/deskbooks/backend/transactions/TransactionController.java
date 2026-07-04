package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/transactions")
class TransactionController {
    private final SqliteConnectionProvider connections;
    private final TransactionReader reader = new TransactionReader();
    private final TransactionQueries queries = new TransactionQueries(reader);
    private final TransactionRelations relations = new TransactionRelations();
    private final TransactionMutations mutations = new TransactionMutations(reader, relations);

    TransactionController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<TransactionResponse> listTransactions(@ModelAttribute TransactionQueryRequest query) {
        query.validatePage();
        try (Connection connection = connections.open()) {
            return queries.list(connection, query);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/count")
    Map<String, Long> countTransactions(@ModelAttribute TransactionQueryRequest query) {
        try (Connection connection = connections.open()) {
            return queries.count(connection, query);
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
}
