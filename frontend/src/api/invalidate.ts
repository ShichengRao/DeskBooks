import type { QueryClient } from "@tanstack/react-query";

// Anything that aggregates Transactions. Use after any mutation that adds,
// removes, recategorizes, or rolls back transactions so the analytics views
// don't stay stale (refetchOnWindowFocus is off).
export function invalidateTxQueries(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: ["transactions"] });
  qc.invalidateQueries({ queryKey: ["monthly"] });
  qc.invalidateQueries({ queryKey: ["sankey"] });
  qc.invalidateQueries({ queryKey: ["recurring"] });
}

// Anything that aggregates NetWorthSnapshots.
export function invalidateSnapshotQueries(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: ["snapshots"] });
  qc.invalidateQueries({ queryKey: ["nw-series"] });
  qc.invalidateQueries({ queryKey: ["goal-progress"] });
}
