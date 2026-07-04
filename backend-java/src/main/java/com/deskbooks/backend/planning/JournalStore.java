package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class JournalStore {
    private final JournalPatchApplier patches = new JournalPatchApplier();
    private final JournalRevisionStore revisionStore = new JournalRevisionStore();

    List<JournalEntryResponse> listEntries(Connection connection, Long goalId) throws SQLException {
        String sql = "SELECT * FROM journal_entries" + (goalId == null ? "" : " WHERE goal_id = ?")
                + " ORDER BY entry_date DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (goalId != null) {
                statement.setLong(1, goalId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<JournalEntryResponse> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(JournalRows.entryFrom(rs));
                }
                return entries;
            }
        }
    }

    JournalEntryResponse createEntry(Connection connection, JournalEntryRequest body) throws SQLException {
        JournalEntryResponse created;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entries (entry_date, title, body_markdown, goal_id)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, body.entryDate().toString());
            statement.setString(2, body.title());
            statement.setString(3, body.bodyMarkdown());
            statement.setObject(4, body.goalId());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                created = getEntry(connection, keys.getLong(1));
            }
        }
        revisionStore.insert(connection, created, "created");
        return getEntry(connection, created.id());
    }

    JournalEntryResponse getEntry(Connection connection, long entryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM journal_entries WHERE id = ?")) {
            statement.setLong(1, entryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, JournalRows.JOURNAL_NOT_FOUND);
                }
                return JournalRows.entryFrom(rs);
            }
        }
    }

    JournalEntryResponse updateEntry(Connection connection, long entryId, JsonNode body) throws SQLException {
        JournalEntryResponse before = getEntry(connection, entryId);
        String changeSummary = patches.apply(connection, entryId, body);
        JournalEntryResponse after = getEntry(connection, entryId);
        if (!after.equals(before)) {
            revisionStore.insert(connection, after, revisionSummary(changeSummary));
        }
        return after;
    }

    void deleteEntry(Connection connection, long entryId) throws SQLException {
        getEntry(connection, entryId);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM journal_entries WHERE id = ?")) {
            statement.setLong(1, entryId);
            statement.executeUpdate();
        }
    }

    List<JournalEntryRevisionResponse> revisions(Connection connection, long entryId) throws SQLException {
        getEntry(connection, entryId);
        return revisionStore.list(connection, entryId);
    }

    private String revisionSummary(String changeSummary) {
        return changeSummary == null || changeSummary.isBlank() ? "edited" : changeSummary;
    }
}
