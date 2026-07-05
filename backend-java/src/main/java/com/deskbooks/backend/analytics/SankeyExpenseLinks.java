package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.util.Map;

final class SankeyExpenseLinks {
    private final SankeyGraph graph;

    SankeyExpenseLinks(SankeyGraph graph) {
        this.graph = graph;
    }

    void add(int hub, Map<String, Map<String, BigDecimal>> expenses, BigDecimal expenseTotal) {
        if (expenseTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int expenseGroup = graph.node("Expenses");
        graph.link(hub, expenseGroup, expenseTotal, "Expenses");
        expenses.entrySet().stream()
                .sorted((left, right) -> SankeyAmounts.sumValues(right.getValue())
                        .compareTo(SankeyAmounts.sumValues(left.getValue())))
                .forEach(entry -> addPositiveGroup(expenseGroup, entry));
    }

    private void addPositiveGroup(
            int expenseGroup,
            Map.Entry<String, Map<String, BigDecimal>> entry) {
        BigDecimal groupTotal = SankeyAmounts.sumValues(entry.getValue());
        if (groupTotal.compareTo(BigDecimal.ZERO) > 0) {
            addGroup(expenseGroup, entry.getKey(), entry.getValue(), groupTotal);
        }
    }

    private void addGroup(
            int expenseGroup,
            String group,
            Map<String, BigDecimal> leaves,
            BigDecimal groupTotal) {
        if (leaves.size() >= 2 && !leaves.containsKey(group)) {
            int groupNode = graph.node(group);
            graph.link(expenseGroup, groupNode, groupTotal, group);
            addGroupedLeaves(groupNode, leaves);
        } else {
            addCollapsedLeaves(expenseGroup, leaves);
        }
    }

    private void addGroupedLeaves(int groupNode, Map<String, BigDecimal> leaves) {
        SankeyAmounts.sortedEntriesDescending(leaves).forEach(entry -> {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(groupNode, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
        });
    }

    private void addCollapsedLeaves(int expenseGroup, Map<String, BigDecimal> leaves) {
        for (Map.Entry<String, BigDecimal> entry : leaves.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(expenseGroup, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
        }
    }
}
