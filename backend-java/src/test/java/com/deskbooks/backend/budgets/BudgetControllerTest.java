package com.deskbooks.backend.budgets;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class BudgetControllerTest {
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
    void budgetReportAppliesDefaultsOverridesRollupsSplitsAndDeletes() throws Exception {
        seedBudgetReportCase();

        mvc.perform(get("/api/budgets?start=2026-06-24&end=2026-07-20&focus_month=2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start", equalTo("2026-06-01")))
                .andExpect(jsonPath("$.end", equalTo("2026-07-01")))
                .andExpect(jsonPath("$.focus_month", equalTo("2026-07-01")))
                .andExpect(jsonPath("$.months", hasSize(2)))
                .andExpect(jsonPath("$.planned_total", equalTo("590.00")))
                .andExpect(jsonPath("$.actual_total", equalTo("450.00")))
                .andExpect(jsonPath("$.delta_total", equalTo("140.00")))
                .andExpect(jsonPath("$.months[0].month", equalTo("2026-06-01")))
                .andExpect(jsonPath("$.months[0].planned_total", equalTo("260.00")))
                .andExpect(jsonPath("$.months[0].actual_total", equalTo("250.00")))
                .andExpect(jsonPath("$.months[1].month", equalTo("2026-07-01")))
                .andExpect(jsonPath("$.months[1].planned_total", equalTo("330.00")))
                .andExpect(jsonPath("$.months[1].actual_total", equalTo("200.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Food')].target_amount", hasItem("80.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Food')].actual_amount", hasItem("0.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Housing')].target_amount", hasItem("250.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Housing')].actual_amount", hasItem("200.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Rent')].override_amount", hasItem("250.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Groceries')].target_amount", hasItem(nullValue())));

        mvc.perform(get("/api/budgets?start=2026-06-24&end=2026-07-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focus_month").doesNotExist())
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Food')].target_amount", hasItem("160.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Food')].actual_amount", hasItem("50.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Housing')].target_amount", hasItem("430.00")))
                .andExpect(jsonPath("$.rows[?(@.category_name == 'Housing')].actual_amount", hasItem("400.00")));

        mvc.perform(put("/api/budgets/defaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_id": 1,
                                  "amount": "90.00",
                                  "notes": "updated"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.amount", equalTo("90.00")))
                .andExpect(jsonPath("$.notes", equalTo("updated")));

        mvc.perform(delete("/api/budgets/defaults/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok", equalTo(true)));

        mvc.perform(delete("/api/budgets/overrides/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok", equalTo(true)));
    }

    @Test
    void budgetEndpointsValidateInputs() throws Exception {
        createCategory("Salary", "income", null);

        mvc.perform(get("/api/budgets"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("provide start/end or month")));

        mvc.perform(get("/api/budgets?start=2026-07-01&end=2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("end must be on or after start")));

        mvc.perform(put("/api/budgets/defaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_id": 1,
                                  "amount": "100.00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("budgets can only target expense categories")));

        mvc.perform(put("/api/budgets/defaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_id": 999,
                                  "amount": "100.00"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", equalTo("category not found")));

        mvc.perform(put("/api/budgets/defaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_id": 1,
                                  "amount": "-1.00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("budget amount must be zero or greater")));

        mvc.perform(delete("/api/budgets/defaults/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", equalTo("budget default not found")));
    }

    private void seedBudgetReportCase() throws Exception {
        createAccount("Checking", "bank", "checking");
        createCategory("Food", "expense", null);
        createCategory("Groceries", "expense", 1);
        createCategory("Housing", "expense", null);
        createCategory("Rent", "expense", 3);

        createTransaction(1, "2026-06-03", "GROCERIES", "-100.00", 2, false);
        mvc.perform(put("/api/transactions/1/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "group_name": "Household",
                                  "personal_share": "0.5000"
                                }
                                """))
                .andExpect(status().isOk());
        createTransaction(1, "2026-06-05", "RENT", "-200.00", 4, false);
        createTransaction(1, "2026-06-06", "RENT", "-999.00", 4, true);
        createTransaction(1, "2026-07-05", "RENT", "-200.00", 4, false);

        putDefault(1, "80.00");
        putDefault(3, "999.00");
        putDefault(4, "180.00");
        mvc.perform(put("/api/budgets/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "month": "2026-07-20",
                                  "category_id": 4,
                                  "amount": "250.00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month", equalTo("2026-07-01")));
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

    private void createCategory(String name, String kind, Integer parentId) throws Exception {
        String parent = parentId == null ? "null" : parentId.toString();
        mvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "kind": "%s",
                                  "parent_id": %s
                                }
                                """.formatted(name, kind, parent)))
                .andExpect(status().isOk());
    }

    private void createTransaction(
            long accountId,
            String date,
            String description,
            String amount,
            long categoryId,
            boolean excluded) throws Exception {
        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": %d,
                                  "date": "%s",
                                  "description_raw": "%s",
                                  "amount": "%s",
                                  "category_id": %d,
                                  "is_excluded_from_totals": %s
                                }
                                """.formatted(accountId, date, description, amount, categoryId, excluded)))
                .andExpect(status().isOk());
    }

    private void putDefault(long categoryId, String amount) throws Exception {
        mvc.perform(put("/api/budgets/defaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_id": %d,
                                  "amount": "%s"
                                }
                                """.formatted(categoryId, amount)))
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
