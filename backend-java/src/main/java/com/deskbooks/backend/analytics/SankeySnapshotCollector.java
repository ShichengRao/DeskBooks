package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

final class SankeySnapshotCollector {
    private final SankeySnapshots snapshots = new SankeySnapshots();
    private final SankeyAccounts accounts = new SankeyAccounts();

    private SankeySnapshotCollector() {
    }

    static SankeySnapshotRollup collect(Connection connection, LocalDate start, LocalDate end) throws SQLException {
        return new SankeySnapshotCollector().collectDeltas(connection, start, end);
    }

    private SankeySnapshotRollup collectDeltas(Connection connection, LocalDate start, LocalDate end)
            throws SQLException {
        SnapshotRef startSnapshot = snapshots.bracketingStart(connection, start);
        SnapshotRef endSnapshot = snapshots.bracketingEnd(connection, end.plusDays(1));
        Map<Long, BigDecimal> startBalances = snapshots.balances(connection, startSnapshot);
        Map<Long, BigDecimal> endBalances = snapshots.balances(connection, endSnapshot);

        Map<String, BigDecimal> deltaByBucket = new LinkedHashMap<>();
        Map<String, BigDecimal> positiveDeltaByGrowthSource = new LinkedHashMap<>();
        BigDecimal totalAccountDelta = BigDecimal.ZERO;
        for (AccountRow account : accounts.accounts(connection)) {
            if (accounts.excludedFromDelta(account)) {
                continue;
            }
            BigDecimal delta = accountDelta(account, startBalances, endBalances);
            deltaByBucket.merge(accounts.deltaBucket(account), delta, BigDecimal::add);
            totalAccountDelta = totalAccountDelta.add(delta);
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                positiveDeltaByGrowthSource.merge(accounts.growthBucket(account), delta, BigDecimal::add);
            }
        }

        return new SankeySnapshotRollup(
                startSnapshot,
                endSnapshot,
                deltaByBucket,
                positiveDeltaByGrowthSource,
                totalAccountDelta);
    }

    private BigDecimal accountDelta(
            AccountRow account,
            Map<Long, BigDecimal> startBalances,
            Map<Long, BigDecimal> endBalances) {
        BigDecimal startBalance = startBalances.getOrDefault(account.id(), BigDecimal.ZERO);
        BigDecimal endBalance = endBalances.getOrDefault(account.id(), BigDecimal.ZERO);
        return endBalance.subtract(startBalance);
    }
}
