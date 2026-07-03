package com.deskbooks.backend.analytics;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class AnalyticsControllerTest {
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
    void reconcileAndSplitSummaryMatchPythonSemantics() throws Exception {
        seedReconcileCase();

        mvc.perform(get("/api/analytics/reconcile?account_id=1&year=2026&month=7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_id", equalTo(1)))
                .andExpect(jsonPath("$.year", equalTo(2026)))
                .andExpect(jsonPath("$.month", equalTo(7)))
                .andExpect(jsonPath("$.start", equalTo("2026-07-01")))
                .andExpect(jsonPath("$.end", equalTo("2026-07-31")))
                .andExpect(jsonPath("$.transaction_count", equalTo(2)))
                .andExpect(jsonPath("$.imported_total", equalTo("-70.00")))
                .andExpect(jsonPath("$.imported_inflows", equalTo("30.00")))
                .andExpect(jsonPath("$.imported_outflows", equalTo("-100.00")))
                .andExpect(jsonPath("$.by_kind.expense", equalTo("-100.00")))
                .andExpect(jsonPath("$.by_kind.reimbursement", equalTo("30.00")))
                .andExpect(jsonPath("$.statement_total").doesNotExist())
                .andExpect(jsonPath("$.delta").doesNotExist());

        mvc.perform(put("/api/analytics/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 1,
                                  "year": 2026,
                                  "month": 7,
                                  "statement_total": "-70.25",
                                  "notes": "bank portal"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statement_total", equalTo("-70.25")))
                .andExpect(jsonPath("$.statement_notes", equalTo("bank portal")))
                .andExpect(jsonPath("$.delta", equalTo("0.25")));

        mvc.perform(get("/api/analytics/reconcile?account_id=1&start=2026-07-01&end=2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").doesNotExist())
                .andExpect(jsonPath("$.month").doesNotExist())
                .andExpect(jsonPath("$.statement_total").doesNotExist())
                .andExpect(jsonPath("$.transaction_count", equalTo(2)))
                .andExpect(jsonPath("$.imported_total", equalTo("-70.00")));

        mvc.perform(get("/api/analytics/splits?start=2026-07-01&end=2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].group_name", equalTo("Household")))
                .andExpect(jsonPath("$[0].shared_outflows", equalTo("100.00")))
                .andExpect(jsonPath("$[0].personal_outflows", equalTo("50.00")))
                .andExpect(jsonPath("$[0].expected_reimbursement", equalTo("50.00")))
                .andExpect(jsonPath("$[0].received_reimbursement", equalTo("30.00")))
                .andExpect(jsonPath("$[0].remaining_owed", equalTo("20.00")))
                .andExpect(jsonPath("$[0].transaction_count", equalTo(2)));
    }

    @Test
    void reconcileValidationMatchesPythonRouter() throws Exception {
        mvc.perform(get("/api/analytics/reconcile?account_id=1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("provide either year/month or start/end")));

        mvc.perform(get("/api/analytics/reconcile?account_id=1&start=2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("provide both start and end")));

        mvc.perform(get("/api/analytics/reconcile?account_id=1&start=2026-07-02&end=2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("end must be on or after start")));
    }

    private void seedReconcileCase() throws Exception {
        createAccount("Checking", "bank", "checking");
        createCategory("Food", "expense");
        createTransaction("2026-07-02", "Shared dinner", "-100.00", "expense", false);
        setSplit(1, "Household", "0.5000");
        createTransaction("2026-07-10", "Roommate paid", "30.00", "reimbursement", false);
        setSplit(2, "Household", "0.0000");
        createTransaction("2026-07-12", "Ignored", "-999.00", "expense", true);
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

    private void createTransaction(
            String date,
            String description,
            String amount,
            String kind,
            boolean excluded) throws Exception {
        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 1,
                                  "date": "%s",
                                  "description_raw": "%s",
                                  "amount": "%s",
                                  "category_id": 1,
                                  "kind": "%s",
                                  "is_excluded_from_totals": %s
                                }
                                """.formatted(date, description, amount, kind, excluded)))
                .andExpect(status().isOk());
    }

    private void setSplit(long transactionId, String groupName, String personalShare) throws Exception {
        mvc.perform(put("/api/transactions/%d/split".formatted(transactionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "group_name": "%s",
                                  "personal_share": "%s"
                                }
                                """.formatted(groupName, personalShare)))
                .andExpect(status().isOk());
    }

    private void cleanDataDir() throws IOException {
        if (!Files.exists(dataDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dataDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(dataDir)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
