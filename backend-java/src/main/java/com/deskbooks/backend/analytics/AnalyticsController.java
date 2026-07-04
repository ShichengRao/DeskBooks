package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
class AnalyticsController {
    private final SqliteConnectionProvider connections;

    AnalyticsController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("/sankey")
    SankeyResponse sankey(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try (Connection connection = connections.open()) {
            if (start != null && end != null) {
                if (end.isBefore(start)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
                }
                return sankeyForPeriod(connection, start, end, "%s to %s".formatted(start, end));
            }
            if (year == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "provide either year or start/end");
            }
            return sankeyForPeriod(
                    connection,
                    LocalDate.of(year, 1, 1),
                    LocalDate.of(year, 12, 31),
                    String.valueOf(year));
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/monthly")
    List<MonthlyPointResponse> monthly(
            @RequestParam(name = "start") LocalDate start,
            @RequestParam(name = "end") LocalDate end) {
        try (Connection connection = connections.open()) {
            return MonthlyAnalytics.load(connection, start, end);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/recurring")
    List<RecurringMerchantResponse> recurring(
            @RequestParam(name = "min_occurrences", defaultValue = "3") int minOccurrences,
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COALESCE(merchant, description_normalized) AS merchant,
                               COUNT(id) AS occurrences,
                               AVG(amount) AS avg_amount,
                               SUM(amount) AS total_amount,
                               MAX(date) AS last_seen,
                               MIN(date) AS first_seen
                        FROM transactions
                        WHERE COALESCE(merchant, description_normalized) IS NOT NULL
                          AND is_excluded_from_totals = 0
                          AND (? IS NULL OR date >= ?)
                          AND (? IS NULL OR date <= ?)
                        GROUP BY COALESCE(merchant, description_normalized)
                        HAVING COUNT(id) >= ?
                        ORDER BY COUNT(id) DESC
                        """)) {
            String startValue = start == null ? null : start.toString();
            String endValue = end == null ? null : end.toString();
            statement.setString(1, startValue);
            statement.setString(2, startValue);
            statement.setString(3, endValue);
            statement.setString(4, endValue);
            statement.setInt(5, minOccurrences);

            List<RecurringMerchantResponse> out = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate firstSeen = nullableDate(rs.getString("first_seen"));
                    LocalDate lastSeen = nullableDate(rs.getString("last_seen"));
                    out.add(new RecurringMerchantResponse(
                            rs.getString("merchant"),
                            rs.getInt("occurrences"),
                            moneyString(rs.getBigDecimal("avg_amount")),
                            moneyString(rs.getBigDecimal("total_amount")),
                            lastSeen,
                            cadenceDays(firstSeen, lastSeen, rs.getInt("occurrences"))));
                }
            }
            return out;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/reconcile")
    ReconcileResponse reconcile(
            @RequestParam(name = "account_id") long accountId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try (Connection connection = connections.open()) {
            if (start != null || end != null) {
                if (start == null || end == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "provide both start and end");
                }
                if (end.isBefore(start)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
                }
                return reconcileAccountPeriod(connection, accountId, start, end, null, null);
            }
            if (year == null || month == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "provide either year/month or start/end");
            }
            return reconcileAccountMonth(connection, accountId, year, month);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/reconcile")
    ReconcileResponse upsertReconcile(@Valid @RequestBody ReconcileRequest body) {
        try (Connection connection = connections.open()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO monthly_reconciliations (account_id, year, month, statement_total, notes)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, year, month) DO UPDATE SET
                      statement_total = excluded.statement_total,
                      notes = excluded.notes,
                      updated_at = CURRENT_TIMESTAMP
                    """)) {
                statement.setLong(1, body.accountId());
                statement.setInt(2, body.year());
                statement.setInt(3, body.month());
                statement.setBigDecimal(4, body.statementTotal() == null ? null : money(body.statementTotal()));
                statement.setString(5, body.notes());
                statement.executeUpdate();
            }
            return reconcileAccountMonth(connection, body.accountId(), body.year(), body.month());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/splits")
    List<SplitGroupSummaryResponse> splitGroups(
            @RequestParam(name = "start") LocalDate start,
            @RequestParam(name = "end") LocalDate end) {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT s.group_name, s.personal_share, t.amount
                        FROM transaction_splits s
                        JOIN transactions t ON t.id = s.transaction_id
                        WHERE t.date >= ?
                          AND t.date <= ?
                          AND t.is_excluded_from_totals = 0
                        """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            Map<String, SplitAccumulator> groups = new TreeMap<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String groupName = rs.getString("group_name");
                    SplitAccumulator group = groups.computeIfAbsent(groupName, ignored -> new SplitAccumulator());
                    group.transactionCount++;
                    BigDecimal amount = rs.getBigDecimal("amount");
                    BigDecimal share = rs.getBigDecimal("personal_share");
                    if (amount.compareTo(BigDecimal.ZERO) < 0) {
                        BigDecimal fullOutflow = amount.negate();
                        BigDecimal personal = fullOutflow.multiply(share);
                        group.sharedOutflows = group.sharedOutflows.add(fullOutflow);
                        group.personalOutflows = group.personalOutflows.add(personal);
                        group.expectedReimbursement = group.expectedReimbursement.add(fullOutflow.subtract(personal));
                    } else if (amount.compareTo(BigDecimal.ZERO) > 0) {
                        group.receivedReimbursement = group.receivedReimbursement.add(amount);
                    }
                }
            }

            List<SplitGroupSummaryResponse> out = new ArrayList<>();
            for (Map.Entry<String, SplitAccumulator> entry : groups.entrySet()) {
                SplitAccumulator group = entry.getValue();
                BigDecimal remaining = group.expectedReimbursement.subtract(group.receivedReimbursement);
                out.add(new SplitGroupSummaryResponse(
                        entry.getKey(),
                        moneyString(group.sharedOutflows),
                        moneyString(group.personalOutflows),
                        moneyString(group.expectedReimbursement),
                        moneyString(group.receivedReimbursement),
                        moneyString(remaining),
                        group.transactionCount));
            }
            return out;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ReconcileResponse reconcileAccountMonth(Connection connection, long accountId, int year, int month) throws SQLException {
        YearMonth yearMonth = YearMonth.of(year, month);
        return reconcileAccountPeriod(
                connection,
                accountId,
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth(),
                year,
                month);
    }

    private SankeyResponse sankeyForPeriod(Connection connection, LocalDate start, LocalDate end, String label) throws SQLException {
        Map<Long, CategoryGroup> groupMap = categoryGroupMap(connection);
        SankeyTransactionRollup transactions = collectSankeyTransactions(connection, start, end, groupMap);
        SankeySnapshotRollup snapshots = collectSankeySnapshotDeltas(connection, start, end);
        SankeyFlowTotals totals = sankeyFlowTotals(transactions, snapshots.totalAccountDelta());

        SankeyGraph graph = new SankeyGraph();
        int hub = graph.node("Inflows");
        addIncomeLinks(graph, hub, transactions.incomeLeaves(), totals.income());
        addGrowthLinks(graph, hub, totals.growth(), snapshots.positiveDeltaByGrowthSource());
        addExpenseLinks(graph, hub, transactions.expenses(), totals.expenses());
        if (transactions.donationsTotal().compareTo(BigDecimal.ZERO) > 0) {
            graph.link(hub, graph.node("Donations"), transactions.donationsTotal(), "Donations");
        }
        if (transactions.taxesTotal().compareTo(BigDecimal.ZERO) > 0) {
            graph.link(hub, graph.node("Taxes"), transactions.taxesTotal(), "Taxes");
        }
        addAccountDeltaLinks(
                graph,
                hub,
                impliedAccountDelta(totals, transactions, snapshots),
                snapshots.deltaByBucket());

        return new SankeyResponse(
                start.getYear(),
                label,
                graph.nodes().stream().map(SankeyNodeResponse::new).toList(),
                graph.links(),
                sankeyNotes(snapshots.startSnapshot(), snapshots.endSnapshot()));
    }

    private Map<Long, CategoryGroup> categoryGroupMap(Connection connection) throws SQLException {
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

    private SankeyTransactionRollup collectSankeyTransactions(
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

    private SankeySnapshotRollup collectSankeySnapshotDeltas(Connection connection, LocalDate start, LocalDate end) throws SQLException {
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

    private SnapshotRef bracketingStartSnapshot(Connection connection, LocalDate start) throws SQLException {
        SnapshotRef snapshot = nearestSnapshot(connection, start);
        return snapshot == null ? firstSnapshot(connection) : snapshot;
    }

    private SnapshotRef bracketingEndSnapshot(Connection connection, LocalDate endAnchor) throws SQLException {
        SnapshotRef snapshot = nearestSnapshot(connection, endAnchor);
        return snapshot == null ? lastSnapshot(connection) : snapshot;
    }

    private SnapshotRef nearestSnapshot(Connection connection, LocalDate target) throws SQLException {
        LocalDate earliest = target.minusDays(60);
        LocalDate latest = target.plusDays(60);
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

    private SnapshotRef firstSnapshot(Connection connection) throws SQLException {
        return orderedSnapshot(connection, "ASC");
    }

    private SnapshotRef lastSnapshot(Connection connection) throws SQLException {
        return orderedSnapshot(connection, "DESC");
    }

    private SnapshotRef orderedSnapshot(Connection connection, String direction) throws SQLException {
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

    private Map<Long, BigDecimal> snapshotBalances(Connection connection, SnapshotRef snapshot) throws SQLException {
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

    private List<AccountRow> accounts(Connection connection) throws SQLException {
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

    private String growthBucketForAccount(AccountRow account) {
        String name = account.name() == null ? "" : account.name().toLowerCase();
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

    private String deltaBucketForAccount(AccountRow account) {
        String name = account.name() == null ? "" : account.name().toLowerCase();
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

    private SankeyFlowTotals sankeyFlowTotals(SankeyTransactionRollup transactions, BigDecimal totalAccountDelta) {
        BigDecimal expenseTotal = BigDecimal.ZERO;
        for (Map<String, BigDecimal> leaves : transactions.expenses().values()) {
            BigDecimal groupTotal = sumValues(leaves);
            if (groupTotal.compareTo(BigDecimal.ZERO) > 0) {
                expenseTotal = expenseTotal.add(groupTotal);
            }
        }
        BigDecimal incomeTotal = BigDecimal.ZERO;
        for (BigDecimal value : transactions.incomeLeaves().values()) {
            if (value.compareTo(BigDecimal.ZERO) > 0) {
                incomeTotal = incomeTotal.add(value);
            }
        }
        BigDecimal netCashflowRealized = incomeTotal
                .subtract(expenseTotal)
                .subtract(transactions.donationsTotal())
                .subtract(transactions.taxesTotal());
        BigDecimal growthTotal = totalAccountDelta.subtract(netCashflowRealized);
        if (growthTotal.compareTo(BigDecimal.ZERO) < 0) {
            growthTotal = BigDecimal.ZERO;
        }
        return new SankeyFlowTotals(incomeTotal, expenseTotal, growthTotal, incomeTotal.add(growthTotal));
    }

    private void addIncomeLinks(
            SankeyGraph graph,
            int hub,
            Map<String, BigDecimal> incomeLeaves,
            BigDecimal incomeTotal) {
        if (incomeTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int incomeGroup = graph.node("Income");
        sortedEntriesDescending(incomeLeaves).forEach(entry -> {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(graph.node(entry.getKey()), incomeGroup, entry.getValue(), entry.getKey());
            }
        });
        graph.link(incomeGroup, hub, incomeTotal, "Income");
    }

    private void addExpenseLinks(
            SankeyGraph graph,
            int hub,
            Map<String, Map<String, BigDecimal>> expenses,
            BigDecimal expenseTotal) {
        if (expenseTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int expenseGroup = graph.node("Expenses");
        graph.link(hub, expenseGroup, expenseTotal, "Expenses");
        expenses.entrySet().stream()
                .sorted((left, right) -> sumValues(right.getValue()).compareTo(sumValues(left.getValue())))
                .forEach(entry -> {
                    BigDecimal groupTotal = sumValues(entry.getValue());
                    if (groupTotal.compareTo(BigDecimal.ZERO) > 0) {
                        addExpenseGroupLinks(graph, expenseGroup, entry.getKey(), entry.getValue(), groupTotal);
                    }
                });
    }

    private void addExpenseGroupLinks(
            SankeyGraph graph,
            int expenseGroup,
            String group,
            Map<String, BigDecimal> leaves,
            BigDecimal groupTotal) {
        if (leaves.size() >= 2 && !leaves.containsKey(group)) {
            int groupNode = graph.node(group);
            graph.link(expenseGroup, groupNode, groupTotal, group);
            addGroupedExpenseLeaves(graph, groupNode, leaves);
        } else {
            addCollapsedExpenseLeaves(graph, expenseGroup, leaves);
        }
    }

    private void addGroupedExpenseLeaves(SankeyGraph graph, int groupNode, Map<String, BigDecimal> leaves) {
        sortedEntriesDescending(leaves).forEach(entry -> {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(groupNode, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
        });
    }

    private void addCollapsedExpenseLeaves(SankeyGraph graph, int expenseGroup, Map<String, BigDecimal> leaves) {
        for (Map.Entry<String, BigDecimal> entry : leaves.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(expenseGroup, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
        }
    }

    private void addGrowthLinks(
            SankeyGraph graph,
            int hub,
            BigDecimal growthTotal,
            Map<String, BigDecimal> positiveDeltaByGrowthSource) {
        if (growthTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int growthGroup = graph.node("Growth");
        BigDecimal totalPositiveShare = sumValues(positiveDeltaByGrowthSource);
        if (totalPositiveShare.compareTo(BigDecimal.ZERO) > 0) {
            sortedEntriesDescending(positiveDeltaByGrowthSource).forEach(entry -> {
                if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal value = apportionedValue(entry.getValue(), totalPositiveShare, growthTotal);
                    if (value.compareTo(BigDecimal.ZERO) > 0) {
                        graph.link(graph.node(entry.getKey()), growthGroup, value, entry.getKey());
                    }
                }
            });
        } else {
            graph.link(graph.node("Unallocated growth"), growthGroup, growthTotal, "Unallocated growth");
        }
        graph.link(growthGroup, hub, growthTotal, "Growth");
    }

    private BigDecimal impliedAccountDelta(
            SankeyFlowTotals totals,
            SankeyTransactionRollup transactions,
            SankeySnapshotRollup snapshots) {
        if (snapshots.totalAccountDelta().compareTo(BigDecimal.ZERO) > 0
                && totals.growth().compareTo(BigDecimal.ZERO) > 0) {
            return snapshots.totalAccountDelta();
        }
        return totals.inflows()
                .subtract(totals.expenses())
                .subtract(transactions.donationsTotal())
                .subtract(transactions.taxesTotal());
    }

    private void addAccountDeltaLinks(
            SankeyGraph graph,
            int hub,
            BigDecimal impliedToAccounts,
            Map<String, BigDecimal> deltaByBucket) {
        if (impliedToAccounts.compareTo(BigDecimal.ZERO) > 0) {
            int accountsNode = graph.node("Account deltas (pos)");
            graph.link(hub, accountsNode, impliedToAccounts, "Account deltas");
            addAccountDeltaBucketLinks(graph, accountsNode, impliedToAccounts, deltaByBucket);
        } else if (impliedToAccounts.compareTo(BigDecimal.ZERO) < 0) {
            graph.link(graph.node("Drawn from savings"), hub, impliedToAccounts.negate(), "Drawn from savings");
        }
    }

    private void addAccountDeltaBucketLinks(
            SankeyGraph graph,
            int accountsNode,
            BigDecimal impliedToAccounts,
            Map<String, BigDecimal> deltaByBucket) {
        Map<String, BigDecimal> positiveBuckets = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : deltaByBucket.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                positiveBuckets.put(entry.getKey(), entry.getValue());
            }
        }
        BigDecimal bucketSum = sumValues(positiveBuckets);
        if (bucketSum.compareTo(BigDecimal.ZERO) > 0) {
            sortedEntriesDescending(positiveBuckets).forEach(entry -> {
                BigDecimal share = apportionedValue(entry.getValue(), bucketSum, impliedToAccounts);
                graph.link(accountsNode, graph.node(entry.getKey()), share, entry.getKey());
            });
        } else {
            graph.link(accountsNode, graph.node("(unknown)"), impliedToAccounts, "(unknown)");
        }
    }

    private List<String> sankeyNotes(SnapshotRef startSnapshot, SnapshotRef endSnapshot) {
        String startDate = startSnapshot == null ? "—" : startSnapshot.snapshotDate().toString();
        String endDate = endSnapshot == null ? "—" : endSnapshot.snapshotDate().toString();
        return List.of(
                "Five-level Sankey. Source → Group (Income/Growth) → Inflows hub → Outflow split → Leaf.",
                "Growth uses the bookkeeping identity ΔNLV = Income − Expenses − Donations − Taxes + Growth, then splits by each NLV account-type's positive-delta share (CD Interest / Stock Growth / Bank Interest / Bond Payments).",
                "Account deltas (pos) is sized to balance the diagram, then split into account-category buckets by their positive-delta share.",
                "Snapshot bracketing picks snapshots nearest to the selected period boundaries (within ±60 days).",
                "Transfers and credit-card payments are intentionally excluded from cashflow (they net to zero between accounts).",
                "Snapshot window used: %s → %s.".formatted(startDate, endDate));
    }

    private ReconcileResponse reconcileAccountPeriod(
            Connection connection,
            long accountId,
            LocalDate start,
            LocalDate end,
            Integer year,
            Integer month) throws SQLException {
        Map<String, BigDecimal> byKind = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal inflows = BigDecimal.ZERO;
        BigDecimal outflows = BigDecimal.ZERO;
        int transactionCount = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT amount, kind
                FROM transactions
                WHERE account_id = ?
                  AND date >= ?
                  AND date <= ?
                  AND is_excluded_from_totals = 0
                """)) {
            statement.setLong(1, accountId);
            statement.setString(2, start.toString());
            statement.setString(3, end.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    transactionCount++;
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String kind = rs.getString("kind");
                    byKind.merge(kind, amount, BigDecimal::add);
                    total = total.add(amount);
                    if (amount.compareTo(BigDecimal.ZERO) >= 0) {
                        inflows = inflows.add(amount);
                    } else {
                        outflows = outflows.add(amount);
                    }
                }
            }
        }

