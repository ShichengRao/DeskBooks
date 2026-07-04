package com.deskbooks.backend.planning;

import java.sql.Connection;
import java.sql.SQLException;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/fire")
class FireController {
    private final SqliteConnectionProvider connections;
    private final FireSettingsStore settings = new FireSettingsStore();
    private final FireProjectionService projections = new FireProjectionService();

    FireController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("/settings")
    FireSettingsResponse getSettings() {
        try (Connection connection = connections.open()) {
            settings.ensure(connection);
            return settings.get(connection);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/settings")
    FireSettingsResponse putSettings(@Valid @RequestBody FireSettingsRequest body) {
        try (Connection connection = connections.open()) {
            settings.ensure(connection);
            return settings.update(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/projection")
    FireProjectionResponse projection(@RequestParam(name = "max_years", defaultValue = "60") int maxYears) {
        try (Connection connection = connections.open()) {
            settings.ensure(connection);
            return projections.project(connection, settings.get(connection), maxYears);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }
}
