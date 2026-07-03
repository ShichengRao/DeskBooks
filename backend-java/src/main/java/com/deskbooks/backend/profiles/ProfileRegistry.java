package com.deskbooks.backend.profiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProfileRegistry {
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    private final Path dataDir;
    private final Path registryPath;
    private final String defaultDbFile;
    private final boolean explicitProfileOverride;
    private final ObjectMapper mapper;

    @Autowired
    public ProfileRegistry(AppPaths appPaths, ObjectMapper objectMapper) {
        this(
                appPaths.dataDir(),
                appPaths.defaultDbFile(),
                appPaths.hasExplicitProfileOverride(),
                objectMapper);
    }

    ProfileRegistry(
            Path dataDir,
            String defaultDbFile,
            boolean explicitProfileOverride,
            ObjectMapper objectMapper) {
        this.dataDir = dataDir;
        this.registryPath = dataDir.resolve("profiles.json");
        this.defaultDbFile = defaultDbFile;
        this.explicitProfileOverride = explicitProfileOverride;
        this.mapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public synchronized List<ProfileInfo> listProfiles() {
        Registry registry = readRegistry();
        return registry.profiles().stream()
                .map(row -> profileFromRow(row, registry.active()))
                .toList();
    }

    public synchronized ProfileInfo getActiveProfile() {
        Registry registry = readRegistry();
        String activeSlug = registry.active();
        for (RegistryProfile row : registry.profiles()) {
            if (row.slug().equals(activeSlug)) {
                return profileFromRow(row, activeSlug);
            }
        }

        RegistryProfile fallback = registry.profiles().getFirst();
        writeRegistry(new Registry(fallback.slug(), registry.profiles()));
        return profileFromRow(fallback, fallback.slug());
    }

    public synchronized ProfileInfo createProfile(String name) {
        Registry registry = readRegistry();
        String slug = uniqueSlug(registry, name);
        RegistryProfile row = new RegistryProfile(slug, normalizedName(name, slug), defaultDbFile(slug));
        List<RegistryProfile> profiles = new ArrayList<>(registry.profiles());
        profiles.add(row);
        writeRegistry(new Registry(registry.active(), profiles));
        return profileFromRow(row, registry.active());
    }

    public synchronized ProfileInfo duplicateProfile(String name, String sourceSlug) {
        Registry registry = readRegistry();
        String activeSlug = registry.active();
        String requestedSource = sourceSlug == null || sourceSlug.isBlank() ? activeSlug : sourceSlug;
        RegistryProfile source = registry.profiles().stream()
                .filter(row -> row.slug().equals(requestedSource))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(requestedSource));

        String slug = uniqueSlug(registry, name);
        RegistryProfile row = new RegistryProfile(slug, normalizedName(name, slug), defaultDbFile(slug));
        Path sourcePath = resolveDbPath(source.dbFile());
        Path targetPath = resolveDbPath(row.dbFile());
        copyOrCreateProfileDatabase(sourcePath, targetPath);

        List<RegistryProfile> profiles = new ArrayList<>(registry.profiles());
        profiles.add(row);
        writeRegistry(new Registry(slug, profiles));
        return profileFromRow(row, slug);
    }

    public synchronized ProfileInfo setActiveProfile(String slug) {
        Registry registry = readRegistry();
        for (RegistryProfile row : registry.profiles()) {
            if (row.slug().equals(slug)) {
                writeRegistry(new Registry(slug, registry.profiles()));
                return profileFromRow(row, slug);
            }
        }
        throw new NoSuchElementException(slug);
    }

    public synchronized ProfileInfo deleteProfile(String slug) {
        Registry registry = readRegistry();
        if (registry.profiles().size() <= 1) {
            throw new IllegalArgumentException("cannot delete the only profile");
        }

        RegistryProfile deleted = registry.profiles().stream()
                .filter(row -> row.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(slug));

        List<RegistryProfile> remaining = registry.profiles().stream()
                .filter(row -> !row.slug().equals(slug))
                .toList();
        String activeSlug = registry.active().equals(slug) ? remaining.getFirst().slug() : registry.active();
        ProfileInfo deletedProfile = profileFromRow(deleted, registry.active());
        writeRegistry(new Registry(activeSlug, remaining));

        Set<Path> remainingPaths = remaining.stream()
                .map(row -> resolveDbPath(row.dbFile()))
                .collect(java.util.stream.Collectors.toSet());
        if (!remainingPaths.contains(deletedProfile.dbPath())) {
            deleteProfileFiles(deletedProfile.dbPath());
        }
        return deletedProfile;
    }

    private void ensureProfileRegistry() {
        if (Files.exists(registryPath)) {
            return;
        }
        writeRegistry(new Registry(
                "personal",
                List.of(new RegistryProfile("personal", "Personal", defaultDbFile))));
    }

    private Registry readRegistry() {
        ensureProfileRegistry();
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

    private void writeRegistry(Registry registry) {
        try {
            Files.createDirectories(dataDir);
            mapper.writeValue(registryPath.toFile(), registry);
            Files.writeString(registryPath, "\n", StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("could not write profile registry: " + registryPath, exception);
        }
    }

    private ProfileInfo profileFromRow(RegistryProfile row, String activeSlug) {
        return new ProfileInfo(
                row.slug(),
                row.name(),
                row.dbFile(),
                resolveDbPath(row.dbFile()),
                row.slug().equals(activeSlug));
    }

    private Path resolveDbPath(String dbFile) {
        Path raw = Path.of(dbFile);
        Path path = raw.isAbsolute() ? raw : dataDir.resolve(raw);
        return path.toAbsolutePath().normalize();
    }

    private String defaultDbFile(String slug) {
        if (slug.equals("personal") && !explicitProfileOverride) {
            return defaultDbFile;
        }
        return Path.of("profiles").resolve(slug + ".db").toString();
    }

    private String uniqueSlug(Registry registry, String name) {
        String baseSlug = slugifyProfileName(name);
        Set<String> existing = registry.profiles().stream()
                .map(RegistryProfile::slug)
                .collect(java.util.stream.Collectors.toSet());
        String slug = baseSlug;
        int suffix = 2;
        while (existing.contains(slug)) {
            slug = baseSlug + "-" + suffix;
            suffix++;
        }
        return slug;
    }

    private String slugifyProfileName(String name) {
        String slug = NON_SLUG_CHARS.matcher(name.trim().toLowerCase()).replaceAll("-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return slug.isBlank() ? "profile" : slug;
    }

    private String normalizedName(String name, String fallback) {
        String trimmed = name.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private void copyOrCreateProfileDatabase(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(source)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createFile(target);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("could not duplicate profile database: " + source, exception);
        }
    }

    private void deleteProfileFiles(Path dbPath) {
        for (Path path : List.of(
                dbPath,
                dbPath.resolveSibling(dbPath.getFileName() + "-wal"),
                dbPath.resolveSibling(dbPath.getFileName() + "-shm"),
                dbPath.resolveSibling(dbPath.getFileName() + "-journal"))) {
            try {
                Files.delete(path);
            } catch (NoSuchFileException ignored) {
            } catch (IOException exception) {
                throw new IllegalStateException("could not delete profile database file: " + path, exception);
            }
        }
    }

    record Registry(String active, List<RegistryProfile> profiles) {
    }

    record RegistryProfile(String slug, String name, @JsonProperty("db_file") String dbFile) {
    }
}
