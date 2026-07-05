package com.deskbooks.backend.backups;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.deskbooks.backend.profiles.ProfileInfo;

final class BackupStorage {
    private static final Pattern BACKUP_NAME_RE =
            Pattern.compile("^[a-z0-9-]+-\\d{8}-\\d{6}(?:-[a-z0-9-]+)?\\.db$");
    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dataDir;

    BackupStorage(Path dataDir) {
        this.dataDir = dataDir;
    }

    List<BackupResponse> list(ProfileInfo profile) {
        Path root = backupDir(profile.slug());
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.list(root)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && BACKUP_NAME_RE.matcher(path.getFileName().toString()).matches())
                    .map(path -> metadata(path, profile.slug()))
                    .sorted(Comparator.comparing(BackupResponse::createdAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("could not list backups: " + root, exception);
        }
    }

    Path destination(String profileSlug, String label) {
        Path root = backupDir(profileSlug);
        Path destination = root.resolve(backupName(profileSlug, label));
        if (Files.exists(destination)) {
            return root.resolve(profileSlug + "-"
                    + LocalDateTime.now().format(STAMP_FORMAT)
                    + "-" + Long.toUnsignedString(System.nanoTime()) + ".db");
        }
        return destination;
    }

    Path resolve(ProfileInfo profile, String name) throws IOException {
        if (!BACKUP_NAME_RE.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid backup name");
        }
        Path root = backupDir(profile.slug()).toAbsolutePath().normalize();
        Path path = root.resolve(name).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new FileNotFoundException(name);
        }
        return path;
    }

    BackupResponse metadata(Path path, String profileSlug) {
        try {
            var stat = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            LocalDateTime createdAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(stat.lastModifiedTime().toMillis()),
                    ZoneId.systemDefault());
            return new BackupResponse(
                    path.getFileName().toString(),
                    profileSlug,
                    stat.size(),
                    createdAt,
                    path.toString());
        } catch (IOException exception) {
            throw new IllegalStateException("could not read backup metadata: " + path, exception);
        }
    }

    void assertSqliteOk(Path path) throws IOException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement();
                var rs = statement.executeQuery("PRAGMA integrity_check")) {
            if (!rs.next() || !"ok".equals(rs.getString(1))) {
                throw new IOException("backup failed SQLite integrity check: " + path);
            }
        } catch (SQLException exception) {
            throw new IOException("backup failed SQLite integrity check: " + path, exception);
        }
    }

    void deleteSidecars(Path dbPath) throws IOException {
        for (Path path : List.of(
                dbPath.resolveSibling(dbPath.getFileName() + "-wal"),
                dbPath.resolveSibling(dbPath.getFileName() + "-shm"),
                dbPath.resolveSibling(dbPath.getFileName() + "-journal"))) {
            Files.deleteIfExists(path);
        }
    }

    private Path backupDir(String profileSlug) {
        return dataDir.resolve("backups").resolve(profileSlug);
    }

    private String backupName(String profileSlug, String label) {
        String suffix = label == null || label.isBlank() ? "" : "-" + label;
        return profileSlug + "-" + LocalDateTime.now().format(STAMP_FORMAT) + suffix + ".db";
    }
}
