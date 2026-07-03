package com.deskbooks.backend.onboarding;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import com.deskbooks.backend.DeskBooksApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
        classes = DeskBooksApplication.class,
        properties = "deskbooks.seed-starter-data=true")
class StarterDataBootstrapTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void profileProperties(DynamicPropertyRegistry registry) {
        registry.add("deskbooks.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private WebApplicationContext context;

    @Test
    void launcherBootstrapSeedsStarterDataWhenEnabled() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].name", hasItems("Checking", "Savings", "Credit Card")));

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("Housing", "Food", "Income", "Credit Card Payment")));

        mvc.perform(get("/api/journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
