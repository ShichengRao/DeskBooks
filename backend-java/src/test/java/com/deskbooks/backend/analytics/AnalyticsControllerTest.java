package com.deskbooks.backend.analytics;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
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
    void monthlyBreakdownAppliesSplitsAndKindBuckets() throws Exception {
        createAccount("Checking", "bank", "checking");
        createCategory("Food", "expense");
        createCategory("Salary", "income");

        createTransaction("2026-07-02", "Shared groceries", "-100.00", "expense", false, 1L, null, null);
        setSplit(1, "Household", "0.2500");
        createTransaction("2026-07-03", "Payroll", "1000.00", "income", false, 2L, "Employer", "payroll");
        createTransaction("2026-07-04", "Charity", "-10.00", "donation", false, null, null, null);
        createTransaction("2026-07-05", "Quarterly tax", "-100.00", "tax", false, null, null, null);
        createTransaction("2026-07-06", "Mystery", "-5.00", "uncategorized", false, null, null, null);
        createTransaction("2026-07-07", "Ignored", "-999.00", "expense", true, 1L, null, null);
        createTransaction("2026-08-01", "Coffee", "-7.00", "expense", false, 1L, null, null);

        mvc.perform(get("/api/analytics/monthly?start=2026-07-01&end=2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].month", equalTo("2026-07")))
                .andExpect(jsonPath("$[0].by_kind.expense", equalTo("-25.00")))
                .andExpect(jsonPath("$[0].by_kind.income", equalTo("1000.00")))
                .andExpect(jsonPath("$[0].by_kind.donation", equalTo("-10.00")))
                .andExpect(jsonPath("$[0].by_kind.tax", equalTo("-100.00")))
                .andExpect(jsonPath("$[0].by_kind.uncategorized", equalTo("-5.00")))
                .andExpect(jsonPath("$[0].by_expense_category.Food", equalTo("25.00")))
                .andExpect(jsonPath("$[0].by_expense_category.Uncategorized", equalTo("5.00")))
                .andExpect(jsonPath("$[0].by_income_category.Salary", equalTo("1000.00")))
                .andExpect(jsonPath("$[0].expenses_total", equalTo("30.00")))
                .andExpect(jsonPath("$[0].income_total", equalTo("1000.00")))
                .andExpect(jsonPath("$[0].donations_total", equalTo("10.00")))
                .andExpect(jsonPath("$[0].taxes_total", equalTo("100.00")))
                .andExpect(jsonPath("$[0].net", equalTo("860.00")))
                .andExpect(jsonPath("$[1].month", equalTo("2026-08")))
                .andExpect(jsonPath("$[1].expenses_total", equalTo("7.00")));
    }

    @Test
    void recurringMerchantsGroupByMerchantAndEstimateCadence() throws Exception {
        createAccount("Checking", "bank", "checking");
        createCategory("Subscriptions", "expense");

        createTransaction("2026-01-01", "Gym January", "-50.00", "expense", false, 1L, "Gym Co", "gym");
        createTransaction("2026-02-01", "Gym February", "-50.00", "expense", false, 1L, "Gym Co", "gym");
        createTransaction("2026-03-02", "Gym March", "-50.00", "expense", false, 1L, "Gym Co", "gym");
        createTransaction("2026-03-15", "Coffee", "-5.00", "expense", false, 1L, "Coffee Shop", "coffee");
        createTransaction("2026-04-01", "Ignored gym", "-50.00", "expense", true, 1L, "Gym Co", "gym");

        mvc.perform(get("/api/analytics/recurring?min_occurrences=3&start=2026-01-01&end=2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].merchant", equalTo("Gym Co")))
                .andExpect(jsonPath("$[0].occurrences", equalTo(3)))
                .andExpect(jsonPath("$[0].avg_amount", equalTo("-50.00")))
                .andExpect(jsonPath("$[0].total_amount", equalTo("-150.00")))
                .andExpect(jsonPath("$[0].last_seen", equalTo("2026-03-02")))
                .andExpect(jsonPath("$[0].cadence_days_estimate", equalTo(30.0)));
    }

    @Test
    void sankeyBalancesCashflowGrowthAndAccountDeltas() throws Exception {
        createAccount("Checking", "bank", "checking");
        createAccount("Brokerage", "investment", "brokerage");
        createCategory("Salary", "income");
        createCategory("Food", "expense");
        createCategory("Groceries", "expense", 2L);

        createTransaction("2026-01-15", "Employer", "5000.00", "income", false, 1L, "Salary", "salary");
        createTransaction("2026-01-16", "Market", "-100.00", "expense", false, 3L, "Market", "market");
        createTransaction("2026-01-17", "Local Charity", "-50.00", "donation", false, null, "Local Charity", "local charity");
        createTransaction("2026-01-18", "IRS", "-500.00", "tax", false, null, "IRS", "irs");

        createSnapshot("2026-01-01", """
                [
                  {"account_id": 1, "balance": "1000.00"},
                  {"account_id": 2, "balance": "2000.00"}
                ]
                """);
        createSnapshot("2026-02-01", """
                [
                  {"account_id": 1, "balance": "1500.00"},
                  {"account_id": 2, "balance": "6500.00"}
                ]
                """);

        mvc.perform(get("/api/analytics/sankey?start=2026-01-01&end=2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year", equalTo(2026)))
                .andExpect(jsonPath("$.label", equalTo("2026-01-01 to 2026-01-31")))
                .andExpect(jsonPath("$.nodes[*].name", hasItems(
                        "Inflows",
                        "Income",
                        "Salary",
                        "Growth",
                        "Stock Growth",
                        "Bank Interest",
                        "Expenses",
                        "Groceries",
                        "Donations",
                        "Taxes",
                        "Account deltas (pos)",
                        "Stock Account",
                        "CDs + Bank Accounts")))
                .andExpect(jsonPath("$.links[?(@.label == 'Income')].value", hasItem(5000.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Expenses')].value", hasItem(100.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Donations')].value", hasItem(50.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Taxes')].value", hasItem(500.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Growth')].value", hasItem(650.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Stock Growth')].value", hasItem(585.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Bank Interest')].value", hasItem(65.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Account deltas')].value", hasItem(5000.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'Stock Account')].value", hasItem(4500.0)))
                .andExpect(jsonPath("$.links[?(@.label == 'CDs + Bank Accounts')].value", hasItem(500.0)))
                .andExpect(jsonPath("$.notes", hasItem("Snapshot window used: 2026-01-01 → 2026-02-01.")));
    }

    @Test
    void sankeyValidationMatchesPythonRouter() throws Exception {
        mvc.perform(get("/api/analytics/sankey"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("provide either year or start/end")));

        mvc.perform(get("/api/analytics/sankey?start=2026-07-02&end=2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("end must be on or after start")));
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
        createCategory(name, kind, null);
    }

    private void createCategory(String name, String kind, Long parentId) throws Exception {
        String parentField = parentId == null ? "" : """
                                ,
                                  "parent_id": %d
                                """.formatted(parentId);
        mvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "kind": "%s"%s
                                }
                                """.formatted(name, kind, parentField)))
                .andExpect(status().isOk());
    }

    private void createTransaction(
            String date,
            String description,
            String amount,
            String kind,
            boolean excluded) throws Exception {
        createTransaction(date, description, amount, kind, excluded, 1L, null, null);
    }

    private void createTransaction(
            String date,
            String description,
            String amount,
            String kind,
            boolean excluded,
            Long categoryId,
            String merchant,
            String descriptionNormalized) throws Exception {
        String merchantField = merchant == null ? "" : """
                                ,
                                  "merchant": "%s"
                                """.formatted(merchant);
        String normalizedField = descriptionNormalized == null ? "" : """
                                ,
                                  "description_normalized": "%s"
                                """.formatted(descriptionNormalized);
        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 1,
                                  "date": "%s",
                                  "description_raw": "%s",
                                  "amount": "%s",
                                  "category_id": %s,
                                  "kind": "%s",
                                  "is_excluded_from_totals": %s%s%s
                                }
                                """.formatted(
                                        date,
                                        description,
                                        amount,
                                        categoryId == null ? "null" : categoryId.toString(),
                                        kind,
                                        excluded,
                                        merchantField,
                                        normalizedField)))
                .andExpect(status().isOk());
    }

    private void createSnapshot(String snapshotDate, String balancesJson) throws Exception {
        mvc.perform(post("/api/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "snapshot_date": "%s",
                                  "balances": %s
                                }
                                """.formatted(snapshotDate, balancesJson)))
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
