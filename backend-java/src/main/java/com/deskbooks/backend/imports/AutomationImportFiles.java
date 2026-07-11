package com.deskbooks.backend.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
final class AutomationImportFiles {
    private static final int MAXIMUM_REPORT_ROWS = 200;

    private final ObjectMapper mapper;

    AutomationImportFiles(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    List<AutomationManifestEntry> readManifest(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<AutomationManifestEntry> entries = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(mapper.readValue(line, AutomationManifestEntry.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                        "invalid manifest JSON on line " + lineNumber + ": " + exception.getOriginalMessage(),
                        exception);
            }
        }
        return entries;
    }

    AutomationImportState loadState(Path path) throws IOException {
        if (!Files.exists(path)) {
            return AutomationImportState.empty();
        }
        return mapper.readValue(path.toFile(), AutomationImportState.class);
    }

    void saveState(Path path, AutomationImportState state) throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), state);
    }

    ValidatedAutomationManifestEntry validate(AutomationManifestEntry entry, Path stagingDir) {
        Path root = stagingDir.toAbsolutePath().normalize();
        Path file = requiredPath(entry.path()).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("refusing file outside staging dir: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("staged file not found: " + file);
        }
        return new ValidatedAutomationManifestEntry(
                entry.source(),
                file,
                entry.accountId(),
                requireText(entry.importerName(), "manifest entry has empty importer_name"),
                requireText(entry.sha256(), "manifest entry has empty sha256"));
    }

    Path writePreviewReport(
            Path stagingDir,
            ImportPreviewResponse preview,
            Path filePath,
            int nonDuplicates) throws IOException {
        Files.createDirectories(stagingDir);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_filename", preview.sourceFilename());
        payload.put("path", filePath.toString());
        payload.put("account_id", preview.accountId());
        payload.put("importer_name", preview.importerName());
        payload.put("row_count", preview.rows().size());
        payload.put("new_count", nonDuplicates);
        payload.put("duplicate_count", preview.rows().size() - nonDuplicates);
        payload.put("rows", preview.rows().stream().map(this::reportRow).toList());

        Path jsonPath = stagingDir.resolve("latest-preview.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), payload);
        Path htmlPath = stagingDir.resolve("latest-preview.html");
        Files.writeString(htmlPath, previewHtml(preview, filePath, nonDuplicates), StandardCharsets.UTF_8);
        return htmlPath;
    }

    private String previewHtml(ImportPreviewResponse preview, Path filePath, int nonDuplicates) {
        List<ImportDraftRow> shownRows = preview.rows().stream().limit(MAXIMUM_REPORT_ROWS).toList();
        StringBuilder rows = new StringBuilder();
        for (ImportDraftRow row : shownRows) {
            rows.append("<tr><td>").append(escape(row.date()))
                    .append("</td><td>").append(escape(row.descriptionRaw()))
                    .append("</td><td>").append(escape(row.suggestedKind()))
                    .append("</td><td class=\"amount\">").append(escape(row.amount()))
                    .append("</td><td>").append(row.isDuplicate() ? "yes" : "")
                    .append("</td></tr>\n");
        }
        String more = preview.rows().size() > shownRows.size()
                ? "<p>Showing first " + shownRows.size() + " of " + preview.rows().size() + " rows.</p>"
                : "";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>DeskBooks Import Preview</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 24px; color: #172033; }
                    code { background: #f4f6f8; padding: 2px 4px; border-radius: 4px; }
                    table { border-collapse: collapse; width: 100%%; font-size: 12px; }
                    th, td { border-bottom: 1px solid #e4e8ee; padding: 6px 8px; text-align: left; }
                    th { background: #f8fafc; position: sticky; top: 0; }
                    .amount { text-align: right; font-variant-numeric: tabular-nums; }
                  </style>
                </head>
                <body>
                  <h1>DeskBooks Import Preview</h1>
                  <p><strong>%s</strong></p>
                  <p>Rows: %d &middot; New: %d &middot; Duplicates: %d &middot; Importer: <code>%s</code> &middot; Account ID: %d</p>
                  <p>Source path: <code>%s</code></p>
                  %s
                  <table>
                    <thead><tr><th>Date</th><th>Description</th><th>Kind</th><th>Amount</th><th>Duplicate?</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                </body>
                </html>
                """.formatted(
                escape(preview.sourceFilename()),
                preview.rows().size(),
                nonDuplicates,
                preview.rows().size() - nonDuplicates,
                escape(preview.importerName()),
                preview.accountId(),
                escape(filePath),
                more,
                rows);
    }

    private Map<String, Object> reportRow(ImportDraftRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("row_index", row.rowIndex());
        payload.put("date", row.date() == null ? null : row.date().toString());
        payload.put("post_date", row.postDate() == null ? null : row.postDate().toString());
        payload.put("description_raw", row.descriptionRaw());
        payload.put("description_normalized", row.descriptionNormalized());
        payload.put("merchant", row.merchant());
        payload.put("amount", row.amount());
        payload.put("suggested_category_id", row.suggestedCategoryId());
        payload.put("suggested_kind", row.suggestedKind());
        payload.put("suggested_tags", row.suggestedTags());
        payload.put("suggested_matched_rule_id", row.suggestedMatchedRuleId());
        payload.put("is_duplicate", row.isDuplicate());
        payload.put("raw", row.raw());
        return payload;
    }

    private Path requiredPath(String value) {
        return Path.of(requireText(value, "manifest entry missing field: path"));
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String escape(Object value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value.toString());
    }
}
