package com.deskbooks.backend.profiles;

import java.nio.file.Path;
import java.util.Locale;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AppPaths {
    private static final String APP_NAME = "DeskBooks";
    private static final String APP_DIR_NAME = "deskbooks";

    private final Environment environment;

    public AppPaths(Environment environment) {
        this.environment = environment;
    }

    public Path dataDir() {
        String override = firstNonBlank(
                environment.getProperty("deskbooks.data-dir"),
                environment.getProperty("PFA_DATA_DIR"),
                environment.getProperty("pfa.data.dir"));
        if (override != null) {
            return expandUser(override);
        }

        Path home = Path.of(System.getProperty("user.home"));
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve(APP_NAME);
        }
        if (osName.contains("win")) {
            String appData = environment.getProperty("APPDATA");
            Path base = appData == null || appData.isBlank()
                    ? home.resolve("AppData").resolve("Roaming")
                    : Path.of(appData);
            return base.resolve(APP_NAME);
        }

        String xdgDataHome = environment.getProperty("XDG_DATA_HOME");
        Path base = xdgDataHome == null || xdgDataHome.isBlank()
                ? home.resolve(".local").resolve("share")
                : expandUser(xdgDataHome);
        return base.resolve(APP_DIR_NAME);
    }

    String defaultDbFile() {
        String configured = firstNonBlank(
                environment.getProperty("deskbooks.db-file"),
                environment.getProperty("PFA_DB_FILE"),
                environment.getProperty("pfa.db.file"));
        return configured == null ? "app.db" : configured;
    }

    boolean hasExplicitProfileOverride() {
        return firstNonBlank(
                environment.getProperty("deskbooks.profile"),
                environment.getProperty("PFA_PROFILE"),
                environment.getProperty("pfa.profile")) != null;
    }

    private Path expandUser(String raw) {
        if (raw.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (raw.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(raw.substring(2));
        }
        return Path.of(raw);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
