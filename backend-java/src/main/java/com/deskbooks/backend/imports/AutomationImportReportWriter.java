package com.deskbooks.backend.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
final class AutomationImportReportWriter {
    private final ObjectMapper mapper;
    private final AutomationImportHtmlReport html;

    AutomationImportReportWriter(ObjectMapper mapper, AutomationImportHtmlReport html) {
        this.mapper = mapper;
        this.html = html;
    }

    Path write(Path stagingDir, ImportPreviewResponse preview, Path filePath, int nonDuplicates)
            throws IOException {
        Files.createDirectories(stagingDir);
        Path jsonPath = stagingDir.resolve("latest-preview.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), payload(preview, filePath, nonDuplicates));
        Path htmlPath = stagingDir.resolve("latest-preview.html");
        Files.writeString(htmlPath, html.render(preview, filePath, nonDuplicates), StandardCharsets.UTF_8);
        return htmlPath;
    }

    private Map<String, Object> payload(ImportPreviewResponse preview, Path filePath, int nonDuplicates) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_filename", preview.sourceFilename());
        payload.put("path", filePath.toString());
        payload.put("account_id", preview.accountId());
        payload.put("importer_name", preview.importerName());
        payload.put("row_count", preview.rows().size());
        payload.put("new_count", nonDuplicates);
        payload.put("duplicate_count", preview.rows().size() - nonDuplicates);
        payload.put("rows", preview.rows().stream().map(this::reportRow).toList());
        return payload;
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
}
