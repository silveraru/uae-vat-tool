import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * Small charts with no library. Marks are thin and the grid recedes, so the data is what stands out.
 * Every value is also in the table view on the page, and each X position answers to hover and focus.
 * Text keeps the ink colours: series colours only ever appear on marks and their keys.
 */

export interface Series { name: string; color: string; values: number[] }

const HEIGHT = 220;
const MARGIN = { top: 12, right: 72, bottom: 26, left: 56 };

function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => setWidth(Math.max(240, entries[0].contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return { ref, width };
}

/** Round axis ticks that cover the data, including zero so a bar's length means what it says. */
function ticks(min: number, max: number): number[] {
  const low = Math.min(0, min);
  const high = Math.max(0, max);
  if (low === high) return [0, 1];
  const rough = (high - low) / 4;
  const power = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * power).find((s) => s >= rough) ?? rough;
  const out: number[] = [];
  for (let v = Math.floor(low / step) * step; v < high + step * 0.999; v += step) out.push(Math.round(v * 1e6) / 1e6);
  return out;
}

export function compact(v: number): string {
  const abs = Math.abs(v);
  if (abs >= 1_000_000) return `${+(v / 1_000_000).toFixed(1)}M`;
  if (abs >= 10_000) return `${+(v / 1_000).toFixed(1)}K`;
  return new Intl.NumberFormat("en-AE", { maximumFractionDigits: 2 }).format(v);
}

/** One unit for the whole axis, so it never mixes 10K with 7,500. */
function axisFormat(axis: number[]): (v: number) => string {
  const top = Math.max(...axis.map(Math.abs));
  if (top >= 1_000_000) return (v) => (v === 0 ? "0" : `${+(v / 1_000_000).toFixed(2)}M`);
  if (top >= 10_000) return (v) => (v === 0 ? "0" : `${+(v / 1_000).toFixed(1)}K`);
  return compact;
}

function Frame({ width, labels, axis, y, children }: { width: number; labels: string[]; axis: number[]; y: (v: number) => number; children: ReactNode }) {
  const inner = width - MARGIN.left - MARGIN.right;
  // Thin the X labels when they would touch, keeping the first and spacing the rest evenly.
  const every = Math.max(1, Math.ceil((labels.length * 58) / inner));
  const tick = axisFormat(axis);
  return (
    <>
      {axis.map((v) => (
        <g key={v}>
          <line x1={MARGIN.left} x2={width - MARGIN.right} y1={y(v)} y2={y(v)} className={v === 0 ? "viz-baseline" : "viz-grid"} />
          <text x={MARGIN.left - 8} y={y(v) + 4} textAnchor="end" className="viz-axis">{tick(v)}</text>
        </g>
      ))}
      {labels.map((label, i) => i % every === 0 && (
        <text key={label} x={xAt(i, labels.length, inner)} y={HEIGHT - 8} textAnchor="middle" className="viz-axis">{label}</text>
      ))}
      {children}
    </>
  );
}

function xAt(i: number, count: number, inner: number) {
  return MARGIN.left + (count === 1 ? inner / 2 : ((i + 0.5) * inner) / count);
}

function Tooltip({ x, width, title, rows }: { x: number; width: number; title: string; rows: { name: string; color: string; value: string }[] }) {
  // Flips to the other side of the crosshair near the right edge, so it stays inside the chart.
  const side = x > width * 0.6 ? { right: width - x + 10 } : { left: x + 10 };
  return (
    <div className="viz-tooltip" role="status" style={side}>
      <div className="viz-tooltip-title">{title}</div>
      {rows.map((r) => (
        <div key={r.name} className="viz-tooltip-row">
          <span className="viz-key-line" style={{ background: r.color }} />
          <strong>{r.value}</strong> <span className="muted">{r.name}</span>
        </div>
      ))}
    </div>
  );
}

function Legend({ series, kind }: { series: Series[]; kind: "line" | "rect" }) {
  if (series.length < 2) return null;
  return (
    <ul className="viz-legend">
      {series.map((s) => <li key={s.name}><span className={kind === "line" ? "viz-key-line" : "viz-key-rect"} style={{ background: s.color }} />{s.name}</li>)}
    </ul>
  );
}

