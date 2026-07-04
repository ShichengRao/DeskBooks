package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SankeyAnalytics {
    private static final int SNAPSHOT_BRACKET_DAYS = 60;

    private SankeyAnalytics() {
    }

    static AnalyticsController.SankeyResponse load(
            Connection connection,
            LocalDate start,
            LocalDate end,
            String label) throws SQLException {
        Map<Long, CategoryGroup> groupMap = categoryGroupMap(connection);
        SankeyTransactionRollup transactions = collectSankeyTransactions(connection, start, end, groupMap);
        SankeySnapshotRollup snapshots = collectSankeySnapshotDeltas(connection, start, end);
        return SankeyGraphBuilder.build(start, label, transactions, snapshots);
    }

    private static Map<Long, CategoryGroup> categoryGroupMap(Connection connection) throws SQLException {
        Map<Long, CategoryRow> categories = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, parent_id
                FROM categories
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Long parentId = nullableLong(rs, "parent_id");
                categories.put(rs.getLong("id"), new CategoryRow(rs.getLong("id"), rs.getString("name"), parentId));
            }
        }

        Map<Long, CategoryGroup> out = new HashMap<>();
        for (CategoryRow category : categories.values()) {
            CategoryRow parent = category.parentId() == null ? null : categories.get(category.parentId());
            out.put(category.id(), new CategoryGroup(category.name(), parent == null ? category.name() : parent.name()));
        }
        return out;
    }

    private static SankeyTransactionRollup collectSankeyTransactions(
            Connection connection,
            LocalDate start,
            LocalDate end,
            Map<Long, CategoryGroup> groupMap) throws SQLException {
        Map<String, BigDecimal> incomeLeaves = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> expenses = new LinkedHashMap<>();
        BigDecimal donationsTotal = BigDecimal.ZERO;
        BigDecimal taxesTotal = BigDecimal.ZERO;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.kind, t.amount, s.personal_share, t.merchant, t.category_id
                FROM transactions t
                LEFT JOIN transaction_splits s ON s.transaction_id = t.id
                WHERE t.date >= ?
                  AND t.date <= ?
                  AND t.is_excluded_from_totals = 0
                """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    BigDecimal amount = effectiveAmount(rs.getBigDecimal("amount"), rs.getBigDecimal("personal_share"));
                    Long categoryId = nullableLong(rs, "category_id");
                    CategoryGroup categoryGroup = categoryId == null ? null : groupMap.get(categoryId);
                    String leaf = firstNonBlank(categoryGroup == null ? null : categoryGroup.leaf(), rs.getString("merchant"), "(uncategorized)");
                    String group = firstNonBlank(categoryGroup == null ? null : categoryGroup.group(), "(Uncategorized)");
                    String kind = rs.getString("kind");
                    if ("income".equals(kind)) {
                        incomeLeaves.merge(leaf, amount, BigDecimal::add);
                    } else if ("expense".equals(kind)) {
                        expenses.computeIfAbsent(group, ignored -> new LinkedHashMap<>())
                                .merge(leaf, amount.negate(), BigDecimal::add);
                    } else if ("donation".equals(kind)) {
                        donationsTotal = donationsTotal.add(amount.negate());
                    } else if ("tax".equals(kind)) {
                        taxesTotal = taxesTotal.add(amount.negate());
                    }
                }
            }
        }

        return new SankeyTransactionRollup(incomeLeaves, expenses, donationsTotal, taxesTotal);
    }

    private static SankeySnapshotRollup collectSankeySnapshotDeltas(
            Connection connection,
            LocalDate start,
            LocalDate end) throws SQLException {
        SnapshotRef startSnapshot = bracketingStartSnapshot(connection, start);
        SnapshotRef endSnapshot = bracketingEndSnapshot(connection, end.plusDays(1));
        Map<Long, BigDecimal> startBalances = snapshotBalances(connection, startSnapshot);
        Map<Long, BigDecimal> endBalances = snapshotBalances(connection, endSnapshot);

        Map<String, BigDecimal> deltaByBucket = new LinkedHashMap<>();
        Map<String, BigDecimal> positiveDeltaByGrowthSource = new LinkedHashMap<>();
        BigDecimal totalAccountDelta = BigDecimal.ZERO;
        for (AccountRow account : accounts(connection)) {
            if ("credit".equals(account.accountCategory()) || "liability".equals(account.accountCategory())) {
                continue;
            }
            BigDecimal startBalance = startBalances.getOrDefault(account.id(), BigDecimal.ZERO);
            BigDecimal endBalance = endBalances.getOrDefault(account.id(), BigDecimal.ZERO);
            BigDecimal delta = endBalance.subtract(startBalance);
            deltaByBucket.merge(deltaBucketForAccount(account), delta, BigDecimal::add);
            totalAccountDelta = totalAccountDelta.add(delta);
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                positiveDeltaByGrowthSource.merge(growthBucketForAccount(account), delta, BigDecimal::add);
            }
        }

        return new SankeySnapshotRollup(
                startSnapshot,
                endSnapshot,
                deltaByBucket,
                positiveDeltaByGrowthSource,
                totalAccountDelta);
    }

    private static SnapshotRef bracketingStartSnapshot(Connection connection, LocalDate start) throws SQLException {
        SnapshotRef snapshot = nearestSnapshot(connection, start);
        return snapshot == null ? firstSnapshot(connection) : snapshot;
    }

    private static SnapshotRef bracketingEndSnapshot(Connection connection, LocalDate endAnchor) throws SQLException {
        SnapshotRef snapshot = nearestSnapshot(connection, endAnchor);
        return snapshot == null ? lastSnapshot(connection) : snapshot;
    }

    private static SnapshotRef nearestSnapshot(Connection connection, LocalDate target) throws SQLException {
        LocalDate earliest = target.minusDays(SNAPSHOT_BRACKET_DAYS);
        LocalDate latest = target.plusDays(SNAPSHOT_BRACKET_DAYS);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_date
                FROM net_worth_snapshots
                WHERE snapshot_date >= ?
                  AND snapshot_date <= ?
                """)) {
            statement.setString(1, earliest.toString());
            statement.setString(2, latest.toString());
            SnapshotRef best = null;
            long bestDistance = Long.MAX_VALUE;
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    SnapshotRef candidate = new SnapshotRef(rs.getLong("id"), LocalDate.parse(rs.getString("snapshot_date")));
                    long distance = Math.abs(ChronoUnit.DAYS.between(candidate.snapshotDate(), target));
                    if (best == null
                            || distance < bestDistance
                            || (distance == bestDistance && candidate.snapshotDate().isBefore(best.snapshotDate()))) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
            return best;
        }
    }

    private static SnapshotRef firstSnapshot(Connection connection) throws SQLException {
        return orderedSnapshot(connection, "ASC");
    }

    private static SnapshotRef lastSnapshot(Connection connection) throws SQLException {
        return orderedSnapshot(connection, "DESC");
    }

    private static SnapshotRef orderedSnapshot(Connection connection, String direction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_date
                FROM net_worth_snapshots
                ORDER BY snapshot_date %s
                LIMIT 1
                """.formatted(direction));
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new SnapshotRef(rs.getLong("id"), LocalDate.parse(rs.getString("snapshot_date")));
        }
    }

    private static Map<Long, BigDecimal> snapshotBalances(Connection connection, SnapshotRef snapshot) throws SQLException {
        if (snapshot == null) {
            return Map.of();
        }
        Map<Long, BigDecimal> out = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, balance
                FROM account_balances
                WHERE snapshot_id = ?
                  AND balance IS NOT NULL
                """)) {
            statement.setLong(1, snapshot.id());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong("account_id"), rs.getBigDecimal("balance"));
                }
            }
        }
        return out;
    }

    private static List<AccountRow> accounts(Connection connection) throws SQLException {
        List<AccountRow> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, account_category, type
                FROM accounts
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(new AccountRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("account_category"),
                        rs.getString("type")));
            }
        }
        return out;
    }

    private static String growthBucketForAccount(AccountRow account) {
        String name = account.name() == null ? "" : account.name().toLowerCase(Locale.ROOT);
        if ("cd".equals(account.type())) {
            return "CD Interest";
        }
        if (name.contains("bond")) {
            return "Bond Payments";
        }
        if ("investment".equals(account.accountCategory()) || "tax_advantaged".equals(account.accountCategory())) {
            return "Stock Growth";
        }
        if ("checking".equals(account.type()) || "savings".equals(account.type())) {
            return "Bank Interest";
        }
        return "Other growth";
    }

    private static String deltaBucketForAccount(AccountRow account) {
        String name = account.name() == null ? "" : account.name().toLowerCase(Locale.ROOT);
        if (name.contains("bond")) {
            return "Bond Account";
        }
        if ("investment".equals(account.accountCategory()) || "tax_advantaged".equals(account.accountCategory())) {
            return "Stock Account";
        }
        if ("bank".equals(account.accountCategory())) {
            return "CDs + Bank Accounts";
        }
        return "Other Accounts";
    }

    private static BigDecimal effectiveAmount(BigDecimal amount, BigDecimal personalShare) {
        if (personalShare == null) {
            return amount;
        }
        return amount.multiply(personalShare);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record CategoryRow(long id, String name, Long parentId) {
    }

    private record CategoryGroup(String leaf, String group) {
    }

    private record AccountRow(long id, String name, String accountCategory, String type) {
    }

}
