package com.deskbooks.backend.profiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class ProfileDatabaseFiles {
    void copyOrCreate(Path source, Path target) {
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

    void deleteProfileFiles(Path dbPath) {
        for (Path path : sidecarPaths(dbPath)) {
            deleteProfileFile(path);
        }
    }

    private List<Path> sidecarPaths(Path dbPath) {
        return List.of(
                dbPath,
                dbPath.resolveSibling(dbPath.getFileName() + "-wal"),
                dbPath.resolveSibling(dbPath.getFileName() + "-shm"),
                dbPath.resolveSibling(dbPath.getFileName() + "-journal"));
    }

    private void deleteProfileFile(Path path) {
        try {
            Files.delete(path);
        } catch (NoSuchFileException ignored) {
        } catch (IOException exception) {
            throw new IllegalStateException("could not delete profile database file: " + path, exception);
        }
    }
}
