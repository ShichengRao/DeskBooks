package com.deskbooks.backend.backups;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.deskbooks.backend.profiles.AppPaths;
import com.deskbooks.backend.profiles.ProfileInfo;
import org.springframework.stereotype.Service;

@Service
class BackupService {
    private final BackupStorage storage;

    BackupService(AppPaths appPaths) {
        this.storage = new BackupStorage(appPaths.dataDir());
    }

    List<BackupResponse> listBackups(ProfileInfo profile) {
        return storage.list(profile);
    }

    BackupResponse createBackup(ProfileInfo profile) {
        return createBackup(profile, null);
    }

    BackupResponse restoreBackup(ProfileInfo profile, String name) throws IOException {
        Path source = storage.resolve(profile, name);
        storage.assertSqliteOk(source);

        Path target = profile.dbPath();
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            createBackup(profile, "pre-restore");
        }

        Path tempTarget = target.resolveSibling("." + target.getFileName() + ".restore-tmp");
        Files.deleteIfExists(tempTarget);
        Files.copy(source, tempTarget, StandardCopyOption.REPLACE_EXISTING);
        storage.assertSqliteOk(tempTarget);

        storage.deleteSidecars(target);
        Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
        storage.deleteSidecars(target);
        return storage.metadata(source, profile.slug());
    }

    BackupResponse deleteBackup(ProfileInfo profile, String name) throws IOException {
        Path path = storage.resolve(profile, name);
        BackupResponse deleted = storage.metadata(path, profile.slug());
        Files.delete(path);
        return deleted;
    }

    private BackupResponse createBackup(ProfileInfo profile, String label) {
        if (!Files.exists(profile.dbPath())) {
            throw new IllegalStateException("active database does not exist: " + profile.dbPath());
        }

        Path destination = storage.destination(profile.slug(), label);
        try {
            Files.createDirectories(destination.getParent());
            Files.copy(profile.dbPath(), destination, StandardCopyOption.REPLACE_EXISTING);
            storage.assertSqliteOk(destination);
            return storage.metadata(destination, profile.slug());
        } catch (IOException exception) {
            throw new IllegalStateException("could not create backup: " + destination, exception);
        }
    }
}
