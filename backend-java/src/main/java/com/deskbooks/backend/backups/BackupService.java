package com.deskbooks.backend.backups;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.deskbooks.backend.profiles.AppPaths;
import com.deskbooks.backend.profiles.ProfileInfo;
import org.springframework.stereotype.Service;

@Service
class BackupService {
    private static final Pattern BACKUP_NAME_RE =
            Pattern.compile("^[a-z0-9-]+-\\d{8}-\\d{6}(?:-[a-z0-9-]+)?\\.db$");
    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dataDir;

    BackupService(AppPaths appPaths) {
        this.dataDir = appPaths.dataDir();
    }

    List<BackupResponse> listBackups(ProfileInfo profile) {
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

    BackupResponse createBackup(ProfileInfo profile) {
        return createBackup(profile, null);
    }

    BackupResponse restoreBackup(ProfileInfo profile, String name) throws IOException {
        Path source = resolveBackup(profile, name);
        assertSqliteOk(source);

        Path target = profile.dbPath();
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            createBackup(profile, "pre-restore");
        }

        Path tempTarget = target.resolveSibling("." + target.getFileName() + ".restore-tmp");
        Files.deleteIfExists(tempTarget);
        Files.copy(source, tempTarget, StandardCopyOption.REPLACE_EXISTING);
        assertSqliteOk(tempTarget);

        deleteSidecars(target);
        Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
        deleteSidecars(target);
        return metadata(source, profile.slug());
    }

    BackupResponse deleteBackup(ProfileInfo profile, String name) throws IOException {
        Path path = resolveBackup(profile, name);
        BackupResponse deleted = metadata(path, profile.slug());
        Files.delete(path);
        return deleted;
    }

    private BackupResponse createBackup(ProfileInfo profile, String label) {
        if (!Files.exists(profile.dbPath())) {
            throw new IllegalStateException("active database does not exist: " + profile.dbPath());
        }

        Path root = backupDir(profile.slug());
        Path destination = root.resolve(backupName(profile.slug(), label));
        if (Files.exists(destination)) {
            destination = root.resolve(profile.slug() + "-"
                    + LocalDateTime.now().format(STAMP_FORMAT)
                    + "-" + Long.toUnsignedString(System.nanoTime()) + ".db");
        }

        try {
            Files.createDirectories(root);
            Files.copy(profile.dbPath(), destination, StandardCopyOption.REPLACE_EXISTING);
            assertSqliteOk(destination);
            return metadata(destination, profile.slug());
        } catch (IOException exception) {
            throw new IllegalStateException("could not create backup: " + destination, exception);
        }
    }

    private Path resolveBackup(ProfileInfo profile, String name) throws IOException {
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

    private Path backupDir(String profileSlug) {
        return dataDir.resolve("backups").resolve(profileSlug);
    }

    private String backupName(String profileSlug, String label) {
        String suffix = label == null || label.isBlank() ? "" : "-" + label;
        return profileSlug + "-" + LocalDateTime.now().format(STAMP_FORMAT) + suffix + ".db";
    }

    private BackupResponse metadata(Path path, String profileSlug) {
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

    private void assertSqliteOk(Path path) throws IOException {
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

    private void deleteSidecars(Path dbPath) throws IOException {
        for (Path path : List.of(
                dbPath.resolveSibling(dbPath.getFileName() + "-wal"),
                dbPath.resolveSibling(dbPath.getFileName() + "-shm"),
                dbPath.resolveSibling(dbPath.getFileName() + "-journal"))) {
            Files.deleteIfExists(path);
        }
    }
}
