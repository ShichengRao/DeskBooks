package com.deskbooks.backend.onboarding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import org.springframework.stereotype.Service;

@Service
public class OnboardingService {
    private static final List<StarterAccount> STARTER_ACCOUNTS = List.of(
            new StarterAccount("Checking", "bank", "checking", "outflow_negative", 10),
            new StarterAccount("Savings", "bank", "savings", "outflow_negative", 20),
            new StarterAccount("Credit Card", "credit", "credit_card", "outflow_negative", 30));

    private static final List<StarterCategoryGroup> STARTER_CATEGORIES = List.of(
            new StarterCategoryGroup("Housing", "expense", List.of("Rent", "Utilities")),
            new StarterCategoryGroup("Food", "expense", List.of("Groceries", "Restaurants")),
            new StarterCategoryGroup("Transportation", "expense", List.of()),
            new StarterCategoryGroup("Health", "expense", List.of()),
            new StarterCategoryGroup("Subscriptions", "expense", List.of()),
            new StarterCategoryGroup("Misc", "expense", List.of()),
            new StarterCategoryGroup("Income", "income", List.of("Paycheck", "Other Income")),
            new StarterCategoryGroup("Transfer", "transfer", List.of()),
            new StarterCategoryGroup("Credit Card Payment", "cc_payment", List.of()),
            new StarterCategoryGroup("Refund", "refund", List.of()));

    private static final String WELCOME_BODY = """
            This local profile stores its data in a separate SQLite file.

            Add accounts, import transactions, and create snapshots to get started.
            """;

    private final SqliteConnectionProvider connections;

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
            if (hasExistingStarterDomainData(connection)) {
                return new BootstrapResult(true, new StarterSeedResult(0, 0, 0));
            }
            return new BootstrapResult(false, seedStarterData(connection));
        }
    }

    public StarterSeedResult seedStarterData(Connection connection) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            StarterSeedResult result = seedStarterDataInTransaction(connection);
            if (originalAutoCommit) {
                connection.commit();
            }
            return result;
        } catch (SQLException | RuntimeException exception) {
            if (originalAutoCommit) {
                rollback(connection);
            }
            throw exception;
        } finally {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private StarterSeedResult seedStarterDataInTransaction(Connection connection) throws SQLException {
        int accountsAdded = 0;
        int categoriesAdded = 0;
        int journalAdded = 0;

        for (StarterAccount account : STARTER_ACCOUNTS) {
            if (idByName(connection, "accounts", account.name()) != null) {
                continue;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO accounts (
                      name, institution, account_category, type, sign_convention, sort_order
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, account.name());
                statement.setString(2, null);
                statement.setString(3, account.accountCategory());
                statement.setString(4, account.type());
                statement.setString(5, account.signConvention());
                statement.setInt(6, account.sortOrder());
                statement.executeUpdate();
                accountsAdded++;
            }
        }

        int sortOrder = 0;
        for (StarterCategoryGroup group : STARTER_CATEGORIES) {
            Long groupId = idByName(connection, "categories", group.name());
            if (groupId == null) {
                insertCategory(connection, group.name(), group.kind(), null, sortOrder);
                categoriesAdded++;
            }
            sortOrder++;
        }

        for (StarterCategoryGroup group : STARTER_CATEGORIES) {
            Long groupId = idByName(connection, "categories", group.name());
            for (String leaf : group.leaves()) {
                if (idByName(connection, "categories", leaf) != null) {
                    continue;
                }
                insertCategory(connection, leaf, group.kind(), groupId, sortOrder);
                categoriesAdded++;
                sortOrder++;
            }
        }

        if (!hasAnyRow(connection, "journal_entries")) {
            long entryId;
            LocalDate today = LocalDate.now();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO journal_entries (entry_date, title, body_markdown, goal_id)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, today.toString());
                statement.setString(2, "Welcome");
                statement.setString(3, WELCOME_BODY);
                statement.setObject(4, null);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    entryId = keys.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO journal_entry_revisions (
                      entry_id, title, body_markdown, entry_date, goal_id, change_summary
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, entryId);
                statement.setString(2, "Welcome");
                statement.setString(3, WELCOME_BODY);
                statement.setString(4, today.toString());
                statement.setObject(5, null);
                statement.setString(6, "initial starter seed");
                statement.executeUpdate();
            }
            journalAdded = 1;
        }

        return new StarterSeedResult(accountsAdded, categoriesAdded, journalAdded);
    }

    private boolean hasExistingStarterDomainData(Connection connection) throws SQLException {
        return hasAnyRow(connection, "accounts") || hasAnyRow(connection, "categories");
    }

    private boolean hasAnyRow(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " LIMIT 1");
                ResultSet rs = statement.executeQuery()) {
            return rs.next();
        }
    }

    private Long idByName(Connection connection, String table, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    private long insertCategory(Connection connection, String name, String kind, Long parentId, int sortOrder)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO categories (name, parent_id, kind, sort_order)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setObject(2, parentId);
            statement.setString(3, kind);
            statement.setInt(4, sortOrder);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
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

    private record StarterAccount(
            String name,
            String accountCategory,
            String type,
            String signConvention,
            int sortOrder) {
    }

    private record StarterCategoryGroup(
            String name,
            String kind,
            List<String> leaves) {
    }
}
