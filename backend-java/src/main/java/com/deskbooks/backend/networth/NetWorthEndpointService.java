package com.deskbooks.backend.networth;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
final class NetWorthEndpointService {
    private final NetWorthEndpointRunner endpoint;
    private final NetWorthReader reader = new NetWorthReader();
    private final NetWorthSeries seriesReader = new NetWorthSeries();
    private final NetWorthSnapshotMutations mutations = new NetWorthSnapshotMutations(reader);

    NetWorthEndpointService(SqliteConnectionProvider connections) {
        this.endpoint = new NetWorthEndpointRunner(connections);
    }

    List<NetWorthSnapshotResponse> listSnapshots() {
        return endpoint.run(reader::list);
    }

    NetWorthSnapshotResponse createSnapshot(NetWorthSnapshotRequest body) {
        return endpoint.run(connection -> mutations.create(connection, body));
    }

    NetWorthWorkbookImportResult importWorkbook(NetWorthWorkbookImportRequest body) {
        return endpoint.runWorkbookImport(connection -> NetWorthWorkbookImporter.importWorkbook(connection, body));
    }

    NetWorthSnapshotResponse updateSnapshot(long snapshotId, JsonNode body) {
        return endpoint.run(connection -> mutations.update(connection, snapshotId, body));
    }

    Map<String, String> deleteSnapshot(long snapshotId) {
        return endpoint.run(connection -> mutations.delete(connection, snapshotId));
    }

    List<NetWorthSeriesPointResponse> series(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }
        return endpoint.run(connection -> seriesReader.list(connection, start, end));
    }
}
