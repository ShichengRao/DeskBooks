package com.deskbooks.backend.backups;

import java.time.LocalDateTime;

public record BackupResponse(
        String name,
        String profileSlug,
        long sizeBytes,
        LocalDateTime createdAt,
        String path) {
}
