package com.deskbooks.backend.backups;

import java.sql.Connection;
import java.util.List;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.profiles.ProfileInfo;
import com.deskbooks.backend.profiles.ProfileRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backups")
class BackupController {
    private final ProfileRegistry profiles;
    private final BackupService backups;
    private final SqliteConnectionProvider connections;

    BackupController(ProfileRegistry profiles, BackupService backups, SqliteConnectionProvider connections) {
        this.profiles = profiles;
        this.backups = backups;
        this.connections = connections;
    }

    @GetMapping("")
    BackupListResponse listBackups() {
        ProfileInfo profile = profiles.getActiveProfile();
        return new BackupListResponse(profile.slug(), backups.listBackups(profile));
    }

    @PostMapping("")
    BackupResponse createBackup() {
        ProfileInfo profile = profiles.getActiveProfile();
        try (Connection ignored = connections.open()) {
            return backups.createBackup(profile);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    @PostMapping("/{name}/restore")
    BackupResponse restoreBackup(@PathVariable String name) {
        ProfileInfo profile = profiles.getActiveProfile();
        try {
            BackupResponse restored = backups.restoreBackup(profile, name);
            try (Connection ignored = connections.open()) {
                return restored;
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (java.io.FileNotFoundException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "backup not found");
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    BackupResponse deleteBackup(@PathVariable String name) {
        ProfileInfo profile = profiles.getActiveProfile();
        try {
            return backups.deleteBackup(profile, name);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (java.io.FileNotFoundException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "backup not found");
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    record BackupListResponse(String profileSlug, List<BackupResponse> backups) {
    }
}
