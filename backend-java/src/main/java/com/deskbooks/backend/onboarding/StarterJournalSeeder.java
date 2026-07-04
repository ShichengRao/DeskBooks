package com.deskbooks.backend.onboarding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

final class StarterJournalSeeder {
    private static final String TABLE_JOURNAL_ENTRIES = "journal_entries";
    private static final String WELCOME_TITLE = "Welcome";
    private static final String WELCOME_BODY = """
            This local profile stores its data in a separate SQLite file.

            Add accounts, import transactions, and create snapshots to get started.
            """;

    int seed(Connection connection) throws SQLException {
        if (hasAnyRow(connection)) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        long entryId = insertWelcomeJournalEntry(connection, today);
        insertWelcomeJournalRevision(connection, entryId, today);
        return 1;
    }

    private long insertWelcomeJournalEntry(Connection connection, LocalDate today) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entries (entry_date, title, body_markdown, goal_id)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, today.toString());
            statement.setString(2, WELCOME_TITLE);
            statement.setString(3, WELCOME_BODY);
            statement.setObject(4, null);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertWelcomeJournalRevision(Connection connection, long entryId, LocalDate today)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entry_revisions (
                  entry_id, title, body_markdown, entry_date, goal_id, change_summary
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, entryId);
            statement.setString(2, WELCOME_TITLE);
            statement.setString(3, WELCOME_BODY);
            statement.setString(4, today.toString());
            statement.setObject(5, null);
            statement.setString(6, "initial starter seed");
            statement.executeUpdate();
        }
    }

    private boolean hasAnyRow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + TABLE_JOURNAL_ENTRIES + " LIMIT 1");
                ResultSet rs = statement.executeQuery()) {
            return rs.next();
        }
    }
}
