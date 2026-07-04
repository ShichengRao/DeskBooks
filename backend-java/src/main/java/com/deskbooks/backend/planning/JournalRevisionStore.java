package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class JournalRevisionStore {
    void insert(Connection connection, JournalEntryResponse entry, String summary) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entry_revisions (
                  entry_id, title, body_markdown, entry_date, goal_id, change_summary
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, entry.id());
            statement.setString(2, entry.title());
            statement.setString(3, entry.bodyMarkdown());
            statement.setString(4, entry.entryDate().toString());
            statement.setObject(5, entry.goalId());
            statement.setString(6, summary);
            statement.executeUpdate();
        }
    }

    List<JournalEntryRevisionResponse> list(Connection connection, long entryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM journal_entry_revisions WHERE entry_id = ? ORDER BY changed_at DESC, id DESC
                """)) {
            statement.setLong(1, entryId);
            try (ResultSet rs = statement.executeQuery()) {
                List<JournalEntryRevisionResponse> revisions = new ArrayList<>();
                while (rs.next()) {
                    revisions.add(JournalRows.revisionFrom(rs));
                }
                return revisions;
            }
        }
    }
}
