package com.deskbooks.backend.analytics;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

final class SankeyAnalytics {
    private SankeyAnalytics() {
    }

    static SankeyResponse load(
            Connection connection,
            LocalDate start,
            LocalDate end,
            String label) throws SQLException {
        var groupMap = SankeyCategoryGroups.load(connection);
        SankeyTransactionRollup transactions = SankeyTransactionCollector.collect(connection, start, end, groupMap);
        SankeySnapshotRollup snapshots = SankeySnapshotCollector.collect(connection, start, end);
        return SankeyGraphBuilder.build(start, label, transactions, snapshots);
    }
}
