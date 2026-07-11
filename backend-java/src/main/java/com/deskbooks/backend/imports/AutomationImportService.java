package com.deskbooks.backend.imports;

import java.io.IOException;
import java.util.List;

import com.deskbooks.backend.backups.BackupService;
import com.deskbooks.backend.profiles.ProfileRegistry;
import org.springframework.stereotype.Service;

@Service
final class AutomationImportService {
    private final ImportEndpointService imports;
    private final ProfileRegistry profiles;
    private final BackupService backups;
    private final AutomationImportFiles files;
    private final AutomationImportReportWriter reports;

    AutomationImportService(
            ImportEndpointService imports,
            ProfileRegistry profiles,
            BackupService backups,
            AutomationImportFiles files,
            AutomationImportReportWriter reports) {
        this.imports = imports;
        this.profiles = profiles;
        this.backups = backups;
        this.files = files;
        this.reports = reports;
    }

    AutomationImportResult run(AutomationImportOptions options) throws IOException {
        List<AutomationManifestEntry> entries = files.readManifest(options.manifest()).stream()
                .filter(entry -> options.source() == null || options.source().equals(entry.source()))
                .toList();
        if (entries.isEmpty()) {
            System.out.println("[import] no manifest entries to process");
            return new AutomationImportResult(0, 0, 0);
        }

        AutomationImportRun run = new AutomationImportRun(
                options,
                files.loadState(options.state()),
                imports,
                profiles,
                backups,
                files,
                reports);
        for (AutomationManifestEntry rawEntry : entries) {
            run.process(rawEntry);
        }
        return run.finish();
    }
}
