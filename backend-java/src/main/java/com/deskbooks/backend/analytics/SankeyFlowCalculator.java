package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.util.Map;

final class SankeyFlowCalculator {
    private SankeyFlowCalculator() {
    }

    static SankeyFlowTotals totals(SankeyTransactionRollup transactions, BigDecimal totalAccountDelta) {
        BigDecimal expenseTotal = expenseTotal(transactions.expenses());
        BigDecimal incomeTotal = incomeTotal(transactions.incomeLeaves());
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

    static BigDecimal impliedAccountDelta(
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

    private static BigDecimal expenseTotal(Map<String, Map<String, BigDecimal>> expenses) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, BigDecimal> leaves : expenses.values()) {
            BigDecimal groupTotal = SankeyAmounts.sumValues(leaves);
            if (groupTotal.compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(groupTotal);
            }
        }
        return total;
    }

    private static BigDecimal incomeTotal(Map<String, BigDecimal> incomeLeaves) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : incomeLeaves.values()) {
            if (value.compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(value);
            }
        }
        return total;
    }
}
