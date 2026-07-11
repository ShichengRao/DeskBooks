package com.deskbooks.backend.imports;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.rules.RuleEngine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
final class ImportEndpointService {
    private static final List<CsvImporter> IMPORTERS = CsvImporters.all();

    private final SqliteConnectionProvider connections;
    private final ImportBatchStore batches;
    private final ImportPreviewMarker preview;
    private final ImportPreviewParser parser;
    private final ImportPreviewSourceReader sources;

    ImportEndpointService(SqliteConnectionProvider connections, RuleEngine ruleEngine) {
        this.connections = connections;
        this.batches = new ImportBatchStore(ruleEngine);
        this.preview = new ImportPreviewMarker(ruleEngine, batches);
        this.parser = new ImportPreviewParser(IMPORTERS);
        this.sources = new ImportPreviewSourceReader();
    }

    List<ImporterResponse> listImporters() {
        return IMPORTERS.stream()
                .map(importer -> new ImporterResponse(importer.name(), importer.label()))
                .toList();
    }

    List<ImportBatchResponse> listBatches() {
        try (Connection connection = connections.open()) {
            return batches.list(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    ImportPreviewResponse previewUpload(MultipartFile file, long accountId, String importerName) {
        return previewSource(sources.fromUpload(file, accountId, importerName));
    }

    ImportPreviewResponse previewPath(ImportPathPreviewRequest body) {
        return previewSource(sources.fromPath(body));
    }

    ImportBatchResponse apply(ImportApplyRequest body) {
        return apply(body, null);
    }

    ImportBatchResponse apply(ImportApplyRequest body, String notes) {
        try (Connection connection = connections.open()) {
            return batches.apply(connection, body, notes);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    boolean hasBatchNotes(String notes) {
        try (Connection connection = connections.open()) {
            return batches.hasNotes(connection, notes);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    Map<String, String> rollbackBatch(long batchId) {
        try (Connection connection = connections.open()) {
            return batches.rollbackBatch(connection, batchId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ImportPreviewResponse previewSource(ImportPreviewSource source) {
        try (Connection connection = connections.open()) {
            batches.requireAccount(connection, source.accountId());
            ImportPreviewParser.ParsedImport parsed = parser.parse(source.data(), source.filename(), source.importerName());
            return preview.previewRows(
                    connection,
                    parsed.rows(),
                    parsed.importerName(),
                    source.accountId(),
                    source.filename(),
                    parsed.sniffNotes());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
