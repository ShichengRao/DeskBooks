package com.deskbooks.backend.transactions;

import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
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
    private final TransactionEndpointRunner endpoint;
    private final TransactionReader reader = new TransactionReader();
    private final TransactionQueries queries = new TransactionQueries(reader);
    private final TransactionRelations relations = new TransactionRelations();
    private final TransactionMutations mutations = new TransactionMutations(reader, relations);

    TransactionController(SqliteConnectionProvider connections) {
        endpoint = new TransactionEndpointRunner(connections);
    }

    @GetMapping("")
    List<TransactionResponse> listTransactions(@ModelAttribute TransactionQueryRequest query) {
        query.validatePage();
        return endpoint.run(connection -> queries.list(connection, query));
    }

    @GetMapping("/count")
    Map<String, Long> countTransactions(@ModelAttribute TransactionQueryRequest query) {
        return endpoint.run(connection -> queries.count(connection, query));
    }

    @PostMapping("")
    TransactionResponse createTransaction(@RequestBody JsonNode body) {
        return endpoint.run(connection -> mutations.create(connection, body));
    }

    @GetMapping("/{transactionId}")
    TransactionResponse getTransaction(@PathVariable long transactionId) {
        return endpoint.run(connection -> reader.get(connection, transactionId));
    }

    @PutMapping("/{transactionId}/split")
    TransactionResponse setTransactionSplit(@PathVariable long transactionId, @RequestBody JsonNode body) {
        return endpoint.run(connection -> mutations.setSplit(connection, transactionId, body));
    }

    @PatchMapping("/bulk/update")
    Map<String, Integer> bulkUpdate(@RequestBody JsonNode body) {
        return endpoint.run(connection -> mutations.bulkUpdate(connection, body));
    }

    @PatchMapping("/{transactionId}")
    TransactionResponse updateTransaction(@PathVariable long transactionId, @RequestBody JsonNode body) {
        return endpoint.run(connection -> mutations.update(connection, transactionId, body));
    }

    @PostMapping("/pair")
    Map<String, String> pairTransactions(@RequestBody JsonNode body) {
        return endpoint.run(connection -> mutations.pair(connection, body));
    }

    @PostMapping("/{transactionId}/unpair")
    Map<String, String> unpairTransaction(@PathVariable long transactionId) {
        return endpoint.run(connection -> mutations.unpair(connection, transactionId));
    }

    @DeleteMapping("/{transactionId}")
    Map<String, String> deleteTransaction(@PathVariable long transactionId) {
        return endpoint.run(connection -> mutations.delete(connection, transactionId));
    }
}
