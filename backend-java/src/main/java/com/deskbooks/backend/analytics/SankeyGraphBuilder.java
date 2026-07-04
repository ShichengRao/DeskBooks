package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

final class SankeyGraphBuilder {
    private SankeyGraphBuilder() {
    }

    static AnalyticsController.SankeyResponse build(
            LocalDate start,
            String label,
            SankeyTransactionRollup transactions,
            SankeySnapshotRollup snapshots) {
        SankeyFlowTotals totals = SankeyFlowCalculator.totals(transactions, snapshots.totalAccountDelta());

        SankeyGraph graph = new SankeyGraph();
        int hub = graph.node("Inflows");
        SankeyLinkBuilder links = new SankeyLinkBuilder(graph, hub);
        links.addIncome(transactions.incomeLeaves(), totals.income());
        links.addGrowth(totals.growth(), snapshots.positiveDeltaByGrowthSource());
        links.addExpenses(transactions.expenses(), totals.expenses());
        if (transactions.donationsTotal().compareTo(BigDecimal.ZERO) > 0) {
            graph.link(hub, graph.node("Donations"), transactions.donationsTotal(), "Donations");
        }
        if (transactions.taxesTotal().compareTo(BigDecimal.ZERO) > 0) {
            graph.link(hub, graph.node("Taxes"), transactions.taxesTotal(), "Taxes");
        }
        links.addAccountDeltas(
                SankeyFlowCalculator.impliedAccountDelta(totals, transactions, snapshots),
                snapshots.deltaByBucket());

        return new AnalyticsController.SankeyResponse(
                start.getYear(),
                label,
                graph.nodes().stream().map(AnalyticsController.SankeyNodeResponse::new).toList(),
                graph.links(),
                SankeyNotes.build(snapshots.startSnapshot(), snapshots.endSnapshot()));
    }
}
