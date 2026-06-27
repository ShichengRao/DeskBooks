export const currency = (val: string | number | null | undefined, opts?: { showSign?: boolean }) => {
  if (val === null || val === undefined || val === "") return "—";
  const n = typeof val === "string" ? parseFloat(val) : val;
  if (Number.isNaN(n)) return "—";
  const formatted = n.toLocaleString("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  if (opts?.showSign && n > 0) return `+${formatted}`;
  return formatted;
};

export const compactCurrency = (val: string | number | null | undefined) => {
  if (val === null || val === undefined || val === "") return "—";
  const n = typeof val === "string" ? parseFloat(val) : val;
  if (Number.isNaN(n)) return "—";
  const abs = Math.abs(n);
  if (abs >= 1_000_000) return `$${(n / 1_000_000).toFixed(2)}M`;
  if (abs >= 10_000) return `$${(n / 1_000).toFixed(1)}k`;
  if (abs >= 1_000) return `$${(n / 1_000).toFixed(2)}k`;
  return `$${n.toFixed(0)}`;
};

export const percent = (val: number | null | undefined, digits = 1) => {
  if (val === null || val === undefined || Number.isNaN(val)) return "—";
  return `${val.toFixed(digits)}%`;
};

const localDate = (s: string) => {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);
  if (!match) return new Date(s);
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
};

export const dateLabel = (s: string | null | undefined) => {
  if (!s) return "—";
  const d = localDate(s);
  return d.toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric" });
};

export const monthLabel = (yyyymm: string) => {
  const [y, m] = yyyymm.split("-").map(Number);
  // Full 4-digit year — "Apr 26" is too easily misread as April 26th.
  return new Date(y, m - 1, 1).toLocaleDateString("en-US", { month: "short", year: "numeric" });
};

export const shortDateLabel = (s: string | null | undefined) => {
  // For chart axes where horizontal space matters: "Apr 2026" not
  // "Apr 26, 2026" (which dateLabel produces).
  if (!s) return "—";
  const d = localDate(s);
  return d.toLocaleDateString("en-US", { month: "short", year: "numeric" });
};

export const num = (val: string | number | null | undefined) => {
  if (val === null || val === undefined || val === "") return 0;
  const n = typeof val === "string" ? parseFloat(val) : val;
  return Number.isNaN(n) ? 0 : n;
};
