package com.deskbooks.backend.planning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
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
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/journal")
class JournalController {
    private final SqliteConnectionProvider connections;

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
        Path path = Path.of(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        List<String> pages = documentPages(path);
        List<JournalImportDraftResponse> drafts = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i).trim();
            if (!page.isBlank()) {
                drafts.add(new JournalImportDraftResponse(i + 1, stripExtension(path.getFileName().toString()) + " page " + (i + 1), page));
            }
        }
        if (drafts.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no journal text found");
        }
        return new JournalImportPreviewResponse(path.getFileName().toString(), drafts);
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

    private List<String> documentPages(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        try {
            if (filename.endsWith(".txt") || filename.endsWith(".md") || filename.endsWith(".markdown")) {
                return splitTextPages(Files.readString(path));
            }
            if (filename.endsWith(".docx")) {
                return docxPages(path);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read journal import text");
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "supported journal imports: .txt, .md, .markdown, .docx");
    }

    private List<String> splitTextPages(String text) {
        if (text.contains("\f")) {
            return List.of(text.split("\\f"));
        }
        List<String> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            String marker = line.trim().toLowerCase();
            if (marker.equals("--- page ---") || marker.equals("=== page ===")) {
                pages.add(String.join("\n", current));
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        pages.add(String.join("\n", current));
        return pages.stream().map(String::trim).filter(page -> !page.isBlank()).toList();
    }

    private List<String> docxPages(Path path) throws IOException {
        try (ZipFile docx = new ZipFile(path.toFile())) {
            var entry = docx.getEntry("word/document.xml");
            if (entry == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "could not read docx document text");
            }
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(docx.getInputStream(entry));
            NodeList paragraphs = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
            List<String> pages = new ArrayList<>();
            List<String> current = new ArrayList<>();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Element paragraph = (Element) paragraphs.item(i);
                String text = paragraphText(paragraph).trim();
                if (!text.isBlank()) {
                    current.add(text);
                }
                boolean hasPageBreak = paragraph.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "lastRenderedPageBreak").getLength() > 0;
                NodeList breaks = paragraph.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "br");
                for (int j = 0; j < breaks.getLength(); j++) {
                    Element br = (Element) breaks.item(j);
                    hasPageBreak = hasPageBreak || "page".equals(br.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "type"));
                }
                if (hasPageBreak && !current.isEmpty()) {
                    pages.add(String.join("\n\n", current));
                    current = new ArrayList<>();
                }
            }
            if (!current.isEmpty()) {
                pages.add(String.join("\n\n", current));
            }
            return pages.stream().map(String::trim).filter(page -> !page.isBlank()).toList();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read docx document text");
        }
    }

    private String paragraphText(Element paragraph) {
        NodeList textNodes = paragraph.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < textNodes.getLength(); i++) {
            builder.append(textNodes.item(i).getTextContent());
        }
        return builder.toString();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
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
