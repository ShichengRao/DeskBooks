package com.deskbooks.backend.profiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProfileRegistry {
    private static final int MINIMUM_REMAINING_PROFILES = 1;

    private final ProfileRegistryStore store;
    private final ProfilePaths paths;
    private final ProfileNaming naming;
    private final ProfileDatabaseFiles files = new ProfileDatabaseFiles();

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
        store = new ProfileRegistryStore(dataDir, defaultDbFile, objectMapper);
        paths = new ProfilePaths(dataDir, defaultDbFile, explicitProfileOverride);
        naming = new ProfileNaming(paths);
    }

    public synchronized List<ProfileInfo> listProfiles() {
        Registry registry = store.read();
        return registry.profiles().stream()
                .map(row -> paths.profileFromRow(row, registry.active()))
                .toList();
    }

    public synchronized ProfileInfo getActiveProfile() {
        Registry registry = store.read();
        String activeSlug = registry.active();
        for (RegistryProfile row : registry.profiles()) {
            if (row.slug().equals(activeSlug)) {
                return paths.profileFromRow(row, activeSlug);
            }
        }

        RegistryProfile fallback = registry.profiles().getFirst();
        store.write(new Registry(fallback.slug(), registry.profiles()));
        return paths.profileFromRow(fallback, fallback.slug());
    }

    public synchronized ProfileInfo createProfile(String name) {
        Registry registry = store.read();
        RegistryProfile row = naming.newProfile(registry, name);
        List<RegistryProfile> profiles = new ArrayList<>(registry.profiles());
        profiles.add(row);
        store.write(new Registry(registry.active(), profiles));
        return paths.profileFromRow(row, registry.active());
    }

    public synchronized ProfileInfo duplicateProfile(String name, String sourceSlug) {
        Registry registry = store.read();
        String activeSlug = registry.active();
        String requestedSource = sourceSlug == null || sourceSlug.isBlank() ? activeSlug : sourceSlug;
        RegistryProfile source = registry.profiles().stream()
                .filter(row -> row.slug().equals(requestedSource))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(requestedSource));

        RegistryProfile row = naming.newProfile(registry, name);
        files.copyOrCreate(paths.resolveDbPath(source.dbFile()), paths.resolveDbPath(row.dbFile()));

        List<RegistryProfile> profiles = new ArrayList<>(registry.profiles());
        profiles.add(row);
        store.write(new Registry(row.slug(), profiles));
        return paths.profileFromRow(row, row.slug());
    }

    public synchronized ProfileInfo setActiveProfile(String slug) {
        Registry registry = store.read();
        for (RegistryProfile row : registry.profiles()) {
            if (row.slug().equals(slug)) {
                store.write(new Registry(slug, registry.profiles()));
                return paths.profileFromRow(row, slug);
            }
        }
        throw new NoSuchElementException(slug);
    }

    public synchronized ProfileInfo deleteProfile(String slug) {
        Registry registry = store.read();
        if (registry.profiles().size() <= MINIMUM_REMAINING_PROFILES) {
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
        ProfileInfo deletedProfile = paths.profileFromRow(deleted, registry.active());
        store.write(new Registry(activeSlug, remaining));

        Set<Path> remainingPaths = remaining.stream()
                .map(row -> paths.resolveDbPath(row.dbFile()))
                .collect(java.util.stream.Collectors.toSet());
        if (!remainingPaths.contains(deletedProfile.dbPath())) {
            files.deleteProfileFiles(deletedProfile.dbPath());
        }
        return deletedProfile;
    }
}
