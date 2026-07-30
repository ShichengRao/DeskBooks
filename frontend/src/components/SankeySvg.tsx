import { useMemo } from "react";
import type { SankeyResponse } from "../api/types";
import { colorAt } from "../lib/chartColors";

// Hand-computed Sankey layout rendered as plain SVG. Replaces the Plotly
// dependency (a ~4.7MB chunk whose only user was this chart) with ~200
// lines: nodes columned by graph depth, heights proportional to flow,
// ribbons as cubic ribbons with a hairline surface gap, direct labels on
// every node, native tooltips on ribbons, click-to-focus on nodes.

const NODE_W = 10;
const GAP = 12;
const PAD_X = 8;
const LABEL_W = 170;
const MIN_H = 4;
const INK = "#111827";
const INK2 = "#6b7280";
const SURFACE = "#ffffff";

type LaidNode = {
  index: number;
  name: string;
  depth: number;
  value: number;
  x: number;
  y: number;
  h: number;
  terminal: boolean;
};

function fmt(value: number): string {
  return value.toLocaleString(undefined, { maximumFractionDigits: 0 });
}

export function SankeySvg({
  data,
  colors,
  height = 480,
  width = 1040,
  onFocus,
}: {
  data: SankeyResponse;
  colors: string[];
  height?: number;
  width?: number;
  onFocus?: (name: string | null) => void;
}) {
  const layout = useMemo(() => {
    const n = data.nodes.length;
    // Depth = longest path from any root, so columns respect flow order.
    const depth = new Array(n).fill(0);
    for (let pass = 0; pass < n; pass += 1) {
      let changed = false;
      for (const link of data.links) {
        if (depth[link.target] < depth[link.source] + 1) {
          depth[link.target] = depth[link.source] + 1;
          changed = true;
        }
      }
      if (!changed) break;
    }
    const maxDepth = Math.max(0, ...depth);
    const inflow = new Array(n).fill(0);
    const outflow = new Array(n).fill(0);
    for (const link of data.links) {
      outflow[link.source] += link.value;
      inflow[link.target] += link.value;
    }
    const value = data.nodes.map((_, i) => Math.max(inflow[i], outflow[i]));

    const columns: number[][] = Array.from({ length: maxDepth + 1 }, () => []);
    data.nodes.forEach((_, i) => columns[depth[i]].push(i));
    for (const column of columns) column.sort((a, b) => value[b] - value[a]);

    const plotTop = 8;
    const plotH = height - plotTop - 8;
    const scale = Math.min(
      ...columns.map((column) => {
        const total = column.reduce((s, i) => s + value[i], 0);
        return total > 0 ? (plotH - (column.length - 1) * GAP) / total : Infinity;
      }),
    );

    const plotW = width - 2 * (LABEL_W + PAD_X);
    const colX = (d: number) =>
      LABEL_W + PAD_X + (maxDepth === 0 ? 0 : (plotW - NODE_W) * (d / maxDepth));

    const nodes: LaidNode[] = new Array(n);
    for (let d = 0; d <= maxDepth; d += 1) {
      const column = columns[d];
      const totalPx =
        column.reduce((s, i) => s + Math.max(value[i] * scale, MIN_H), 0) +
        (column.length - 1) * GAP;
      let y = plotTop + Math.max(0, (plotH - totalPx) / 2);
      for (const i of column) {
        const h = Math.max(value[i] * scale, MIN_H);
        nodes[i] = {
          index: i,
          name: data.nodes[i].name,
          depth: depth[i],
          value: value[i],
          x: colX(d),
          y,
          h,
          terminal: outflow[i] === 0,
        };
        y += h + GAP;
      }
    }

    // Ribbon anchor offsets stack flush down each node face.
    const outY = nodes.map((node) => node.y);
    const inY = nodes.map((node) => node.y);
    const ribbons = data.links.map((link) => {
      const s = nodes[link.source];
      const t = nodes[link.target];
      const h = link.value * scale;
      const y0 = outY[link.source];
      const y1 = inY[link.target];
      outY[link.source] += h;
      inY[link.target] += h;
      return { link, s, t, h, y0, y1 };
    });
    return { nodes, ribbons, maxDepth };
  }, [data, height, width]);

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      style={{ width: "100%", height: "auto", fontFamily: "inherit" }}
      role="img"
      aria-label="Sankey diagram"
    >
      {layout.ribbons.map(({ link, s, t, h, y0, y1 }, i) => {
        const x0 = s.x + NODE_W;
        const x1 = t.x;
        const mx = (x0 + x1) / 2;
        const color = colorAt(colors, s.index);
        return (
          <path
            key={`ribbon-${i}`}
            d={`M${x0},${y0} C${mx},${y0} ${mx},${y1} ${x1},${y1} L${x1},${y1 + h} C${mx},${y1 + h} ${mx},${y0 + h} ${x0},${y0 + h} Z`}
            fill={color}
            opacity={0.32}
            stroke={SURFACE}
            strokeWidth={1}
          >
            <title>{`${s.name} → ${t.name} · $${fmt(link.value)}`}</title>
          </path>
        );
      })}
      {layout.nodes.map((node) => {
        const color = colorAt(colors, node.index);
        const first = node.depth === 0;
        // Terminal nodes label to the right even when they end early
        // (e.g. a residual bucket beside a column of pass-through hubs).
        const last = node.depth === layout.maxDepth || node.terminal;
        const middle = !first && !last;
        const cy = node.y + node.h / 2;
        const inline = node.h < 34;
        return (
          <g
            key={node.index}
            onClick={onFocus ? () => onFocus(node.name) : undefined}
            style={onFocus ? { cursor: "pointer" } : undefined}
          >
            <rect x={node.x} y={node.y} width={NODE_W} height={node.h} rx={3} fill={color}>
              <title>{`${node.name} · $${fmt(node.value)}`}</title>
            </rect>
            {middle ? (
              <text x={node.x + NODE_W / 2} y={Math.max(node.y - 8, 12)} textAnchor="middle" fontSize={12.5} fontWeight={600} fill={INK}>
                {node.name}
                <tspan fontWeight={400} fill={INK2}>{` $${fmt(node.value)}`}</tspan>
              </text>
            ) : inline ? (
              <text
                x={first ? node.x - 8 : node.x + NODE_W + 8}
                y={cy + 4}
                textAnchor={first ? "end" : "start"}
                fontSize={12.5}
                fontWeight={600}
                fill={INK}
              >
                {node.name}
                <tspan fontWeight={400} fontSize={11.5} fill={INK2}>{` $${fmt(node.value)}`}</tspan>
              </text>
            ) : (
              <>
                <text x={first ? node.x - 8 : node.x + NODE_W + 8} y={cy - 3} textAnchor={first ? "end" : "start"} fontSize={12.5} fontWeight={600} fill={INK}>
                  {node.name}
                </text>
                <text x={first ? node.x - 8 : node.x + NODE_W + 8} y={cy + 12} textAnchor={first ? "end" : "start"} fontSize={11.5} fill={INK2}>
                  ${fmt(node.value)}
                </text>
              </>
            )}
          </g>
        );
      })}
    </svg>
  );
}
