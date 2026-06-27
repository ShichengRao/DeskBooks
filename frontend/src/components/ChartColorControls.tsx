import { useEffect, useState } from "react";
import {
  CHART_PALETTES,
  type ChartPaletteId,
  DEFAULT_CHART_COLORS,
  readStoredChartColors,
  writeStoredChartColors,
} from "../lib/chartColors";

export function useChartColors() {
  const [state, setState] = useState(readStoredChartColors);

  useEffect(() => {
    writeStoredChartColors(state);
  }, [state]);

  const setPaletteId = (paletteId: ChartPaletteId) => {
    if (paletteId === "custom") {
      setState((s) => ({ paletteId, colors: s.colors.length ? s.colors : DEFAULT_CHART_COLORS }));
      return;
    }
    setState({ paletteId, colors: CHART_PALETTES[paletteId] });
  };

  const setColor = (index: number, color: string) => {
    setState((s) => {
      const colors = [...s.colors];
      colors[index] = color;
      return { paletteId: "custom", colors };
    });
  };

  return { ...state, setPaletteId, setColor };
}

export function ChartColorControls({
  paletteId,
  colors,
  onPaletteChange,
  onColorChange,
}: {
  paletteId: ChartPaletteId;
  colors: string[];
  onPaletteChange: (paletteId: ChartPaletteId) => void;
  onColorChange: (index: number, color: string) => void;
}) {
  return (
    <div className="flex items-center gap-2 flex-wrap text-xs">
      <label className="flex items-center gap-1">
        <span className="text-ink-500">Colors</span>
        <select
          className="input max-w-[9rem]"
          value={paletteId}
          onChange={(e) => onPaletteChange(e.target.value as ChartPaletteId)}
        >
          <option value="default">Default</option>
          <option value="contrast">High contrast</option>
          <option value="muted">Muted</option>
          <option value="custom">Custom</option>
        </select>
      </label>
      <div className="flex items-center gap-1">
        {colors.slice(0, 10).map((color, index) => (
          <input
            key={`${index}-${color}`}
            type="color"
            value={color}
            onChange={(e) => onColorChange(index, e.target.value)}
            className="h-7 w-7 rounded border border-ink-200 bg-white p-0.5"
            title={`Color ${index + 1}`}
          />
        ))}
      </div>
    </div>
  );
}
