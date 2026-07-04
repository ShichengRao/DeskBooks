package com.deskbooks.backend.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class ProfileRegistryStore {
    private final Path dataDir;
    private final Path registryPath;
    private final ObjectMapper mapper;
    private final String initialDbFile;

    ProfileRegistryStore(Path dataDir, String initialDbFile, ObjectMapper objectMapper) {
        this.dataDir = dataDir;
        this.registryPath = dataDir.resolve("profiles.json");
        this.initialDbFile = initialDbFile;
        this.mapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    Registry read() {
        ensure();
        try {
            Registry registry = mapper.readValue(registryPath.toFile(), Registry.class);
            if (registry.profiles() == null || registry.profiles().isEmpty()) {
                throw new IllegalStateException("profile registry has no profiles: " + registryPath);
            }
            return registry;
        } catch (IOException exception) {
            throw new IllegalStateException("profile registry is invalid: " + registryPath, exception);
        }
    }

    void write(Registry registry) {
        try {
            Files.createDirectories(dataDir);
            mapper.writeValue(registryPath.toFile(), registry);
            Files.writeString(registryPath, "\n", StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("could not write profile registry: " + registryPath, exception);
        }
    }

    private void ensure() {
        if (Files.exists(registryPath)) {
            return;
        }
        write(new Registry(
                "personal",
                List.of(new RegistryProfile("personal", "Personal", initialDbFile))));
    }
}
