package com.deskbooks.backend.imports;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.deskbooks.backend.backups.BackupResponse;
import com.deskbooks.backend.backups.BackupService;
import com.deskbooks.backend.profiles.ProfileRegistry;
import org.springframework.stereotype.Service;

@Service
final class AutomationImportService {
    private static final String AUTOMATION_NOTES_PREFIX = "automation_sha256=";

    private final ImportEndpointService imports;
    private final ProfileRegistry profiles;
    private final BackupService backups;
    private final AutomationImportFiles files;

    AutomationImportService(
            ImportEndpointService imports,
            ProfileRegistry profiles,
            BackupService backups,
            AutomationImportFiles files) {
        this.imports = imports;
        this.profiles = profiles;
        this.backups = backups;
        this.files = files;
    }

    AutomationImportResult run(AutomationImportOptions options) throws IOException {
        List<AutomationManifestEntry> entries = files.readManifest(options.manifest()).stream()
                .filter(entry -> options.source() == null || options.source().equals(entry.source()))
                .toList();
        if (entries.isEmpty()) {
            System.out.println("[import] no manifest entries to process");
            return new AutomationImportResult(0, 0, 0);
        }

        AutomationImportState state = files.loadState(options.state());
        Set<String> seenHashes = new HashSet<>();
        boolean didBackup = false;
        int previewed = 0;
        int imported = 0;
        int skipped = 0;

        for (AutomationManifestEntry rawEntry : entries) {
            ValidatedAutomationManifestEntry entry = files.validate(rawEntry, options.stagingDir());
            if (!seenHashes.add(entry.sha256())) {
                System.out.println("[import] skip duplicate manifest entry: " + entry.filePath().getFileName());
                skipped++;
                continue;
            }
            String notes = AUTOMATION_NOTES_PREFIX + entry.sha256();
            if (state.appliedSha256().containsKey(entry.sha256()) || imports.hasBatchNotes(notes)) {
                System.out.println("[import] skip already applied: " + entry.filePath().getFileName());
                skipped++;
                continue;
            }

            ImportPreviewResponse preview = imports.previewPath(new ImportPathPreviewRequest(
                    entry.filePath().toString(),
                    entry.accountId(),
                    entry.importerName()));
            int nonDuplicates = (int) preview.rows().stream().filter(row -> !row.isDuplicate()).count();
            previewed++;
            System.out.println("[import] preview %s: rows=%d new=%d duplicates=%d".formatted(
                    entry.filePath().getFileName(),
                    preview.rows().size(),
                    nonDuplicates,
                    preview.rows().size() - nonDuplicates));
            System.out.println("[import] preview report: "
                    + files.writePreviewReport(options.stagingDir(), preview, entry.filePath(), nonDuplicates));

            if (!options.apply()) {
                continue;
            }
            if (!didBackup && !options.noBackup()) {
                BackupResponse backup = backups.createBackup(profiles.getActiveProfile(), "pre-auto-import");
                System.out.println("[import] backup created: " + backup.name());
                didBackup = true;
            }

            ImportBatchResponse batch = imports.apply(new ImportApplyRequest(
                    preview.importerName(),
                    preview.accountId(),
                    preview.sourceFilename(),
                    preview.rows(),
                    true), notes);
            state.appliedSha256().put(entry.sha256(), new AppliedAutomationImport(
                    batch.id(),
                    entry.filePath().toString(),
                    batch.importedAt().toString()));
            imported++;
            System.out.println("[import] applied batch=%d rows=%d duplicates=%d".formatted(
                    batch.id(), batch.rowCountApplied(), batch.rowCountDuplicate()));
        }

        if (options.apply() && imported > 0) {
            files.saveState(options.state(), state);
        }
        return new AutomationImportResult(previewed, imported, skipped);
    }
}