export function LineChart({ title, labels, series, format = compact }: { title: string; labels: string[]; series: Series[]; format?: (v: number) => string }) {
  const { ref, width } = useWidth();
  const [active, setActive] = useState<number | null>(null);
  const all = series.flatMap((s) => s.values);
  const axis = ticks(Math.min(...all), Math.max(...all));
  const inner = width - MARGIN.left - MARGIN.right;
  const plot = HEIGHT - MARGIN.top - MARGIN.bottom;
  const y = (v: number) => MARGIN.top + plot * (1 - (v - axis[0]) / (axis[axis.length - 1] - axis[0]));
  const band = inner / Math.max(1, labels.length);
  return (
    <figure className="viz" ref={ref} dir="ltr">
      <figcaption className="sr-only">{title}</figcaption>
      <svg width={width} height={HEIGHT} role="img" aria-label={title}>
        <Frame width={width} labels={labels} axis={axis} y={y}>
          {active !== null && <line className="viz-crosshair" x1={xAt(active, labels.length, inner)} x2={xAt(active, labels.length, inner)} y1={MARGIN.top} y2={MARGIN.top + plot} />}
          {series.map((s) => (
            <polyline key={s.name} fill="none" stroke={s.color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
              points={s.values.map((v, i) => `${xAt(i, labels.length, inner)},${y(v)}`).join(" ")} />
          ))}
          {series.map((s) => {
            const i = s.values.length - 1;
            return i >= 0 && (
              <g key={s.name}>
                <circle cx={xAt(i, labels.length, inner)} cy={y(s.values[i])} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />
                {series.length <= 4 && <text x={xAt(i, labels.length, inner) + 8} y={y(s.values[i]) + 4} className="viz-label">{format(s.values[i])}</text>}
              </g>
            );
          })}
          {active !== null && series.map((s) => <circle key={s.name} cx={xAt(active, labels.length, inner)} cy={y(s.values[active])} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />)}
          {labels.map((label, i) => (
            <rect key={label} x={xAt(i, labels.length, inner) - band / 2} y={MARGIN.top} width={band} height={plot} fill="transparent" tabIndex={0}
              aria-label={`${label}: ${series.map((s) => `${s.name} ${format(s.values[i])}`).join(", ")}`} className="viz-hit"
              onPointerEnter={() => setActive(i)} onPointerLeave={() => setActive(null)} onFocus={() => setActive(i)} onBlur={() => setActive(null)} />
          ))}
        </Frame>
      </svg>
      {active !== null && <Tooltip x={xAt(active, labels.length, inner)} width={width} title={labels[active]} rows={series.map((s) => ({ name: s.name, color: s.color, value: format(s.values[active]) }))} />}
      <Legend series={series} kind="line" />
    </figure>
  );
}

/** Columns from one baseline. With several series they stack, and the only ones that may go below zero are single series. */
export function ColumnChart({ title, labels, series, format = compact }: { title: string; labels: string[]; series: Series[]; format?: (v: number) => string }) {
  const { ref, width } = useWidth();
  const [active, setActive] = useState<number | null>(null);
  const stacked = series.length > 1;
  const totals = labels.map((_, i) => series.reduce((sum, s) => sum + s.values[i], 0));
  const axis = stacked ? ticks(0, Math.max(...totals)) : ticks(Math.min(...series[0].values), Math.max(...series[0].values));
  const inner = width - MARGIN.left - MARGIN.right;
  const plot = HEIGHT - MARGIN.top - MARGIN.bottom;
  const y = (v: number) => MARGIN.top + plot * (1 - (v - axis[0]) / (axis[axis.length - 1] - axis[0]));
  const band = inner / Math.max(1, labels.length);
  const barWidth = Math.min(24, Math.max(4, band * 0.6));
  return (
    <figure className="viz" ref={ref} dir="ltr">
      <figcaption className="sr-only">{title}</figcaption>
      <svg width={width} height={HEIGHT} role="img" aria-label={title}>
        <Frame width={width} labels={labels} axis={axis} y={y}>
          {labels.map((label, i) => {
            const x = xAt(i, labels.length, inner) - barWidth / 2;
            let base = 0;
            const top = series.reduce((last, s, si) => (s.values[i] !== 0 ? si : last), -1);
            return (
              <g key={label} className={active === i ? "viz-active" : undefined}>
                {series.map((s, si) => {
                  const v = s.values[i];
                  if (v === 0) return null;
                  const from = y(base);
                  const to = y(base + v);
                  base += v;
                  return <path key={s.name} fill={s.color} d={column(x, barWidth, from, to, si === top, stacked && si > 0)} />;
                })}
              </g>
            );
          })}
          {labels.map((label, i) => (
            <rect key={label} x={xAt(i, labels.length, inner) - band / 2} y={MARGIN.top} width={band} height={plot} fill="transparent" tabIndex={0}
              aria-label={`${label}: ${series.map((s) => `${s.name} ${format(s.values[i])}`).join(", ")}`} className="viz-hit"
              onPointerEnter={() => setActive(i)} onPointerLeave={() => setActive(null)} onFocus={() => setActive(i)} onBlur={() => setActive(null)} />
          ))}
        </Frame>
      </svg>
      {active !== null && <Tooltip x={xAt(active, labels.length, inner)} width={width} title={labels[active]} rows={series.map((s) => ({ name: s.name, color: s.color, value: format(s.values[active]) }))} />}
      <Legend series={series} kind="rect" />
    </figure>
  );
}

/** A column with a rounded data end and a square foot, leaving a 2px gap above the segment below. */
function column(x: number, w: number, from: number, to: number, roundedEnd: boolean, gapAtFoot: boolean) {
  const up = to < from;
  const foot = gapAtFoot ? from + (up ? -2 : 2) : from;
  const r = roundedEnd ? Math.min(4, w / 2, Math.abs(foot - to)) : 0;
  const end = up ? to + r : to - r;
  const sweep = up ? 1 : 0;
  return `M${x},${foot} L${x},${end} A${r},${r} 0 0 ${sweep} ${x + r},${to} L${x + w - r},${to} A${r},${r} 0 0 ${sweep} ${x + w},${end} L${x + w},${foot} Z`;
}
