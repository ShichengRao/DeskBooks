package com.deskbooks.backend.planning;

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
class PlanningControllerTest {
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
    void goalsCrudCreatesRevisionsAndArchives() throws Exception {
        mvc.perform(post("/api/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Emergency fund",
                                  "target_amount": "12000.00",
                                  "target_date": "2027-01-01",
                                  "kind": "savings",
                                  "linked_account_ids": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.title", equalTo("Emergency fund")));

        mvc.perform(patch("/api/goals/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"paused\",\"change_summary\":\"waiting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("paused")));

        mvc.perform(get("/api/goals/1/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].change_summary", equalTo("waiting")));

        mvc.perform(delete("/api/goals/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("archived")));

        mvc.perform(get("/api/goals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void journalCrudCreatesRevisionsAndImportsTextPages() throws Exception {
        mvc.perform(post("/api/journal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "entry_date": "2026-07-03",
                                  "title": "Plan",
                                  "body_markdown": "First note"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.title", equalTo("Plan")));

        mvc.perform(patch("/api/journal/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body_markdown\":\"Updated note\",\"change_summary\":\"expanded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body_markdown", equalTo("Updated note")));

        mvc.perform(get("/api/journal/1/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].change_summary", equalTo("expanded")));

        Path importFile = dataDir.resolve("planning-notes.md");
        Files.writeString(importFile, "First page\n\fSecond page");
        mvc.perform(post("/api/journal/import-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + importFile + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_filename", equalTo("planning-notes.md")))
                .andExpect(jsonPath("$.drafts", hasSize(2)))
                .andExpect(jsonPath("$.drafts[1].body_markdown", equalTo("Second page")));
    }

    @Test
    void fireSettingsAndProjectionLoadForPlanningPage() throws Exception {
        mvc.perform(get("/api/analytics/fire/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annual_retirement_spending", equalTo("75000.00")))
                .andExpect(jsonPath("$.withdrawal_rate", equalTo("0.0400")));

        mvc.perform(put("/api/analytics/fire/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "growth_bank": "0.0100",
                                  "growth_investment": "0.0500",
                                  "growth_tax_advantaged": "0.0500",
                                  "growth_nonsense": "0.0000",
                                  "growth_cash": "0.0000",
                                  "growth_credit": "0.0000",
                                  "annual_retirement_spending": "80000.00",
                                  "withdrawal_rate": "0.0400"
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annual_retirement_spending", equalTo("80000.00")));

        mvc.perform(get("/api/analytics/fire/projection?max_years=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target_total", equalTo("2000000.00")))
                .andExpect(jsonPath("$.years", hasSize(3)));
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
