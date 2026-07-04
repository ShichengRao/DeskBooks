package com.deskbooks.backend.profiles;

import java.nio.file.Path;

final class ProfilePaths {
    private final Path dataDir;
    private final String initialDbFile;
    private final boolean explicitProfileOverride;

    ProfilePaths(Path dataDir, String initialDbFile, boolean explicitProfileOverride) {
        this.dataDir = dataDir;
        this.initialDbFile = initialDbFile;
        this.explicitProfileOverride = explicitProfileOverride;
    }

    ProfileInfo profileFromRow(RegistryProfile row, String activeSlug) {
        return new ProfileInfo(
                row.slug(),
                row.name(),
                row.dbFile(),
                resolveDbPath(row.dbFile()),
                row.slug().equals(activeSlug));
    }

    Path resolveDbPath(String dbFile) {
        Path raw = Path.of(dbFile);
        Path path = raw.isAbsolute() ? raw : dataDir.resolve(raw);
        return path.toAbsolutePath().normalize();
    }

    String dbFileForSlug(String slug) {
        if (slug.equals("personal") && !explicitProfileOverride) {
            return initialDbFile;
        }
        return Path.of("profiles").resolve(slug + ".db").toString();
    }
}
