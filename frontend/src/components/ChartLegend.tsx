import clsx from "clsx";

export function ChartLegend({
  payload,
  focusedKey,
  onToggle,
}: {
  payload?: Array<{ dataKey?: string | number; value?: string; color?: string }>;
  focusedKey: string | null;
  onToggle: (key: string) => void;
}) {
  if (!payload?.length) return null;
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 pt-2 text-xs">
      {payload.map((item) => {
        const key = String(item.dataKey ?? item.value ?? "");
        if (!key) return null;
        const active = focusedKey === null || focusedKey === key;
        return (
          <button
            key={key}
            type="button"
            onClick={() => onToggle(key)}
            className={clsx(
              "inline-flex items-center gap-1 rounded px-1.5 py-0.5 transition-colors",
              active ? "text-ink-800 hover:bg-ink-100" : "text-ink-400 hover:bg-ink-50",
            )}
            title={focusedKey === key ? "Show all series" : `Only show ${item.value ?? key}`}
          >
            <span
              className="h-2.5 w-2.5 rounded-sm"
              style={{ backgroundColor: item.color ?? "#7a8392" }}
            />
            <span>{item.value ?? key}</span>
          </button>
        );
      })}
    </div>
  );
}
