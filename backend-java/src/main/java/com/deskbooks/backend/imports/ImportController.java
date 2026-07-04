package com.deskbooks.backend.imports;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.deskbooks.backend.imports.ImportParsing.money;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.rules.RuleEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
class ImportController {
    private static final List<CsvImporter> IMPORTERS = CsvImporters.all();

    private final SqliteConnectionProvider connections;
    private final RuleEngine ruleEngine;
    private final ImportBatchStore batches;

    ImportController(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.ruleEngine = ruleEngine;
        this.batches = new ImportBatchStore(ruleEngine);
    }

    @GetMapping("/importers")
    List<ImporterResponse> listImporters() {
        return IMPORTERS.stream()
                .map(importer -> new ImporterResponse(importer.name(), importer.label()))
                .toList();
    }

    @GetMapping("")
    List<ImportBatchResponse> listBatches() {
        try (Connection connection = connections.open()) {
            return batches.list(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/preview")
    ImportPreviewResponse previewUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("account_id") long accountId,
            @RequestParam(name = "importer_name", required = false) String importerName) {
        try {
            return previewBytes(file.getBytes(), file.getOriginalFilename() == null ? "uploaded.csv" : file.getOriginalFilename(), accountId, importerName);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read upload");
        }
    }

    @PostMapping("/preview-path")
    ImportPreviewResponse previewPath(@Valid @RequestBody ImportPathPreviewRequest body) {
        Path path = Path.of(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        try {
            return previewBytes(Files.readAllBytes(path), path.getFileName().toString(), body.accountId(), body.importerName());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read file");
        }
    }

    @PostMapping("/apply")
    ImportBatchResponse apply(@Valid @RequestBody ImportApplyRequest body) {
        try (Connection connection = connections.open()) {
            return batches.apply(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/{batchId}/rollback")
    Map<String, String> rollbackBatch(@PathVariable long batchId) {
        try (Connection connection = connections.open()) {
            return batches.rollbackBatch(connection, batchId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ImportPreviewResponse previewBytes(byte[] data, String filename, long accountId, String importerName) {
        try (Connection connection = connections.open()) {
            batches.requireAccount(connection, accountId);
            if (filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                List<ImportDraftRow> rows = AmexWorkbookParser.parse(data);
                if (rows.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "no importer can handle this file");
                }
                return previewRows(
                        connection,
                        rows,
                        "amex",
                        accountId,
                        filename,
                        List.of("matched importers: amex"));
            }

            String csvText = new String(data, StandardCharsets.UTF_8);
            List<CsvImporter> matched = IMPORTERS.stream().filter(importer -> importer.canHandle(csvText)).toList();
            CsvImporter chosen;
            if (importerName != null && !importerName.isBlank()) {
                chosen = IMPORTERS.stream()
                        .filter(importer -> importer.name().equals(importerName))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "unknown importer: " + importerName));
            } else {
                if (matched.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "no importer can handle this file");
                }
                chosen = matched.get(0);
            }

            List<ImportDraftRow> rows = chosen.parse(csvText);
            String names = matched.isEmpty()
                    ? ""
                    : String.join(", ", matched.stream().map(CsvImporter::name).toList());
            return previewRows(
                    connection,
                    rows,
                    chosen.name(),
                    accountId,
                    filename,
                    List.of("matched importers: " + names));
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ImportPreviewResponse previewRows(
            Connection connection,
            List<ImportDraftRow> rows,
            String importerName,
            long accountId,
            String filename,
            List<String> sniffNotes) throws SQLException {
            List<RuleEngine.RuleRecord> activeRules = ruleEngine.loadActiveRules(connection);
            Map<DuplicateKey, Integer> existing = batches.existingKeyCounts(connection, accountId);
            Map<DuplicateKey, Integer> fileCounts = new LinkedHashMap<>();
            List<ImportDraftRow> markedRows = new ArrayList<>();
            for (ImportDraftRow row : rows) {
                row = row.withRuleSuggestion(ruleEngine.evaluate(
                        activeRules,
                        accountId,
                        row.descriptionNormalized() == null ? row.descriptionRaw() : row.descriptionNormalized(),
                        row.amountValue()));
                DuplicateKey key = new DuplicateKey(row.date(), money(row.amountValue()), row.descriptionNormalized() == null ? "" : row.descriptionNormalized());
                int position = fileCounts.getOrDefault(key, 0);
                fileCounts.put(key, position + 1);
                boolean duplicate = position < existing.getOrDefault(key, 0);
                markedRows.add(row.withDuplicate(duplicate));
            }
            return new ImportPreviewResponse(
                    importerName,
                    accountId,
                    filename,
                    markedRows,
                    sniffNotes);
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record ImportPathPreviewRequest(@NotNull String path, long accountId, String importerName) {
    }

    record ImportApplyRequest(
            @NotNull String importerName,
            long accountId,
            @NotNull String sourceFilename,
            List<ImportDraftRow> rows,
            boolean skipDuplicates) {
        ImportApplyRequest {
            rows = rows == null ? List.of() : rows;
        }
    }

    record ImporterResponse(String name, String label) {
    }

    record ImportPreviewResponse(
            String importerName,
            long accountId,
            String sourceFilename,
            List<ImportDraftRow> rows,
            List<String> sniffNotes) {
    }

    record ImportDraftRow(
            int rowIndex,
            LocalDate date,
            LocalDate postDate,
            String descriptionRaw,
            String descriptionNormalized,
            String merchant,
            String amount,
            Long suggestedCategoryId,
            String suggestedKind,
            List<String> suggestedTags,
            Long suggestedMatchedRuleId,
            boolean isDuplicate,
            Map<String, String> raw) {
        ImportDraftRow withDuplicate(boolean duplicate) {
            return new ImportDraftRow(
                    rowIndex,
                    date,
                    postDate,
                    descriptionRaw,
                    descriptionNormalized,
                    merchant,
                    amount,
                    suggestedCategoryId,
                    suggestedKind,
                    suggestedTags,
                    suggestedMatchedRuleId,
                    duplicate,
                    raw);
        }
        ImportDraftRow withRuleSuggestion(RuleEngine.RuleEval eval) {
            if (!eval.matched()) {
                return this;
            }
            return new ImportDraftRow(
                    rowIndex,
                    date,
                    postDate,
                    descriptionRaw,
                    descriptionNormalized,
                    eval.merchant() == null || eval.merchant().isBlank() ? merchant : eval.merchant(),
                    amount,
                    eval.categoryId() == null ? suggestedCategoryId : eval.categoryId(),
                    eval.kind() == null ? suggestedKind : eval.kind(),
                    eval.tags() == null ? suggestedTags : eval.tags(),
                    eval.matchedRuleId(),
                    isDuplicate,
                    raw);
        }
        BigDecimal amountValue() {
            return amount == null ? null : new BigDecimal(amount);
        }
    }

    record ImportBatchResponse(
            long id,
            String sourceFilename,
            String importerName,
            long accountId,
            LocalDateTime importedAt,
            int rowCountTotal,
            int rowCountApplied,
            int rowCountDuplicate,
            String status,
            String notes) {
    }

    record DuplicateKey(LocalDate date, BigDecimal amount, String description) {
    }
}
