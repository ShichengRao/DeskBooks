package com.deskbooks.backend.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
final class AutomationImportFiles {
    private final ObjectMapper mapper;

    AutomationImportFiles(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    List<AutomationManifestEntry> readManifest(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<AutomationManifestEntry> entries = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(mapper.readValue(line, AutomationManifestEntry.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                        "invalid manifest JSON on line " + lineNumber + ": " + exception.getOriginalMessage(),
                        exception);
            }
        }
        return entries;
    }

    AutomationImportState loadState(Path path) throws IOException {
        if (!Files.exists(path)) {
            return AutomationImportState.empty();
        }
        return mapper.readValue(path.toFile(), AutomationImportState.class);
    }

    void saveState(Path path, AutomationImportState state) throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), state);
    }

    ValidatedAutomationManifestEntry validate(AutomationManifestEntry entry, Path stagingDir) {
        Path root = stagingDir.toAbsolutePath().normalize();
        Path file = requiredPath(entry.path()).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("refusing file outside staging dir: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("staged file not found: " + file);
        }
        return new ValidatedAutomationManifestEntry(
                entry.source(),
                file,
                entry.accountId(),
                requireText(entry.importerName(), "manifest entry has empty importer_name"),
                requireText(entry.sha256(), "manifest entry has empty sha256"));
    }

    private Path requiredPath(String value) {
        return Path.of(requireText(value, "manifest entry missing field: path"));
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

}
