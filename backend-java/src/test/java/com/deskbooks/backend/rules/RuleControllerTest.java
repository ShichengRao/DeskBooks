package com.deskbooks.backend.rules;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.deskbooks.backend.DeskBooksApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = DeskBooksApplication.class)
class RuleControllerTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void profileProperties(DynamicPropertyRegistry registry) {
        registry.add("deskbooks.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        cleanDataDir();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void ruleCrudCoverageReapplyAndDeleteWork() throws Exception {
        createAccount("Checking", "bank", "checking");
        createCategory("Food", "expense");

        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Coffee",
                                  "priority": 10,
                                  "match_description_pattern": "Coffee",
                                  "set_category_id": 1,
                                  "set_kind": "expense",
                                  "set_merchant": "Cafe"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.is_active", equalTo(true)))
                .andExpect(jsonPath("$.apply_count", equalTo(0)));

        mvc.perform(get("/api/rules/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_rule_count", equalTo(1)))
                .andExpect(jsonPath("$.total_transactions", equalTo(0)))
                .andExpect(jsonPath("$.coverage_percent", equalTo(0.0)));

        applyUnreviewedImportRow("Coffee Shop", "-4.50");

        mvc.perform(post("/api/rules/reapply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_changed", equalTo(1)))
                .andExpect(jsonPath("$.rules_fired", equalTo(1)));

        mvc.perform(get("/api/transactions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category_id", equalTo(1)))
                .andExpect(jsonPath("$.kind", equalTo("expense")))
                .andExpect(jsonPath("$.merchant", equalTo("Cafe")))
                .andExpect(jsonPath("$.matched_rule_id", equalTo(1)));

        mvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apply_count", equalTo(1)))
                .andExpect(jsonPath("$[0].last_applied_at", notNullValue()));

        mvc.perform(get("/api/rules/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched_transactions", equalTo(1)))
                .andExpect(jsonPath("$.labeled_correct_matches", equalTo(1)))
                .andExpect(jsonPath("$.labeled_accuracy", closeTo(1.0, 0.0001)));

        mvc.perform(patch("/api/rules/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "priority": 20,
                                  "is_active": false,
                                  "match_amount_min": "-10.00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority", equalTo(20)))
                .andExpect(jsonPath("$.is_active", equalTo(false)))
                .andExpect(jsonPath("$.match_amount_min", equalTo("-10.00")));

        mvc.perform(delete("/api/rules/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")));

        mvc.perform(get("/api/transactions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched_rule_id").doesNotExist());
    }

    @Test
    void proposalBacktestAndRejectFollowPythonContract() throws Exception {
        createAccount("Checking", "bank", "checking");
        createCategory("Food", "expense");
        createManualTransaction("2026-07-01", "METRO COFFEE 001", "-5.00");
        createManualTransaction("2026-07-02", "METRO COFFEE 002", "-6.00");
        createManualTransaction("2026-07-03", "METRO COFFEE 003", "-7.00");

        mvc.perform(get("/api/rules/proposals?min_support=3&limit=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].key", equalTo("METRO COFFEE")))
                .andExpect(jsonPath("$[0].match_description_pattern", equalTo("METRO.*COFFEE")))
                .andExpect(jsonPath("$[0].support", equalTo(3)))
                .andExpect(jsonPath("$[0].correct_matches", equalTo(3)))
                .andExpect(jsonPath("$[0].examples", hasSize(3)));

        String proposal = """
                {
                  "key": "METRO COFFEE",
                  "name": "METRO COFFEE",
                  "match_description_pattern": "METRO.*COFFEE",
                  "match_account_id": null,
                  "set_category_id": 1,
                  "set_kind": "expense",
                  "set_merchant": "METRO COFFEE"
                }
                """;
        mvc.perform(post("/api/rules/proposals/backtest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_user_labeled_matches", equalTo(3)))
                .andExpect(jsonPath("$.all_transaction_matches", equalTo(3)))
                .andExpect(jsonPath("$.accuracy", closeTo(1.0, 0.0001)));

        mvc.perform(post("/api/rules/proposals/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("rejected")))
                .andExpect(jsonPath("$.created", equalTo(true)));

        mvc.perform(get("/api/rules/proposals?min_support=3&limit=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private void createAccount(String name, String category, String type) throws Exception {
        mvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "account_category": "%s",
                                  "type": "%s"
                                }
                                """.formatted(name, category, type)))
                .andExpect(status().isOk());
    }

    private void createCategory(String name, String kind) throws Exception {
        mvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "kind": "%s"
                                }
                                """.formatted(name, kind)))
                .andExpect(status().isOk());
    }

    private void createManualTransaction(String date, String description, String amount) throws Exception {
        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 1,
                                  "date": "%s",
                                  "description_raw": "%s",
                                  "description_normalized": "%s",
                                  "merchant": "METRO COFFEE",
                                  "amount": "%s",
                                  "category_id": 1
                                }
                                """.formatted(date, description, description, amount)))
                .andExpect(status().isOk());
    }

    private void applyUnreviewedImportRow(String description, String amount) throws Exception {
        mvc.perform(post("/api/imports/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "importer_name": "test",
                                  "account_id": 1,
                                  "source_filename": "rules.csv",
                                  "skip_duplicates": true,
                                  "rows": [
                                    {
                                      "row_index": 0,
                                      "date": "2026-07-01",
                                      "post_date": null,
                                      "description_raw": "%s",
                                      "description_normalized": "%s",
                                      "merchant": "%s",
                                      "amount": "%s",
                                      "suggested_category_id": null,
                                      "suggested_kind": "uncategorized",
                                      "suggested_tags": [],
                                      "suggested_matched_rule_id": null,
                                      "is_duplicate": false,
                                      "raw": {}
                                    }
                                  ]
                                }
                                """.formatted(description, description, description, amount)))
                .andExpect(status().isOk());
    }

    private void cleanDataDir() throws IOException {
        if (!Files.exists(dataDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dataDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(dataDir))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }
}
