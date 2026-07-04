package com.deskbooks.backend.db;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.deskbooks.backend.profiles.AppPaths;
import com.deskbooks.backend.profiles.ProfileRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class SqliteConnectionProviderTest {
    @TempDir
    Path dataDir;

    @Test
    void openWaitsForTransientSqliteLocks() throws Exception {
        SqliteConnectionProvider provider = provider();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection locked = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("app.db"));
                Statement statement = locked.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");

            Future<Connection> opening = executor.submit(provider::open);
            Thread.sleep(250);

            assertFalse(opening.isDone());
            statement.execute("COMMIT");

            try (Connection opened = opening.get(5, TimeUnit.SECONDS)) {
                assertFalse(opened.isClosed());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private SqliteConnectionProvider provider() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("deskbooks.data-dir", dataDir.toString());
        ProfileRegistry profiles = new ProfileRegistry(new AppPaths(environment), new ObjectMapper());
        return new SqliteConnectionProvider(profiles, new SqliteSchema());
    }
}
