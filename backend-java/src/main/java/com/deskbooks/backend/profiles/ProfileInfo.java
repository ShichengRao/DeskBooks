package com.deskbooks.backend.profiles;

import java.nio.file.Path;

public record ProfileInfo(
        String slug,
        String name,
        String dbFile,
        Path dbPath,
        boolean isActive) {
}
