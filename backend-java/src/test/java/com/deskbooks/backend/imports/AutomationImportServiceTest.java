package com.deskbooks.backend.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.stream.Stream;

import com.deskbooks.backend.DeskBooksApplication;
import com.deskbooks.backend.db.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = DeskBooksApplication.class)
class AutomationImportServiceTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void profileProperties(DynamicPropertyRegistry registry) {
        registry.add("deskbooks.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private AutomationImportService imports;

    @Autowired
    private AutomationImportFiles files;

    @Autowired
    private SqliteConnectionProvider connections;

    private Path stagingDir;

    @BeforeEach
    void setUp() throws Exception {
        cleanDataDir();
        stagingDir = dataDir.resolve("import-staging");
        Files.createDirectories(stagingDir);
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO accounts (name, account_category, type)
                        VALUES ('Credit Card', 'credit', 'credit_card')
                        """)) {
            statement.executeUpdate();
        }
    }

    @Test
    void stagedImportPreviewsAppliesBacksUpAndSkipsReruns() throws Exception {
        Path csv = stagingDir.resolve("chase.csv");
        Files.writeString(csv, String.join("\n",
                "Transaction Date,Post Date,Description,Category,Type,Amount,Memo",
                "06/01/2026,06/02/2026,ACME GROCERY,Groceries,Sale,-42.18,"));
        Path manifest = stagingDir.resolve("latest-manifest.jsonl");
        Files.writeString(manifest, """
                {"source":"chase","path":"%s","account_id":1,"importer_name":"chase_credit","sha256":"abc123"}
                """.formatted(csv));
        Path state = stagingDir.resolve("import-state.json");

        AutomationImportResult preview = imports.run(options(manifest, state, false));
        assertEquals(new AutomationImportResult(1, 0, 0), preview);
        assertTrue(Files.isRegularFile(stagingDir.resolve("latest-preview.json")));
        assertTrue(Files.isRegularFile(stagingDir.resolve("latest-preview.html")));
        assertEquals(0, count("transactions"));

        AutomationImportResult applied = imports.run(options(manifest, state, true));
        assertEquals(new AutomationImportResult(1, 1, 0), applied);
        assertEquals(1, count("transactions"));
        assertEquals(1, count("import_batches"));
        assertEquals("automation_sha256=abc123", batchNotes());
        assertTrue(Files.readString(state).contains("\"applied_sha256\""));
        assertEquals(1, backupCount());

        AutomationImportResult rerun = imports.run(options(manifest, state, true));
        assertEquals(new AutomationImportResult(0, 0, 1), rerun);
        assertEquals(1, count("transactions"));
        assertEquals(1, count("import_batches"));
        assertEquals(1, backupCount());
    }

    @Test
    void stagedFilesMustRemainInsideTheConfiguredDirectory() throws IOException {
        Path outside = dataDir.resolve("outside.csv");
        Files.writeString(outside, "not used");
        AutomationManifestEntry entry = new AutomationManifestEntry(
                "chase", outside.toString(), 1, "chase_credit", "abc123");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> files.validate(entry, stagingDir));
        assertTrue(exception.getMessage().startsWith("refusing file outside staging dir:"));
    }

    private AutomationImportOptions options(Path manifest, Path state, boolean apply) {
        return new AutomationImportOptions(manifest, stagingDir, state, apply, null, false);
    }

    private int count(String table) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String batchNotes() throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("SELECT notes FROM import_batches");
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private long backupCount() throws IOException {
        Path backupDir = dataDir.resolve("backups");
        if (!Files.exists(backupDir)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(backupDir)) {
            return paths.filter(Files::isRegularFile).count();
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
