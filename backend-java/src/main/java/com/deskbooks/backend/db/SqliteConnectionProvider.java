package com.deskbooks.backend.db;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.deskbooks.backend.profiles.ProfileInfo;
import com.deskbooks.backend.profiles.ProfileRegistry;
import org.springframework.stereotype.Service;

@Service
public class SqliteConnectionProvider {
    private static final String BUSY_TIMEOUT_MILLIS = "30000";

    private final ProfileRegistry profiles;
    private final SqliteSchema schema;

    SqliteConnectionProvider(ProfileRegistry profiles, SqliteSchema schema) {
        this.profiles = profiles;
        this.schema = schema;
    }

    public Connection open() throws SQLException {
        ProfileInfo activeProfile = profiles.getActiveProfile();
        try {
            Files.createDirectories(activeProfile.dbPath().getParent());
        } catch (java.io.IOException exception) {
            throw new SQLException("could not create profile database directory", exception);
        }

        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + activeProfile.dbPath(),
                connectionProperties());
        try {
            applyPragmas(connection);
            schema.ensure(connection);
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private void applyPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("busy_timeout", BUSY_TIMEOUT_MILLIS);
        return properties;
    }
}
