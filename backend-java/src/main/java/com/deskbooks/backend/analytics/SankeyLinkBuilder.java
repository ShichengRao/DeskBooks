package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.util.Map;

final class SankeyLinkBuilder {
    private final SankeyGraph graph;
    private final int hub;
    private final SankeyExpenseLinks expenseLinks;
    private final SankeyBalanceLinks balanceLinks;

    SankeyLinkBuilder(SankeyGraph graph, int hub) {
        this.graph = graph;
        this.hub = hub;
        expenseLinks = new SankeyExpenseLinks(graph);
        balanceLinks = new SankeyBalanceLinks(graph, hub);
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
        expenseLinks.add(hub, expenses, expenseTotal);
    }

    void addGrowth(BigDecimal growthTotal, Map<String, BigDecimal> positiveDeltaByGrowthSource) {
        balanceLinks.addGrowth(growthTotal, positiveDeltaByGrowthSource);
    }

    void addAccountDeltas(BigDecimal impliedToAccounts, Map<String, BigDecimal> deltaByBucket) {
        balanceLinks.addAccountDeltas(impliedToAccounts, deltaByBucket);
    }
}
