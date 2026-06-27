import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { Dashboard } from "./pages/Dashboard";
import { Transactions } from "./pages/Transactions";
import { NetWorth } from "./pages/NetWorth";
import { Planning } from "./pages/Planning";
import { Budgets } from "./pages/Budgets";
import { Import } from "./pages/Import";
import { Rules } from "./pages/Rules";
import { Reconcile } from "./pages/Reconcile";
import { Backups } from "./pages/Backups";

// Analytics pulls in Plotly (~5MB). Lazy-load it so it only costs users who
// visit that route.
const Analytics = lazy(() =>
  import("./pages/Analytics").then((m) => ({ default: m.Analytics })),
);

function LazyFallback() {
  return <div className="p-8 text-sm text-ink-500">Loading…</div>;
}

export function App() {
  return (
    <ErrorBoundary>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="transactions" element={<Transactions />} />
          <Route path="networth" element={<NetWorth />} />
          <Route path="planning" element={<Planning />} />
          <Route path="budgets" element={<Budgets />} />
          <Route
            path="analytics"
            element={
              <Suspense fallback={<LazyFallback />}>
                <Analytics />
              </Suspense>
            }
          />
          <Route path="import" element={<Import />} />
          <Route path="reconcile" element={<Reconcile />} />
          <Route path="rules" element={<Rules />} />
          <Route path="backups" element={<Backups />} />
        </Route>
      </Routes>
    </ErrorBoundary>
  );
}
