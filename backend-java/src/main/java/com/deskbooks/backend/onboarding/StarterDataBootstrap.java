package com.deskbooks.backend.onboarding;

import java.sql.SQLException;

import com.deskbooks.backend.onboarding.OnboardingService.BootstrapResult;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class StarterDataBootstrap implements ApplicationRunner {
    private final Environment environment;
    private final OnboardingService onboardingService;

    StarterDataBootstrap(Environment environment, OnboardingService onboardingService) {
        this.environment = environment;
        this.onboardingService = onboardingService;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        if (!enabled()) {
            return;
        }
        BootstrapResult result = onboardingService.bootstrapActiveProfileIfEmpty();
        if (result.starterSeedSkipped()) {
            System.out.println("[bootstrap] starter seed skipped: existing data found");
        } else {
            System.out.println("[bootstrap] starter seed complete: accounts=%d categories=%d journal=%d".formatted(
                    result.result().accountsAdded(),
                    result.result().categoriesAdded(),
                    result.result().journalAdded()));
        }
    }

    private boolean enabled() {
        return truthy(firstNonBlank(
                environment.getProperty("deskbooks.seed-starter-data"),
                environment.getProperty("PFA_SEED_STARTER_DATA"),
                environment.getProperty("pfa.seed.starter.data")));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
