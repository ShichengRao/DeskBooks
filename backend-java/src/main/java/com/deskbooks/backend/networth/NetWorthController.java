package com.deskbooks.backend.networth;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
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
@RequestMapping("/api/snapshots")
class NetWorthController {
    private final SqliteConnectionProvider connections;
    private final NetWorthReader reader = new NetWorthReader();
    private final NetWorthSeries seriesReader = new NetWorthSeries();
    private final NetWorthSnapshotMutations mutations = new NetWorthSnapshotMutations(reader);

    NetWorthController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<NetWorthSnapshotResponse> listSnapshots() {
        try (Connection connection = connections.open()) {
            return reader.list(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    NetWorthSnapshotResponse createSnapshot(@Valid @RequestBody NetWorthSnapshotRequest body) {
        try (Connection connection = connections.open()) {
            return mutations.create(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/import-workbook")
    NetWorthWorkbookImportResult importWorkbook(@Valid @RequestBody NetWorthWorkbookImportRequest body) {
        try (Connection connection = connections.open()) {
            return NetWorthWorkbookImporter.importWorkbook(connection, body);
        } catch (java.io.IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read workbook");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{snapshotId}")
    NetWorthSnapshotResponse updateSnapshot(@PathVariable long snapshotId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return mutations.update(connection, snapshotId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{snapshotId}")
    Map<String, String> deleteSnapshot(@PathVariable long snapshotId) {
        try (Connection connection = connections.open()) {
            return mutations.delete(connection, snapshotId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/series")
    List<NetWorthSeriesPointResponse> series(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }

        try (Connection connection = connections.open()) {
            return seriesReader.list(connection, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
