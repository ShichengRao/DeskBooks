package com.deskbooks.backend.imports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.deskbooks.backend.profiles.AppPaths;
import org.springframework.core.env.Environment;

record AutomationImportOptions(
        Path manifest,
        Path stagingDir,
        Path state,
        boolean apply,
        String source,
        boolean noBackup) {

    static AutomationImportOptions from(Environment environment, AppPaths paths) {
        Path stagingDir = configuredPath(
                environment,
                "DESKBOOKS_IMPORT_STAGING_DIR",
                paths.dataDir().resolve("import-staging"));
        Path latestManifest = stagingDir.resolve("latest-manifest.jsonl");
        Path defaultManifest = Files.isRegularFile(latestManifest)
                ? latestManifest
                : stagingDir.resolve("manifest.jsonl");
        return new AutomationImportOptions(
                configuredPath(environment, "DESKBOOKS_IMPORT_MANIFEST", defaultManifest),
                stagingDir,
                configuredPath(environment, "DESKBOOKS_IMPORT_STATE", stagingDir.resolve("import-state.json")),
                truthy(environment.getProperty("DESKBOOKS_IMPORT_APPLY")),
                blankToNull(environment.getProperty("DESKBOOKS_IMPORT_SOURCE")),
                truthy(environment.getProperty("DESKBOOKS_IMPORT_NO_BACKUP")));
    }

    private static Path configuredPath(Environment environment, String property, Path fallback) {
        String value = environment.getProperty(property);
        return value == null || value.isBlank() ? fallback : Path.of(value).toAbsolutePath().normalize();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}

record AutomationManifestEntry(
        String source,
        String path,
        long accountId,
        String importerName,
        String sha256) {
}

record ValidatedAutomationManifestEntry(
        String source,
        Path filePath,
        long accountId,
        String importerName,
        String sha256) {
}

record AppliedAutomationImport(long batchId, String path, String importedAt) {
}

record AutomationImportState(Map<String, AppliedAutomationImport> appliedSha256) {
    AutomationImportState {
        appliedSha256 = appliedSha256 == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(appliedSha256);
    }

    static AutomationImportState empty() {
        return new AutomationImportState(new LinkedHashMap<>());
    }
}

record AutomationImportResult(int previewed, int imported, int skipped) {
}
