package com.deskbooks.backend.networth;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
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
    private final NetWorthEndpointService snapshots;

    NetWorthController(NetWorthEndpointService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("")
    List<NetWorthSnapshotResponse> listSnapshots() {
        return snapshots.listSnapshots();
    }

    @PostMapping("")
    NetWorthSnapshotResponse createSnapshot(@Valid @RequestBody NetWorthSnapshotRequest body) {
        return snapshots.createSnapshot(body);
    }

    @PostMapping("/import-workbook")
    NetWorthWorkbookImportResult importWorkbook(@Valid @RequestBody NetWorthWorkbookImportRequest body) {
        return snapshots.importWorkbook(body);
    }

    @PatchMapping("/{snapshotId}")
    NetWorthSnapshotResponse updateSnapshot(@PathVariable long snapshotId, @RequestBody JsonNode body) {
        return snapshots.updateSnapshot(snapshotId, body);
    }

    @DeleteMapping("/{snapshotId}")
    Map<String, String> deleteSnapshot(@PathVariable long snapshotId) {
        return snapshots.deleteSnapshot(snapshotId);
    }

    @GetMapping("/series")
    List<NetWorthSeriesPointResponse> series(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        return snapshots.series(start, end);
    }
}
