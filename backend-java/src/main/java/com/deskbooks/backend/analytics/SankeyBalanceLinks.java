package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

final class SankeyBalanceLinks {
    private final SankeyGraph graph;
    private final int hub;

    SankeyBalanceLinks(SankeyGraph graph, int hub) {
        this.graph = graph;
        this.hub = hub;
    }

    void addGrowth(BigDecimal growthTotal, Map<String, BigDecimal> positiveDeltaByGrowthSource) {
        if (growthTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int growthGroup = graph.node("Growth");
        BigDecimal totalPositiveShare = SankeyAmounts.sumValues(positiveDeltaByGrowthSource);
        if (totalPositiveShare.compareTo(BigDecimal.ZERO) > 0) {
            addGrowthSources(growthGroup, growthTotal, positiveDeltaByGrowthSource, totalPositiveShare);
        } else {
            graph.link(graph.node("Unallocated growth"), growthGroup, growthTotal, "Unallocated growth");
        }
        graph.link(growthGroup, hub, growthTotal, "Growth");
    }

    void addAccountDeltas(BigDecimal impliedToAccounts, Map<String, BigDecimal> deltaByBucket) {
        if (impliedToAccounts.compareTo(BigDecimal.ZERO) > 0) {
            int accountsNode = graph.node("Account deltas (pos)");
            graph.link(hub, accountsNode, impliedToAccounts, "Account deltas");
            addAccountDeltaBuckets(accountsNode, impliedToAccounts, deltaByBucket);
        } else if (impliedToAccounts.compareTo(BigDecimal.ZERO) < 0) {
            graph.link(graph.node("Drawn from savings"), hub, impliedToAccounts.negate(), "Drawn from savings");
        }
    }

    private void addGrowthSources(
            int growthGroup,
            BigDecimal growthTotal,
            Map<String, BigDecimal> positiveDeltaByGrowthSource,
            BigDecimal totalPositiveShare) {
        SankeyAmounts.sortedEntriesDescending(positiveDeltaByGrowthSource).forEach(entry -> {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal value = SankeyAmounts.apportionedValue(entry.getValue(), totalPositiveShare, growthTotal);
                if (value.compareTo(BigDecimal.ZERO) > 0) {
                    graph.link(graph.node(entry.getKey()), growthGroup, value, entry.getKey());
                }
            }
        });
    }

    private void addAccountDeltaBuckets(
            int accountsNode,
            BigDecimal impliedToAccounts,
            Map<String, BigDecimal> deltaByBucket) {
        Map<String, BigDecimal> positiveBuckets = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : deltaByBucket.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                positiveBuckets.put(entry.getKey(), entry.getValue());
            }
        }
        BigDecimal bucketSum = SankeyAmounts.sumValues(positiveBuckets);
        if (bucketSum.compareTo(BigDecimal.ZERO) > 0) {
            SankeyAmounts.sortedEntriesDescending(positiveBuckets).forEach(entry -> {
                BigDecimal share = SankeyAmounts.apportionedValue(entry.getValue(), bucketSum, impliedToAccounts);
                graph.link(accountsNode, graph.node(entry.getKey()), share, entry.getKey());
            });
        } else {
            graph.link(accountsNode, graph.node("(unknown)"), impliedToAccounts, "(unknown)");
        }
    }
}
