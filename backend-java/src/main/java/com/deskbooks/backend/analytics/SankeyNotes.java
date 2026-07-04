package com.deskbooks.backend.analytics;

import java.util.List;

final class SankeyNotes {
    private SankeyNotes() {
    }

    static List<String> build(SnapshotRef startSnapshot, SnapshotRef endSnapshot) {
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
}
