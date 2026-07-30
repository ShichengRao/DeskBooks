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

    // Reduce ribbon crossings: reorder each column by the flow-weighted
    // mean position of its neighbors (barycenter heuristic), sweeping
    // forward then backward a few times. Value order above is the initial
    // state and the tiebreak, so layouts stay deterministic.
    const inLinks: { other: number; v: number }[][] = data.nodes.map(() => []);
    const outLinks: { other: number; v: number }[][] = data.nodes.map(() => []);
    for (const link of data.links) {
      outLinks[link.source].push({ other: link.target, v: link.value });
      inLinks[link.target].push({ other: link.source, v: link.value });
    }
    const order = new Array(n).fill(0);
    const reindex = () => {
      for (const column of columns) column.forEach((node, i) => (order[node] = i));
    };
    const barycenter = (node: number, neighbors: { other: number; v: number }[]) => {
      let weight = 0;
      let sum = 0;
      for (const { other, v } of neighbors) {
        weight += v;
        sum += v * order[other];
      }
      return weight > 0 ? sum / weight : order[node];
    };
    for (let sweep = 0; sweep < 4; sweep += 1) {
      reindex();
      const forward = sweep % 2 === 0;
      const range = forward ? columns.slice(1) : columns.slice(0, -1).reverse();
      for (const column of range) {
        const score = new Map(
          column.map((node) => [node, barycenter(node, forward ? inLinks[node] : outLinks[node])]),
        );
        column.sort((a, b) => score.get(a)! - score.get(b)! || order[a] - order[b]);
        column.forEach((node, i) => (order[node] = i));
      }
    }

    const plotTop = 8;
    const plotH = height - plotTop - 8;
    const scale = Math.min(
      ...columns.map((column) => {
        const total = column.reduce((s, i) => s + value[i], 0);
        return total > 0 ? (plotH - (column.length - 1) * GAP) / total : Infinity;
      }),
    );

    // Size the label gutters to the longest label on each side so nothing
    // clips at the edges (LABEL_W is only the floor).
    const gutterFor = (indices: number[]) =>
      Math.min(
        240,
        Math.max(LABEL_W, ...indices.map((i) => `${data.nodes[i].name} $${fmt(value[i])}`.length * 6.8 + 10)),
      );
    const leftGutter = gutterFor(data.nodes.map((_, i) => i).filter((i) => depth[i] === 0));
    const rightGutter = gutterFor(
      data.nodes.map((_, i) => i).filter((i) => depth[i] === maxDepth || outflow[i] === 0),
    );
    const plotW = width - (leftGutter + PAD_X) - (rightGutter + PAD_X);
    const colX = (d: number) =>
      leftGutter + PAD_X + (maxDepth === 0 ? 0 : (plotW - NODE_W) * (d / maxDepth));

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

    // Ribbon anchors stack flush down each node face in the counterpart's
    // vertical order, so a node's fan of ribbons never crosses itself.
    const outIdx: number[][] = data.nodes.map(() => []);
    const inIdx: number[][] = data.nodes.map(() => []);
    data.links.forEach((link, i) => {
      outIdx[link.source].push(i);
      inIdx[link.target].push(i);
    });
    const byCounterpart = (side: "source" | "target") => (a: number, b: number) => {
      const na = nodes[data.links[a][side]];
      const nb = nodes[data.links[b][side]];
      return na.y - nb.y || na.x - nb.x;
    };
    const y0s = new Array(data.links.length).fill(0);
    const y1s = new Array(data.links.length).fill(0);
    nodes.forEach((node, i) => {
      let y = node.y;
      for (const li of [...outIdx[i]].sort(byCounterpart("target"))) {
        y0s[li] = y;
        y += data.links[li].value * scale;
      }
      y = node.y;
      for (const li of [...inIdx[i]].sort(byCounterpart("source"))) {
        y1s[li] = y;
        y += data.links[li].value * scale;
      }
    });
    const ribbons = data.links.map((link, i) => ({
      link,
      s: nodes[link.source],
      t: nodes[link.target],
      h: link.value * scale,
      y0: y0s[i],
      y1: y1s[i],
    }));

    // Label placement. Small nodes get one-line labels at node height —
    // to the right for endpoints, to the LEFT for small pass-through hubs
    // (a centered title above a 4px hub has nowhere to dodge in a dense
    // cluster). Tall nodes keep their classic spots (title above hubs,
    // two-line beside endpoints) and act as fixed obstacles. Movable
    // labels are pushed down until nothing horizontally overlapping sits
    // closer than one line.
    const labelY = nodes.map((node) => node.y + node.h / 2 + 4);
    const labelWidth = (node: LaidNode) => `${node.name} $${fmt(node.value)}`.length * 6.8;
    const isSide = (node: LaidNode) => node.depth === 0 || node.depth === maxDepth || node.terminal;
    type LabelBox = {
      index: number;
      movable: boolean;
      twoLine: boolean;
      baseline: number;
      start: number;
      end: number;
    };
    // A hub's left label must not run through the previous column's
    // nodes: use the full "name $value" when the inter-column gap fits
    // it, just the name when tight (the value stays in the tooltip), and
    // fall back to the centered title when not even the name fits.
    const colStep = maxDepth === 0 ? plotW : (plotW - NODE_W) / maxDepth;
    const hubLabelRoom = colStep - NODE_W - 12;
    const labelMode: ("full" | "name" | "title")[] = nodes.map(() => "full");
    const boxes: LabelBox[] = nodes.map((node) => {
      const w = labelWidth(node);
      const small = node.h < 34;
      const cy = node.y + node.h / 2;
      const titled = (!isSide(node) && !small) || (!isSide(node) && node.name.length * 6.8 > hubLabelRoom);
      if (titled) {
        if (!isSide(node)) labelMode[node.index] = "title";
        const cx = node.x + NODE_W / 2;
        return {
          index: node.index,
          movable: false,
          twoLine: false,
          baseline: Math.max(node.y - 8, 12),
          start: cx - w / 2,
          end: cx + w / 2,
        };
      }
      const left = node.depth === 0 || !isSide(node);
      if (!isSide(node) && w > hubLabelRoom) {
        labelMode[node.index] = "name";
      }
      if (isSide(node) && !left && node.depth < maxDepth) {
        // A right-side label mid-plot must not run under a downstream
        // node; drop to the bare name when the full label would.
        const startX = node.x + NODE_W + 8;
        const blockedBy = (labelW: number) =>
          nodes.some(
            (o) =>
              o.index !== node.index &&
              o.x > node.x &&
              o.x < startX + labelW + 4 &&
              o.y < cy + 7 &&
              o.y + o.h > cy - 6,
          );
        if (blockedBy(w) && !blockedBy(node.name.length * 6.8)) {
          labelMode[node.index] = "name";
        }
      }
      const shownW = labelMode[node.index] === "name" ? node.name.length * 6.8 : w;
      // Two-line labels only where a gutter guarantees room; tall
      // terminals mid-plot stay single-line so they can dodge neighbors.
      const twoLine = !small && (node.depth === 0 || node.depth === maxDepth);
      return {
        index: node.index,
        movable: !twoLine,
        twoLine,
        baseline: twoLine ? cy - 3 : cy + 4,
        start: left ? node.x - 8 - shownW : node.x + NODE_W + 8,
        end: left ? node.x - 8 : node.x + NODE_W + 8 + shownW,
      };
    });
    // Resolve pairwise: the lower label of an overlapping pair moves down
    // below the upper one; a movable crowding a FIXED label from above
    // jumps down past it instead. A few iterations reach a fixed point.
    for (let iter = 0; iter < 4; iter += 1) {
      let changed = false;
      boxes.sort((a, b) => a.baseline - b.baseline);
      for (let i = 0; i < boxes.length; i += 1) {
        for (let j = i + 1; j < boxes.length; j += 1) {
          const upper = boxes[i];
          const lower = boxes[j];
          if (upper.start >= lower.end || lower.start >= upper.end) continue;
          const gap = upper.twoLine ? 28 : 13;
          if (lower.baseline - upper.baseline >= gap) continue;
          if (lower.movable) {
            lower.baseline = upper.baseline + gap;
            changed = true;
          } else if (upper.movable) {
            upper.baseline = lower.baseline + (lower.twoLine ? 28 : 13);
            changed = true;
          }
        }
      }
      if (!changed) break;
    }
    for (const box of boxes) {
      if (box.movable) labelY[box.index] = box.baseline;
    }
    return { nodes, ribbons, labelY, labelMode, maxDepth };
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
        const singleLine = inline || (!first && node.depth !== layout.maxDepth);
        return (
          <g
            key={node.index}
            onClick={onFocus ? () => onFocus(node.name) : undefined}
            style={onFocus ? { cursor: "pointer" } : undefined}
          >
            <rect x={node.x} y={node.y} width={NODE_W} height={node.h} rx={3} fill={color}>
              <title>{`${node.name} · $${fmt(node.value)}`}</title>
            </rect>
            {middle && (!inline || layout.labelMode[node.index] === "title") ? (
              <text x={node.x + NODE_W / 2} y={Math.max(node.y - 8, 12)} textAnchor="middle" fontSize={12.5} fontWeight={600} fill={INK}>
                {node.name}
                <tspan fontWeight={400} fill={INK2}>{` $${fmt(node.value)}`}</tspan>
              </text>
            ) : singleLine ? (
              <text
                x={first || middle ? node.x - 8 : node.x + NODE_W + 8}
                y={layout.labelY[node.index]}
                textAnchor={first || middle ? "end" : "start"}
                fontSize={12.5}
                fontWeight={600}
                fill={INK}
              >
                {node.name}
                {layout.labelMode[node.index] !== "name" && (
                  <tspan fontWeight={400} fontSize={11.5} fill={INK2}>{` $${fmt(node.value)}`}</tspan>
                )}
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
