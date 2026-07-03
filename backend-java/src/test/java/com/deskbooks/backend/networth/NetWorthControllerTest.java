package com.deskbooks.backend.networth;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
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
class NetWorthControllerTest {
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
    void snapshotsCrudAndSeriesMatchPythonContract() throws Exception {
        createAccount("Checking", "bank", "checking");
        createAccount("Card", "credit", "credit_card");
        createAccount("Roth", "tax_advantaged", "retirement");

        mvc.perform(post("/api/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "snapshot_date": "2026-06-30",
                                  "notes": "Quarter close",
                                  "balances": [
                                    {"account_id": 1, "balance": "1000.00"},
                                    {"account_id": 2, "balance": "250.00"},
                                    {"account_id": 3, "balance": "500.00"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.snapshot_date", equalTo("2026-06-30")))
                .andExpect(jsonPath("$.balances", hasSize(3)))
                .andExpect(jsonPath("$.balances[0].balance", equalTo("1000.00")));

        mvc.perform(get("/api/snapshots/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].total", equalTo("1250.00")))
                .andExpect(jsonPath("$[0].by_category.bank", equalTo("1000.00")))
                .andExpect(jsonPath("$[0].by_category.credit", equalTo("-250.00")))
                .andExpect(jsonPath("$[0].by_account.Card", equalTo("-250.00")))
                .andExpect(jsonPath("$[0].taxable", equalTo("750.00")))
                .andExpect(jsonPath("$[0].tax_advantaged", equalTo("500.00")));

        mvc.perform(get("/api/analytics/fire/projection?max_years=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_total", equalTo("1250.00")))
                .andExpect(jsonPath("$.current_by_category.credit", equalTo("-250.00")));

        mvc.perform(patch("/api/snapshots/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "notes": "Updated close",
                                  "balances": [
                                    {"account_id": 1, "balance": "900.50", "notes": "settled"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes", equalTo("Updated close")))
                .andExpect(jsonPath("$.balances", hasSize(1)))
                .andExpect(jsonPath("$.balances[0].balance", equalTo("900.50")))
                .andExpect(jsonPath("$.balances[0].notes", equalTo("settled")));

        mvc.perform(delete("/api/snapshots/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")));

        mvc.perform(get("/api/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void duplicateSnapshotDatesReturnConflict() throws Exception {
        createAccount("Checking", "bank", "checking");
        String body = """
                {
                  "snapshot_date": "2026-06-30",
                  "balances": [{"account_id": 1, "balance": "1000.00"}]
                }
                """;
        mvc.perform(post("/api/snapshots").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/snapshots").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", equalTo("snapshot for this date already exists")));
    }

    @Test
    void seriesRejectsInvertedDateRange() throws Exception {
        mvc.perform(get("/api/snapshots/series?start=2026-07-01&end=2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("end must be on or after start")));
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
