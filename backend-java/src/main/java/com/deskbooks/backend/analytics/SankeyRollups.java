package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

record SankeyTransactionRollup(
        Map<String, BigDecimal> incomeLeaves,
        Map<String, Map<String, BigDecimal>> expenses,
        BigDecimal donationsTotal,
        BigDecimal taxesTotal) {
}

record SankeySnapshotRollup(
        SnapshotRef startSnapshot,
        SnapshotRef endSnapshot,
        Map<String, BigDecimal> deltaByBucket,
        Map<String, BigDecimal> positiveDeltaByGrowthSource,
        BigDecimal totalAccountDelta) {
}

record SnapshotRef(long id, LocalDate snapshotDate) {
}
