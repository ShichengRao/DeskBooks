package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SankeyGraphBuilder {
    private SankeyGraphBuilder() {
    }

    static AnalyticsController.SankeyResponse build(
            LocalDate start,
            String label,
            SankeyTransactionRollup transactions,
            SankeySnapshotRollup snapshots) {
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

        return new AnalyticsController.SankeyResponse(
                start.getYear(),
                label,
                graph.nodes().stream().map(AnalyticsController.SankeyNodeResponse::new).toList(),
                graph.links(),
                sankeyNotes(snapshots.startSnapshot(), snapshots.endSnapshot()));
    }

    private static SankeyFlowTotals sankeyFlowTotals(
            SankeyTransactionRollup transactions,
            BigDecimal totalAccountDelta) {
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

    private static void addIncomeLinks(
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

    private static void addExpenseLinks(
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

    private static void addExpenseGroupLinks(
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

    private static void addGroupedExpenseLeaves(SankeyGraph graph, int groupNode, Map<String, BigDecimal> leaves) {
        sortedEntriesDescending(leaves).forEach(entry -> {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(groupNode, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
        });
    }

    private static void addCollapsedExpenseLeaves(SankeyGraph graph, int expenseGroup, Map<String, BigDecimal> leaves) {
        for (Map.Entry<String, BigDecimal> entry : leaves.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                graph.link(expenseGroup, graph.node(entry.getKey()), entry.getValue(), entry.getKey());
            }
        }
    }

    private static void addGrowthLinks(
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

    private static BigDecimal impliedAccountDelta(
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

    private static void addAccountDeltaLinks(
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

    private static void addAccountDeltaBucketLinks(
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

    private static List<String> sankeyNotes(SnapshotRef startSnapshot, SnapshotRef endSnapshot) {
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

    private static BigDecimal sumValues(Map<String, BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values.values()) {
            total = total.add(value);
        }
        return total;
    }

    private static List<Map.Entry<String, BigDecimal>> sortedEntriesDescending(Map<String, BigDecimal> values) {
        return values.entrySet().stream()
                .sorted((left, right) -> right.getValue().compareTo(left.getValue()))
                .toList();
    }

    private static BigDecimal apportionedValue(BigDecimal shareBasis, BigDecimal totalShare, BigDecimal totalValue) {
        return shareBasis.multiply(totalValue).divide(totalShare, 10, RoundingMode.HALF_UP);
    }

    private record SankeyFlowTotals(
            BigDecimal income,
            BigDecimal expenses,
            BigDecimal growth,
            BigDecimal inflows) {
    }

    private static final class SankeyGraph {
        private final List<String> nodeLabels = new ArrayList<>();
        private final Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        private final List<AnalyticsController.SankeyLinkResponse> graphLinks = new ArrayList<>();

        int node(String name) {
            Integer existing = nodeIndex.get(name);
            if (existing != null) {
                return existing;
            }
            int index = nodeLabels.size();
            nodeIndex.put(name, index);
            nodeLabels.add(name);
            return index;
        }

        void link(int source, int target, BigDecimal value, String label) {
            graphLinks.add(new AnalyticsController.SankeyLinkResponse(source, target, value.doubleValue(), label));
        }

        List<String> nodes() {
            return nodeLabels;
        }

        List<AnalyticsController.SankeyLinkResponse> links() {
            return graphLinks;
        }
    }
}
