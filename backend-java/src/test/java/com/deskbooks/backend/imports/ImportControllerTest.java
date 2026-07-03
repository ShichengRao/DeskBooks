package com.deskbooks.backend.imports;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class ImportControllerTest {
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
    void importersListExposesOneAmexChoice() throws Exception {
        mvc.perform(get("/api/imports/importers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("amex")))
                .andExpect(jsonPath("$[?(@.name == 'amex')]", hasSize(1)));
    }

    @Test
    void previewApplyDuplicateDetectionAndRollbackWorkForChaseCreditCsv() throws Exception {
        createAccount("Card", "credit", "credit_card");
        createCategory("Groceries", "expense");
        createRule("Acme grocery", "ACME GROCERY", 1, "expense");
        Path csv = dataDir.resolve("chase.csv");
        Files.writeString(csv, String.join("\n",
                "Transaction Date,Post Date,Description,Category,Type,Amount,Memo",
                "06/01/2026,06/02/2026,ACME GROCERY,Groceries,Sale,-42.18,",
                "06/10/2026,06/10/2026,AUTOPAY PAYMENT,Payment,Payment,50.68,"));

        String previewBody = """
                {
                  "path": "%s",
                  "account_id": 1
                }
                """.formatted(jsonString(csv));

        mvc.perform(post("/api/imports/preview-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importer_name", equalTo("chase_credit")))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].amount", equalTo("-42.18")))
                .andExpect(jsonPath("$.rows[0].is_duplicate", equalTo(false)))
                .andExpect(jsonPath("$.rows[0].suggested_category_id", equalTo(1)))
                .andExpect(jsonPath("$.rows[0].suggested_kind", equalTo("expense")))
                .andExpect(jsonPath("$.rows[0].suggested_matched_rule_id", equalTo(1)))
                .andExpect(jsonPath("$.rows[1].suggested_kind", equalTo("cc_payment")));

        mvc.perform(post("/api/imports/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "importer_name": "chase_credit",
                                  "account_id": 1,
                                  "source_filename": "chase.csv",
                                  "skip_duplicates": true,
                                  "rows": [
                                    {
                                      "row_index": 0,
                                      "date": "2026-06-01",
                                      "post_date": "2026-06-02",
                                      "description_raw": "ACME GROCERY",
                                      "description_normalized": "ACME GROCERY",
                                      "merchant": "Acme Grocery",
                                      "amount": "-42.18",
                                      "suggested_category_id": 1,
                                      "suggested_kind": "expense",
                                      "suggested_tags": [],
                                      "suggested_matched_rule_id": 1,
                                      "is_duplicate": false,
                                      "raw": {"Description": "ACME GROCERY"}
                                    },
                                    {
                                      "row_index": 1,
                                      "date": "2026-06-10",
                                      "post_date": "2026-06-10",
                                      "description_raw": "AUTOPAY PAYMENT",
                                      "description_normalized": "AUTOPAY PAYMENT",
                                      "merchant": "Autopay Payment",
                                      "amount": "50.68",
                                      "suggested_category_id": null,
                                      "suggested_kind": "cc_payment",
                                      "suggested_tags": [],
                                      "suggested_matched_rule_id": null,
                                      "is_duplicate": false,
                                      "raw": {"Description": "AUTOPAY PAYMENT"}
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.row_count_total", equalTo(2)))
                .andExpect(jsonPath("$.row_count_applied", equalTo(2)))
                .andExpect(jsonPath("$.row_count_duplicate", equalTo(0)))
                .andExpect(jsonPath("$.status", equalTo("applied")));

        mvc.perform(get("/api/transactions/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", equalTo(2)));

        mvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apply_count", equalTo(1)));

        mvc.perform(post("/api/imports/preview-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].is_duplicate", equalTo(true)))
                .andExpect(jsonPath("$.rows[1].is_duplicate", equalTo(true)));

        mvc.perform(get("/api/imports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].source_filename", equalTo("chase.csv")));

        mvc.perform(post("/api/imports/1/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("rolled_back")));

        mvc.perform(get("/api/transactions/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", equalTo(0)));
    }

    @Test
    void contributionHistoryPreviewSkipsMetadataRows() throws Exception {
        createAccount("Giving", "bank", "checking");
        Path csv = dataDir.resolve("contributions.csv");
        Files.writeString(csv, String.join("\n",
                "CONTRIBUTION HISTORY",
                "",
                "Account,Example Fund",
                "",
                "Status,Description,Symbol,\"Estimated Amount\",\"Received Date\",\"Contribution ID\"",
                "Complete,\"INDEX ETF\",ETF,\"$123.45\",\"2026-05-26T00:00:00-04:00\",abc-1"));

        mvc.perform(post("/api/imports/preview-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "path": "%s",
                                  "account_id": 1,
                                  "importer_name": "contribution_history"
                                }
                                """.formatted(jsonString(csv))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importer_name", equalTo("contribution_history")))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].date", equalTo("2026-05-26")))
                .andExpect(jsonPath("$.rows[0].amount", equalTo("-123.45")))
                .andExpect(jsonPath("$.rows[0].suggested_kind", equalTo("donation")));
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

    private void createRule(String name, String pattern, long categoryId, String kind) throws Exception {
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "match_description_pattern": "%s",
                                  "set_category_id": %d,
                                  "set_kind": "%s"
                                }
                                """.formatted(name, pattern, categoryId, kind)))
                .andExpect(status().isOk());
    }

    private String jsonString(Path path) {
        return path.toString().replace("\\", "\\\\");
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
