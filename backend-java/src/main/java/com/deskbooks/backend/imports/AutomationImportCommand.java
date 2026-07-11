package com.deskbooks.backend.imports;

import com.deskbooks.backend.profiles.AppPaths;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "deskbooks.command", havingValue = "automation-import")
final class AutomationImportCommand implements ApplicationRunner {
    private final Environment environment;
    private final AppPaths paths;
    private final AutomationImportService imports;

    AutomationImportCommand(Environment environment, AppPaths paths, AutomationImportService imports) {
        this.environment = environment;
        this.paths = paths;
        this.imports = imports;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        imports.run(AutomationImportOptions.from(environment, paths));
    }
}
