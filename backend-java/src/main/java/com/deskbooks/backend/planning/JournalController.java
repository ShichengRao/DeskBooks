package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
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
    private final JournalStore journal = new JournalStore();
    private final JournalImportParser importParser = new JournalImportParser();

    JournalController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<JournalEntryResponse> listEntries(@RequestParam(name = "goal_id", required = false) Long goalId) {
        try (Connection connection = connections.open()) {
            return journal.listEntries(connection, goalId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    JournalEntryResponse createEntry(@Valid @RequestBody JournalEntryRequest body) {
        try (Connection connection = connections.open()) {
            return journal.createEntry(connection, body);
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
            return journal.getEntry(connection, entryId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{entryId}")
    JournalEntryResponse updateEntry(@PathVariable long entryId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return journal.updateEntry(connection, entryId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{entryId}")
    Map<String, String> deleteEntry(@PathVariable long entryId) {
        try (Connection connection = connections.open()) {
            journal.deleteEntry(connection, entryId);
            return Map.of("status", "deleted");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{entryId}/revisions")
    List<JournalEntryRevisionResponse> revisions(@PathVariable long entryId) {
        try (Connection connection = connections.open()) {
            return journal.revisions(connection, entryId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
