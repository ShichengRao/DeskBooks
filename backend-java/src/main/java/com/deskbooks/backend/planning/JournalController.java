package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/journal")
class JournalController {
    private final SqliteConnectionProvider connections;
    private final JournalImportParser importParser = new JournalImportParser();

    JournalController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<JournalEntryResponse> listEntries(@RequestParam(name = "goal_id", required = false) Long goalId) {
        String sql = "SELECT * FROM journal_entries" + (goalId == null ? "" : " WHERE goal_id = ?")
                + " ORDER BY entry_date DESC";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (goalId != null) {
                statement.setLong(1, goalId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<JournalEntryResponse> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(entryFrom(rs));
                }
                return entries;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    JournalEntryResponse createEntry(@Valid @RequestBody JournalEntryRequest body) {
        try (Connection connection = connections.open()) {
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
            insertRevision(connection, created, "created");
            return getEntry(connection, created.id());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/import-preview")
    JournalImportPreviewResponse importPreview(@Valid @RequestBody JournalImportPreviewRequest body) {
        return importParser.preview(body);
    }

    @GetMapping("/{entryId}")
    JournalEntryResponse getEntry(@PathVariable long entryId) {
        try (Connection connection = connections.open()) {
            return getEntry(connection, entryId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{entryId}")
    JournalEntryResponse updateEntry(@PathVariable long entryId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            JournalEntryResponse before = getEntry(connection, entryId);
            List<PatchValue> values = new ArrayList<>();
            PlanningSql.addDate(values, body, "entry_date");
            PlanningSql.addText(values, body, "title");
            PlanningSql.addText(values, body, "body_markdown");
            PlanningSql.addLong(values, body, "goal_id");
            String changeSummary = PlanningSql.textOrNull(body, "change_summary");
            if (!values.isEmpty()) {
                StringJoiner assignments = new StringJoiner(", ");
                for (PatchValue value : values) {
                    assignments.add(value.column() + " = ?");
                }
                assignments.add("updated_at = CURRENT_TIMESTAMP");
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE journal_entries SET " + assignments + " WHERE id = ?")) {
                    int index = 1;
                    for (PatchValue value : values) {
                        statement.setObject(index++, value.value());
                    }
                    statement.setLong(index, entryId);
                    statement.executeUpdate();
                }
            }
            JournalEntryResponse after = getEntry(connection, entryId);
            if (!after.equals(before)) {
                insertRevision(connection, after, changeSummary == null || changeSummary.isBlank() ? "edited" : changeSummary);
            }
            return after;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{entryId}")
    Map<String, String> deleteEntry(@PathVariable long entryId) {
        try (Connection connection = connections.open()) {
            getEntry(connection, entryId);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM journal_entries WHERE id = ?")) {
                statement.setLong(1, entryId);
                statement.executeUpdate();
            }
            return Map.of("status", "deleted");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{entryId}/revisions")
    List<JournalEntryRevisionResponse> revisions(@PathVariable long entryId) {
        try (Connection connection = connections.open()) {
            getEntry(connection, entryId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM journal_entry_revisions WHERE entry_id = ? ORDER BY changed_at DESC, id DESC
                    """)) {
                statement.setLong(1, entryId);
                try (ResultSet rs = statement.executeQuery()) {
                    List<JournalEntryRevisionResponse> revisions = new ArrayList<>();
                    while (rs.next()) {
                        revisions.add(new JournalEntryRevisionResponse(
                                rs.getLong("id"),
                                rs.getLong("entry_id"),
                                rs.getString("title"),
                                rs.getString("body_markdown"),
                                PlanningSql.localDate(rs, "entry_date"),
                                PlanningSql.nullableLong(rs, "goal_id"),
                                PlanningSql.localDateTime(rs, "changed_at"),
                                rs.getString("change_summary")));
                    }
                    return revisions;
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private JournalEntryResponse getEntry(Connection connection, long entryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM journal_entries WHERE id = ?")) {
            statement.setLong(1, entryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "journal entry not found");
                }
                return entryFrom(rs);
            }
        }
    }

    private JournalEntryResponse entryFrom(ResultSet rs) throws SQLException {
        return new JournalEntryResponse(
                rs.getLong("id"),
                LocalDate.parse(rs.getString("entry_date")),
                rs.getString("title"),
                rs.getString("body_markdown"),
                PlanningSql.nullableLong(rs, "goal_id"),
                PlanningSql.localDateTime(rs, "created_at"),
                PlanningSql.localDateTime(rs, "updated_at"));
    }

    private void insertRevision(Connection connection, JournalEntryResponse entry, String summary) throws SQLException {
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

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record JournalEntryRequest(
            @NotNull LocalDate entryDate,
            @NotBlank String title,
            @NotBlank String bodyMarkdown,
            Long goalId) {
    }

    record JournalEntryResponse(
            long id,
            LocalDate entryDate,
            String title,
            String bodyMarkdown,
            Long goalId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record JournalEntryRevisionResponse(
            long id,
            long entryId,
            String title,
            String bodyMarkdown,
            LocalDate entryDate,
            Long goalId,
            LocalDateTime changedAt,
            String changeSummary) {
    }

    record JournalImportPreviewRequest(@NotBlank String path) {
    }

    record JournalImportDraftResponse(int pageNumber, String title, String bodyMarkdown) {
    }

    record JournalImportPreviewResponse(String sourceFilename, List<JournalImportDraftResponse> drafts) {
    }
}
