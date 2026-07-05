package com.deskbooks.backend.transactions;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.stream.Stream;

import com.deskbooks.backend.DeskBooksApplication;
import com.deskbooks.backend.db.SqliteConnectionProvider;
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
class TransactionControllerTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void profileProperties(DynamicPropertyRegistry registry) {
        registry.add("deskbooks.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SqliteConnectionProvider connections;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        cleanDataDir();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void manualTransactionCrudFiltersSplitsAndPairingWork() throws Exception {
        createAccount("Checking", "bank", "checking");
        createAccount("Card", "credit", "credit_card");
        createCategory("Food", "expense");
        createCategory("Paycheck", "income");

        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 1,
                                  "date": "2026-07-01",
                                  "description_raw": "Coffee   Shop",
                                  "amount": "-12.34",
                                  "category_id": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.description_normalized", equalTo("Coffee Shop")))
                .andExpect(jsonPath("$.kind", equalTo("expense")))
                .andExpect(jsonPath("$.is_user_categorized", equalTo(true)))
                .andExpect(jsonPath("$.is_excluded_from_totals", equalTo(false)))
                .andExpect(jsonPath("$.tags", hasSize(0)));

        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 2,
                                  "date": "2026-07-02",
                                  "description_raw": "Card payment",
                                  "amount": "50.00",
                                  "kind": "cc_payment",
                                  "is_excluded_from_totals": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(2)))
                .andExpect(jsonPath("$.kind", equalTo("cc_payment")));

        createTag("Shared", "#35a06b");

        mvc.perform(patch("/api/transactions/bulk/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ids": [1],
                                  "add_tag_ids": [1]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", equalTo(1)));

        mvc.perform(get("/api/transactions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(1)))
                .andExpect(jsonPath("$.tags[0].name", equalTo("Shared")))
                .andExpect(jsonPath("$.tags[0].color", equalTo("#35a06b")));

        mvc.perform(patch("/api/transactions/bulk/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ids": [1],
                                  "remove_tag_ids": [1]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", equalTo(1)));

        mvc.perform(get("/api/transactions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(0)));

        mvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", equalTo(2)))
                .andExpect(jsonPath("$[1].id", equalTo(1)));

        mvc.perform(get("/api/transactions/count?account_category=bank&q=coffee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", equalTo(1)));

        mvc.perform(get("/api/transactions/count?exclude_excluded=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", equalTo(1)));

        mvc.perform(patch("/api/transactions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchant": "Cafe",
                                  "amount": "-15.00",
                                  "category_id": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant", equalTo("Cafe")))
                .andExpect(jsonPath("$.amount", equalTo("-15.00")))
                .andExpect(jsonPath("$.category_id", equalTo(2)))
                .andExpect(jsonPath("$.kind", equalTo("income")));

        mvc.perform(put("/api/transactions/1/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "group_name": "roommate",
                                  "personal_share": "1.25",
                                  "notes": "cap at all mine"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.split.group_name", equalTo("roommate")))
                .andExpect(jsonPath("$.split.personal_share", equalTo("1.0000")));

        mvc.perform(patch("/api/transactions/bulk/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ids": [1, 2],
                                  "kind": "transfer",
                                  "is_excluded_from_totals": false,
                                  "clear_split": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", equalTo(2)));

        mvc.perform(get("/api/transactions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind", equalTo("transfer")))
                .andExpect(jsonPath("$.split").doesNotExist());

        mvc.perform(post("/api/transactions/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transaction_a_id": 1,
                                  "transaction_b_id": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("paired")));

        mvc.perform(post("/api/transactions/1/unpair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("unpaired")));

        mvc.perform(delete("/api/transactions/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")));

        mvc.perform(get("/api/transactions/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", equalTo(1)));
    }

    @Test
    void missingAccountAndCategoryReturnNotFound() throws Exception {
        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 999,
                                  "date": "2026-07-01",
                                  "description_raw": "Coffee",
                                  "amount": "-12.34"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", equalTo("account not found")));

        createAccount("Checking", "bank", "checking");
        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_id": 1,
                                  "date": "2026-07-01",
                                  "description_raw": "Coffee",
                                  "amount": "-12.34",
                                  "category_id": 999
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", equalTo("category not found")));
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

    private void createTag(String name, String color) throws Exception {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tags (name, color) VALUES (?, ?)
                        """)) {
            statement.setString(1, name);
            statement.setString(2, color);
            statement.executeUpdate();
        }
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
