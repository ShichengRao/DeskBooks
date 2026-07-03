package com.deskbooks.backend.categories;

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
class CategoryControllerTest {
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
    void createsListsUpdatesAndArchivesCategories() throws Exception {
        mvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Food\",\"kind\":\"expense\",\"color\":\"#22c55e\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(1)))
                .andExpect(jsonPath("$.name", equalTo("Food")))
                .andExpect(jsonPath("$.parent_id").doesNotExist());

        mvc.perform(patch("/api/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Groceries\",\"sort_order\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", equalTo("Groceries")))
                .andExpect(jsonPath("$.sort_order", equalTo(3)));

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", equalTo("Groceries")));

        mvc.perform(delete("/api/categories/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("archived")));

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/api/categories?include_archived=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void rejectsSelfAndDescendantParentsLikePythonRouter() throws Exception {
        mvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Parent\",\"kind\":\"expense\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Child\",\"kind\":\"expense\",\"parent_id\":1}"))
                .andExpect(status().isOk());

        mvc.perform(patch("/api/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parent_id\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("category cannot be its own parent")));

        mvc.perform(patch("/api/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parent_id\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("category parent cannot be a descendant")));
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
