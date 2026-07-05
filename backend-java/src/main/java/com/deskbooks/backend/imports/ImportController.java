package com.deskbooks.backend.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.rules.RuleEngine;
import jakarta.validation.Valid;
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
    private final ImportBatchStore batches;
    private final ImportPreviewMarker preview;
    private final ImportPreviewParser parser;

    ImportController(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.batches = new ImportBatchStore(ruleEngine);
        this.preview = new ImportPreviewMarker(ruleEngine, batches);
        this.parser = new ImportPreviewParser(IMPORTERS);
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
            String filename = file.getOriginalFilename() == null ? "uploaded.csv" : file.getOriginalFilename();
            return previewBytes(file.getBytes(), filename, accountId, importerName);
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
            return previewBytes(
                    Files.readAllBytes(path),
                    path.getFileName().toString(),
                    body.accountId(),
                    body.importerName());
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
            ImportPreviewParser.ParsedImport parsed = parser.parse(data, filename, importerName);
            return preview.previewRows(
                    connection,
                    parsed.rows(),
                    parsed.importerName(),
                    accountId,
                    filename,
                    parsed.sniffNotes());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
