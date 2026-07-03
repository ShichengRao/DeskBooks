package com.deskbooks.backend.profiles;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class ProfileControllerTest {
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
    void listProfilesCreatesDefaultRegistry() throws Exception {
        mvc.perform(get("/api/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_slug", equalTo("personal")))
                .andExpect(jsonPath("$.profiles", hasSize(1)))
                .andExpect(jsonPath("$.profiles[0].slug", equalTo("personal")))
                .andExpect(jsonPath("$.profiles[0].name", equalTo("Personal")))
                .andExpect(jsonPath("$.profiles[0].db_file", equalTo("app.db")))
                .andExpect(jsonPath("$.profiles[0].is_active", equalTo(true)));
    }

    @Test
    void createProfileAddsAndActivatesSeparateSqliteFilePath() throws Exception {
        mvc.perform(post("/api/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Household\",\"seed_starter_data\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_slug", equalTo("household")))
                .andExpect(jsonPath("$.profiles", hasSize(2)))
                .andExpect(jsonPath("$.profiles[1].slug", equalTo("household")))
                .andExpect(jsonPath("$.profiles[1].db_file", equalTo("profiles/household.db")))
                .andExpect(jsonPath("$.profiles[1].is_active", equalTo(true)));

        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void createProfileSeedsStarterDataByDefault() throws Exception {
        mvc.perform(post("/api/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Starter\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_slug", equalTo("starter")));

        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].name", hasItems("Checking", "Savings", "Credit Card")));

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems(
                        "Housing",
                        "Rent",
                        "Utilities",
                        "Food",
                        "Groceries",
                        "Restaurants",
                        "Income",
                        "Paycheck",
                        "Other Income",
                        "Credit Card Payment")));

        mvc.perform(get("/api/journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", equalTo("Welcome")));
    }

    @Test
    void duplicateProfileCopiesSelectedSourceFileAndActivatesCopy() throws Exception {
        Files.createDirectories(dataDir.resolve("profiles"));
        Files.writeString(dataDir.resolve("profiles/demo.db"), "demo-source");
        Files.writeString(dataDir.resolve("profiles.json"), """
                {
                  "active": "personal",
                  "profiles": [
                    {"slug": "personal", "name": "Personal", "db_file": "app.db"},
                    {"slug": "demo", "name": "Demo", "db_file": "profiles/demo.db"}
                  ]
                }
                """);

        mvc.perform(post("/api/profiles/duplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Copied Demo\",\"source_slug\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_slug", equalTo("copied-demo")))
                .andExpect(jsonPath("$.profiles[2].slug", equalTo("copied-demo")))
                .andExpect(jsonPath("$.profiles[2].db_file", equalTo("profiles/copied-demo.db")));

        String copied = Files.readString(dataDir.resolve("profiles/copied-demo.db"));
        org.assertj.core.api.Assertions.assertThat(copied).isEqualTo("demo-source");
    }

    @Test
    void activatingMissingProfileReturnsFastApiStyleDetail() throws Exception {
        mvc.perform(post("/api/profiles/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"missing\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", equalTo("profile not found")));
    }

    @Test
    void deletingLastProfileReturnsBadRequestDetail() throws Exception {
        mvc.perform(delete("/api/profiles/personal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("cannot delete the only profile")));
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
