package com.deskbooks.backend.accounts;

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
class AccountControllerTest {
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
    void createsListsUpdatesAndDeletesAccounts() throws Exception {
        mvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Checking",
                                  "account_category": "bank",
                                  "type": "checking"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.currency", equalTo("USD")))
                .andExpect(jsonPath("$.sign_convention", equalTo("outflow_negative")))
                .andExpect(jsonPath("$.is_closed", equalTo(false)));

        mvc.perform(patch("/api/accounts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institution\":\"Local Bank\",\"sort_order\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution", equalTo("Local Bank")))
                .andExpect(jsonPath("$.sort_order", equalTo(5)));

        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", equalTo("Checking")));

        mvc.perform(delete("/api/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")));
    }

    @Test
    void accountListCanExcludeClosedAccounts() throws Exception {
        mvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Closed Card",
                                  "account_category": "credit",
                                  "type": "credit_card",
                                  "is_closed": true
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/accounts?include_closed=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
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
