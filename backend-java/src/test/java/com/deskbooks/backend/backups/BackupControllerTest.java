package com.deskbooks.backend.backups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.stream.Stream;

import com.deskbooks.backend.DeskBooksApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = DeskBooksApplication.class)
class BackupControllerTest {
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
    void backupRestoreReplacesActiveProfileDatabaseAndKeepsSafetyCopy() throws Exception {
        Path dbPath = dataDir.resolve("app.db");
        writeMarker(dbPath, "clean");

        MvcResult createdResult = mvc.perform(post("/api/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_slug", equalTo("personal")))
                .andReturn();
        String backupName = backupName(createdResult);

        writeMarker(dbPath, "broken");

        mvc.perform(post("/api/backups/" + backupName + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", equalTo(backupName)));

        assertThat(readMarker(dbPath)).isEqualTo("clean");
        mvc.perform(get("/api/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backups", hasSize(2)))
                .andExpect(jsonPath("$.backups[0].name", endsWith("-pre-restore.db")));
    }

    @Test
    void deleteBackupRemovesProfileBackup() throws Exception {
        Path dbPath = dataDir.resolve("app.db");
        writeMarker(dbPath, "clean");

        MvcResult createdResult = mvc.perform(post("/api/backups"))
                .andExpect(status().isOk())
                .andReturn();
        String backupName = backupName(createdResult);

        mvc.perform(delete("/api/backups/" + backupName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", equalTo(backupName)));

        mvc.perform(get("/api/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backups", hasSize(0)));
    }

    @Test
    void invalidBackupNameReturnsBadRequestDetail() throws Exception {
        mvc.perform(post("/api/backups/bad.db/restore"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", equalTo("invalid backup name")));
    }

    private String backupName(MvcResult result) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(result.getResponse().getContentAsString()).get("name").asString();
    }

    private void writeMarker(Path dbPath, String value) throws IOException, SQLException {
        Files.createDirectories(dbPath.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS marker (value TEXT NOT NULL)");
            statement.execute("DELETE FROM marker");
            try (var insert = connection.prepareStatement("INSERT INTO marker (value) VALUES (?)")) {
                insert.setString(1, value);
                insert.executeUpdate();
            }
        }
    }

    private String readMarker(Path dbPath) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                var statement = connection.createStatement();
                var rs = statement.executeQuery("SELECT value FROM marker")) {
            rs.next();
            return rs.getString(1);
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
