export type ChartPaletteId = "default" | "contrast" | "muted" | "custom";

export const CHART_PALETTES: Record<Exclude<ChartPaletteId, "custom">, string[]> = {
  default: [
    "#2a70ff",
    "#22a559",
    "#7c3aed",
    "#e08a16",
    "#dc2a3c",
    "#0891b2",
    "#6366f1",
    "#db2777",
    "#65a30d",
    "#0f766e",
  ],
  contrast: [
    "#005f73",
    "#ee9b00",
    "#9b2226",
    "#0a9396",
    "#ca6702",
    "#3a0ca3",
    "#2d6a4f",
    "#b7410e",
    "#4361ee",
    "#6a4c93",
  ],
  muted: [
    "#4c78a8",
    "#59a14f",
    "#b279a2",
    "#f28e2b",
    "#e15759",
    "#76b7b2",
    "#edc948",
    "#9c755f",
    "#bab0ac",
    "#8cd17d",
  ],
};

export const DEFAULT_CHART_COLORS = CHART_PALETTES.default;

const STORAGE_KEY = "personal-finance.chartColors.v1";

export function readStoredChartColors(): { paletteId: ChartPaletteId; colors: string[] } {
  if (typeof window === "undefined") {
    return { paletteId: "default", colors: DEFAULT_CHART_COLORS };
  }
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return { paletteId: "default", colors: DEFAULT_CHART_COLORS };
    const parsed = JSON.parse(raw) as { paletteId?: ChartPaletteId; colors?: string[] };
    const paletteId = parsed.paletteId ?? "default";
    const fallback = paletteId !== "custom" && paletteId in CHART_PALETTES
      ? CHART_PALETTES[paletteId as Exclude<ChartPaletteId, "custom">]
      : DEFAULT_CHART_COLORS;
    const colors = parsed.colors?.length ? parsed.colors : fallback;
    return { paletteId, colors };
  } catch {
    return { paletteId: "default", colors: DEFAULT_CHART_COLORS };
  }
}

export function writeStoredChartColors(value: { paletteId: ChartPaletteId; colors: string[] }) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
}

export function colorAt(colors: string[], index: number) {
  return colors[index % colors.length] ?? DEFAULT_CHART_COLORS[index % DEFAULT_CHART_COLORS.length];
}