        ReconciliationRow reconciliation = null;
        if (year != null && month != null) {
            reconciliation = reconciliation(connection, accountId, year, month);
        }
        BigDecimal statementTotal = reconciliation == null ? null : reconciliation.statementTotal();
        BigDecimal delta = statementTotal == null ? null : total.subtract(statementTotal);
        return new ReconcileResponse(
                accountId,
                year,
                month,
                start,
                end,
                transactionCount,
                moneyString(total),
                moneyString(inflows),
                moneyString(outflows),
                stringifyMoney(byKind),
                moneyStringOrNull(statementTotal),
                reconciliation == null ? null : reconciliation.notes(),
                moneyStringOrNull(delta));
    }

    private ReconciliationRow reconciliation(Connection connection, long accountId, int year, int month) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT statement_total, notes
                FROM monthly_reconciliations
                WHERE account_id = ? AND year = ? AND month = ?
                """)) {
            statement.setLong(1, accountId);
            statement.setInt(2, year);
            statement.setInt(3, month);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ReconciliationRow(rs.getBigDecimal("statement_total"), rs.getString("notes"));
            }
        }
    }

    private Map<String, String> stringifyMoney(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            out.put(entry.getKey(), moneyString(entry.getValue()));
        }
        return out;
    }

    private BigDecimal effectiveAmount(BigDecimal amount, BigDecimal personalShare) {
        if (personalShare == null) {
            return amount;
        }
        return amount.multiply(personalShare);
    }

    private LocalDate nullableDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private Double cadenceDays(LocalDate firstSeen, LocalDate lastSeen, int occurrences) {
        if (firstSeen == null || lastSeen == null || occurrences <= 1) {
            return null;
        }
        long spanDays = ChronoUnit.DAYS.between(firstSeen, lastSeen);
        if (spanDays <= 0) {
            return null;
        }
        return spanDays / (double) (occurrences - 1);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private BigDecimal sumValues(Map<String, BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values.values()) {
            total = total.add(value);
        }
        return total;
    }

    private List<Map.Entry<String, BigDecimal>> sortedEntriesDescending(Map<String, BigDecimal> values) {
        return values.entrySet().stream()
                .sorted((left, right) -> right.getValue().compareTo(left.getValue()))
                .toList();
    }

    private BigDecimal apportionedValue(BigDecimal shareBasis, BigDecimal totalShare, BigDecimal totalValue) {
        return shareBasis.multiply(totalValue).divide(totalShare, 10, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private String moneyStringOrNull(BigDecimal value) {
        return value == null ? null : moneyString(value);
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record ReconcileRequest(
            @NotNull Long accountId,
            int year,
            int month,
            BigDecimal statementTotal,
            String notes) {
    }

    record MonthlyPointResponse(
            String month,
            Map<String, BigDecimal> byKind,
            Map<String, BigDecimal> byExpenseCategory,
            Map<String, BigDecimal> byIncomeCategory,
            BigDecimal expensesTotal,
            BigDecimal incomeTotal,
            BigDecimal donationsTotal,
            BigDecimal taxesTotal,
            BigDecimal net) {
    }

    record RecurringMerchantResponse(
            String merchant,
            int occurrences,
            String avgAmount,
            String totalAmount,
            LocalDate lastSeen,
            Double cadenceDaysEstimate) {
    }

    record SankeyResponse(
            int year,
            String label,
            List<SankeyNodeResponse> nodes,
            List<SankeyLinkResponse> links,
            List<String> notes) {
    }

    record SankeyNodeResponse(String name) {
    }

    record SankeyLinkResponse(int source, int target, double value, String label) {
    }

    record ReconcileResponse(
            long accountId,
            Integer year,
            Integer month,
            LocalDate start,
            LocalDate end,
            int transactionCount,
            String importedTotal,
            String importedInflows,
            String importedOutflows,
            Map<String, String> byKind,
            String statementTotal,
            String statementNotes,
            String delta) {
    }

    record SplitGroupSummaryResponse(
            String groupName,
            String sharedOutflows,
            String personalOutflows,
            String expectedReimbursement,
            String receivedReimbursement,
            String remainingOwed,
            int transactionCount) {
    }

    private record ReconciliationRow(BigDecimal statementTotal, String notes) {
    }

    private record CategoryRow(long id, String name, Long parentId) {
    }

    private record CategoryGroup(String leaf, String group) {
    }

    private record AccountRow(long id, String name, String accountCategory, String type) {
    }

    private record SnapshotRef(long id, LocalDate snapshotDate) {
    }

    private record SankeyTransactionRollup(
            Map<String, BigDecimal> incomeLeaves,
            Map<String, Map<String, BigDecimal>> expenses,
            BigDecimal donationsTotal,
            BigDecimal taxesTotal) {
    }

    private record SankeySnapshotRollup(
            SnapshotRef startSnapshot,
            SnapshotRef endSnapshot,
            Map<String, BigDecimal> deltaByBucket,
            Map<String, BigDecimal> positiveDeltaByGrowthSource,
            BigDecimal totalAccountDelta) {
    }

    private record SankeyFlowTotals(
            BigDecimal income,
            BigDecimal expenses,
            BigDecimal growth,
            BigDecimal inflows) {
    }

    private static final class SankeyGraph {
        private final List<String> nodes = new ArrayList<>();
        private final Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        private final List<SankeyLinkResponse> links = new ArrayList<>();

        int node(String name) {
            Integer existing = nodeIndex.get(name);
            if (existing != null) {
                return existing;
            }
            int index = nodes.size();
            nodeIndex.put(name, index);
            nodes.add(name);
            return index;
        }

        void link(int source, int target, BigDecimal value, String label) {
            links.add(new SankeyLinkResponse(source, target, value.doubleValue(), label));
        }

        List<String> nodes() {
            return nodes;
        }

        List<SankeyLinkResponse> links() {
            return links;
        }
    }

    private static final class SplitAccumulator {
        BigDecimal sharedOutflows = BigDecimal.ZERO;
        BigDecimal personalOutflows = BigDecimal.ZERO;
        BigDecimal expectedReimbursement = BigDecimal.ZERO;
        BigDecimal receivedReimbursement = BigDecimal.ZERO;
        int transactionCount = 0;
    }
}
