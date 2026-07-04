package com.deskbooks.backend.onboarding;

import java.sql.Connection;
import java.sql.SQLException;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import org.springframework.stereotype.Service;

@Service
public class OnboardingService {
    private final SqliteConnectionProvider connections;
    private final StarterDataSeeder seeder = new StarterDataSeeder();

    public OnboardingService(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    public StarterSeedResult seedActiveProfile() throws SQLException {
        try (Connection connection = connections.open()) {
            return seedStarterData(connection);
        }
    }

    public BootstrapResult bootstrapActiveProfileIfEmpty() throws SQLException {
        try (Connection connection = connections.open()) {
            if (seeder.hasStarterDomainData(connection)) {
                return new BootstrapResult(true, new StarterSeedResult(0, 0, 0));
            }
            return new BootstrapResult(false, seedStarterData(connection));
        }
    }

    public StarterSeedResult seedStarterData(Connection connection) throws SQLException {
        try (StarterSeedTransaction transaction = StarterSeedTransaction.begin(connection)) {
            StarterSeedResult result = seeder.seed(connection);
            transaction.commit();
            return result;
        }
    }

    public record StarterSeedResult(
            int accountsAdded,
            int categoriesAdded,
            int journalAdded) {
    }

    public record BootstrapResult(
            boolean starterSeedSkipped,
            StarterSeedResult result) {
    }

    private static final class StarterSeedTransaction implements AutoCloseable {
        private final Connection connection;
        private final boolean originalAutoCommit;
        private boolean committed;

        private StarterSeedTransaction(Connection connection, boolean originalAutoCommit) {
            this.connection = connection;
            this.originalAutoCommit = originalAutoCommit;
        }

        static StarterSeedTransaction begin(Connection connection) throws SQLException {
            boolean originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            return new StarterSeedTransaction(connection, originalAutoCommit);
        }

        void commit() throws SQLException {
            if (originalAutoCommit) {
                connection.commit();
            }
            committed = true;
        }

        @Override
        public void close() throws SQLException {
            if (!originalAutoCommit) {
                return;
            }
            if (!committed) {
                rollbackQuietly();
            }
            connection.setAutoCommit(true);
        }

        private void rollbackQuietly() {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
        }
    }
}
