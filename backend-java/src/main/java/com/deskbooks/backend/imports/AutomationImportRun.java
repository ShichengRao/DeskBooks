package com.deskbooks.backend.imports;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.deskbooks.backend.backups.BackupResponse;
import com.deskbooks.backend.backups.BackupService;
import com.deskbooks.backend.profiles.ProfileRegistry;

final class AutomationImportRun {
    private static final String AUTOMATION_NOTES_PREFIX = "automation_sha256=";

    private final AutomationImportOptions options;
    private final AutomationImportState state;
    private final ImportEndpointService imports;
    private final ProfileRegistry profiles;
    private final BackupService backups;
    private final AutomationImportFiles files;
    private final AutomationImportReportWriter reports;
    private final Set<String> seenHashes = new HashSet<>();

    private boolean didBackup;
    private int previewed;
    private int imported;
    private int skipped;

    AutomationImportRun(
            AutomationImportOptions options,
            AutomationImportState state,
            ImportEndpointService imports,
            ProfileRegistry profiles,
            BackupService backups,
            AutomationImportFiles files,
            AutomationImportReportWriter reports) {
        this.options = options;
        this.state = state;
        this.imports = imports;
        this.profiles = profiles;
        this.backups = backups;
        this.files = files;
        this.reports = reports;
    }

    void process(AutomationManifestEntry rawEntry) throws IOException {
        ValidatedAutomationManifestEntry entry = files.validate(rawEntry, options.stagingDir());
        if (duplicateManifestEntry(entry) || alreadyApplied(entry)) {
            skipped++;
            return;
        }
        ImportPreviewResponse preview = preview(entry);
        apply(entry, preview);
    }

    AutomationImportResult finish() throws IOException {
        if (options.apply() && imported > 0) {
            files.saveState(options.state(), state);
        }
        return new AutomationImportResult(previewed, imported, skipped);
    }

    private boolean duplicateManifestEntry(ValidatedAutomationManifestEntry entry) {
        if (seenHashes.add(entry.sha256())) {
            return false;
        }
        System.out.println("[import] skip duplicate manifest entry: " + entry.filePath().getFileName());
        return true;
    }

    private boolean alreadyApplied(ValidatedAutomationManifestEntry entry) {
        String notes = notes(entry);
        if (!state.appliedSha256().containsKey(entry.sha256()) && !imports.hasBatchNotes(notes)) {
            return false;
        }
        System.out.println("[import] skip already applied: " + entry.filePath().getFileName());
        return true;
    }

    private ImportPreviewResponse preview(ValidatedAutomationManifestEntry entry) throws IOException {
        ImportPreviewResponse preview = imports.previewPath(new ImportPathPreviewRequest(
                entry.filePath().toString(), entry.accountId(), entry.importerName()));
        int nonDuplicates = (int) preview.rows().stream().filter(row -> !row.isDuplicate()).count();
        previewed++;
        System.out.println("[import] preview %s: rows=%d new=%d duplicates=%d".formatted(
                entry.filePath().getFileName(),
                preview.rows().size(),
                nonDuplicates,
                preview.rows().size() - nonDuplicates));
        System.out.println("[import] preview report: "
                + reports.write(options.stagingDir(), preview, entry.filePath(), nonDuplicates));
        return preview;
    }

    private void apply(ValidatedAutomationManifestEntry entry, ImportPreviewResponse preview) {
        if (!options.apply()) {
            return;
        }
        backupIfNeeded();
        ImportBatchResponse batch = imports.apply(new ImportApplyRequest(
                preview.importerName(),
                preview.accountId(),
                preview.sourceFilename(),
                preview.rows(),
                true), notes(entry));
        state.appliedSha256().put(entry.sha256(), new AppliedAutomationImport(
                batch.id(), entry.filePath().toString(), batch.importedAt().toString()));
        imported++;
        System.out.println("[import] applied batch=%d rows=%d duplicates=%d".formatted(
                batch.id(), batch.rowCountApplied(), batch.rowCountDuplicate()));
    }

    private void backupIfNeeded() {
        if (didBackup || options.noBackup()) {
            return;
        }
        BackupResponse backup = backups.createBackup(profiles.getActiveProfile(), "pre-auto-import");
        System.out.println("[import] backup created: " + backup.name());
        didBackup = true;
    }

    private String notes(ValidatedAutomationManifestEntry entry) {
        return AUTOMATION_NOTES_PREFIX + entry.sha256();
    }
}
