package com.deskbooks.backend.imports;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
final class AutomationImportHtmlReport {
    private static final int MAXIMUM_REPORT_ROWS = 200;

    String render(ImportPreviewResponse preview, Path filePath, int nonDuplicates) {
        List<ImportDraftRow> shownRows = preview.rows().stream().limit(MAXIMUM_REPORT_ROWS).toList();
        return page(preview, filePath, nonDuplicates, shownRows, tableRows(shownRows));
    }

    private String page(
            ImportPreviewResponse preview,
            Path filePath,
            int nonDuplicates,
            List<ImportDraftRow> shownRows,
            String rows) {
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

    private String tableRows(List<ImportDraftRow> shownRows) {
        StringBuilder rows = new StringBuilder();
        for (ImportDraftRow row : shownRows) {
            rows.append("<tr><td>").append(escape(row.date()))
                    .append("</td><td>").append(escape(row.descriptionRaw()))
                    .append("</td><td>").append(escape(row.suggestedKind()))
                    .append("</td><td class=\"amount\">").append(escape(row.amount()))
                    .append("</td><td>").append(row.isDuplicate() ? "yes" : "")
                    .append("</td></tr>\n");
        }
        return rows.toString();
    }

    private String escape(Object value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value.toString());
    }
}
