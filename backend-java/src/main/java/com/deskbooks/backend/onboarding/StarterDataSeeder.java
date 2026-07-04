package com.deskbooks.backend.onboarding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.deskbooks.backend.onboarding.OnboardingService.StarterSeedResult;

final class StarterDataSeeder {
    private static final String TABLE_ACCOUNTS = "accounts";
    private static final String TABLE_CATEGORIES = "categories";
    private static final String KIND_EXPENSE = "expense";
    private static final String KIND_INCOME = "income";
    private static final String KIND_TRANSFER = "transfer";
    private static final String KIND_CC_PAYMENT = "cc_payment";
    private static final String KIND_REFUND = "refund";

    private static final List<StarterAccount> STARTER_ACCOUNTS = List.of(
            new StarterAccount("Checking", "bank", "checking", "outflow_negative", 10),
            new StarterAccount("Savings", "bank", "savings", "outflow_negative", 20),
            new StarterAccount("Credit Card", "credit", "credit_card", "outflow_negative", 30));

    private static final List<StarterCategoryGroup> STARTER_CATEGORIES = List.of(
            new StarterCategoryGroup("Housing", KIND_EXPENSE, List.of("Rent", "Utilities")),
            new StarterCategoryGroup("Food", KIND_EXPENSE, List.of("Groceries", "Restaurants")),
            new StarterCategoryGroup("Transportation", KIND_EXPENSE, List.of()),
            new StarterCategoryGroup("Health", KIND_EXPENSE, List.of()),
            new StarterCategoryGroup("Subscriptions", KIND_EXPENSE, List.of()),
            new StarterCategoryGroup("Misc", KIND_EXPENSE, List.of()),
            new StarterCategoryGroup("Income", KIND_INCOME, List.of("Paycheck", "Other Income")),
            new StarterCategoryGroup("Transfer", KIND_TRANSFER, List.of()),
            new StarterCategoryGroup("Credit Card Payment", KIND_CC_PAYMENT, List.of()),
            new StarterCategoryGroup("Refund", KIND_REFUND, List.of()));

    private final StarterJournalSeeder journal = new StarterJournalSeeder();

    boolean hasStarterDomainData(Connection connection) throws SQLException {
        return hasAnyRow(connection, TABLE_ACCOUNTS) || hasAnyRow(connection, TABLE_CATEGORIES);
    }

    StarterSeedResult seed(Connection connection) throws SQLException {
        int accountsAdded = seedAccounts(connection);
        int categoriesAdded = seedCategories(connection);
        int journalAdded = journal.seed(connection);
        return new StarterSeedResult(accountsAdded, categoriesAdded, journalAdded);
    }

    private int seedAccounts(Connection connection) throws SQLException {
        int accountsAdded = 0;
        for (StarterAccount account : STARTER_ACCOUNTS) {
            if (idByName(connection, TABLE_ACCOUNTS, account.name()) != null) {
                continue;
            }
            insertAccount(connection, account);
            accountsAdded++;
        }
        return accountsAdded;
    }

    private int seedCategories(Connection connection) throws SQLException {
        int categoriesAdded = 0;
        int sortOrder = 0;
        for (StarterCategoryGroup group : STARTER_CATEGORIES) {
            Long groupId = idByName(connection, TABLE_CATEGORIES, group.name());
            if (groupId == null) {
                insertCategory(connection, group.name(), group.kind(), null, sortOrder);
                categoriesAdded++;
            }
            sortOrder++;
        }
        return seedCategoryLeaves(connection, sortOrder, categoriesAdded);
    }

    private int seedCategoryLeaves(Connection connection, int sortOrder, int categoriesAdded)
            throws SQLException {
        for (StarterCategoryGroup group : STARTER_CATEGORIES) {
            Long groupId = idByName(connection, TABLE_CATEGORIES, group.name());
            for (String leaf : group.leaves()) {
                if (idByName(connection, TABLE_CATEGORIES, leaf) != null) {
                    continue;
                }
                insertCategory(connection, leaf, group.kind(), groupId, sortOrder);
                categoriesAdded++;
                sortOrder++;
            }
        }
        return categoriesAdded;
    }

    private void insertAccount(Connection connection, StarterAccount account) throws SQLException {
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
        }
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
