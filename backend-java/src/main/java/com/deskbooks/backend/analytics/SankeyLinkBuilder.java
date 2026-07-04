package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

final class SankeyLinkBuilder {
    private final SankeyGraph graph;
    private final int hub;

    SankeyLinkBuilder(SankeyGraph graph, int hub) {
        this.graph = graph;
        this.hub = hub;
    }

    void addIncome(Map<String, BigDecimal> incomeLeaves, BigDecimal incomeTotal) {
        if (incomeTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int incomeGroup = graph.node("Income");
        SankeyAmounts.sortedEntriesDescending(incomeLeaves).forEach(entry -> {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(graph.node(entry.getKey()), incomeGroup, entry.getValue(), entry.getKey());
            }
        });
        graph.link(incomeGroup, hub, incomeTotal, "Income");
    }

    void addExpenses(Map<String, Map<String, BigDecimal>> expenses, BigDecimal expenseTotal) {
        if (expenseTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int expenseGroup = graph.node("Expenses");
        graph.link(hub, expenseGroup, expenseTotal, "Expenses");
        expenses.entrySet().stream()
                .sorted((left, right) -> SankeyAmounts.sumValues(right.getValue())
                        .compareTo(SankeyAmounts.sumValues(left.getValue())))
                .forEach(entry -> {
                    BigDecimal groupTotal = SankeyAmounts.sumValues(entry.getValue());
                    if (groupTotal.compareTo(BigDecimal.ZERO) > 0) {
                        addExpenseGroup(expenseGroup, entry.getKey(), entry.getValue(), groupTotal);
                    }
                });
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

    private void addExpenseGroup(
            int expenseGroup,
            String group,
            Map<String, BigDecimal> leaves,
            BigDecimal groupTotal) {
        if (leaves.size() >= 2 && !leaves.containsKey(group)) {
            int groupNode = graph.node(group);
            graph.link(expenseGroup, groupNode, groupTotal, group);
            addGroupedExpenseLeaves(groupNode, leaves);
        } else {
            addCollapsedExpenseLeaves(expenseGroup, leaves);
        }
    }

    private void addGroupedExpenseLeaves(int groupNode, Map<String, BigDecimal> leaves) {
        SankeyAmounts.sortedEntriesDescending(leaves).forEach(entry -> {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(groupNode, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
        });
    }

    private void addCollapsedExpenseLeaves(int expenseGroup, Map<String, BigDecimal> leaves) {
        for (Map.Entry<String, BigDecimal> entry : leaves.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(expenseGroup, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
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
