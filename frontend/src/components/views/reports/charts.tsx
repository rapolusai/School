"use client";

import { useId } from "react";
import { translateOr, type Translate } from "@/lib/i18n";
import type { FunnelStage } from "@/lib/types";
import { formatPercent } from "../attendance/attendance-shared";

/*
 * Small charts drawn with SVG and CSS only (no chart library). Colours come from the theme tokens through
 * CSS classes in globals.css, so they follow light and dark mode. Each chart carries a visually hidden
 * table with the same numbers for screen readers.
 */

export type LinePoint = { key: string; label: string; value: number | null };

/** The y range for percentages: 10 points below the lowest value (rounded down to a ten) up to 100. */
export function percentDomain(values: (number | null)[]): [number, number] {
  const present = values.filter((v): v is number => v !== null && Number.isFinite(v));
  if (present.length === 0) return [0, 100];
  const lowest = Math.min(...present);
  return [Math.max(0, Math.floor(lowest / 10) * 10 - 10), 100];
}

/** Horizontal position (0–100) of point `index` of `count`; a single point sits in the middle. */
export function xAt(index: number, count: number): number {
  return count <= 1 ? 50 : (index / (count - 1)) * 100;
}

/** Vertical position (0 at the top, 100 at the bottom) of a value within the domain. */
export function yAt(value: number, [low, high]: [number, number]): number {
  if (high <= low) return 50;
  const clamped = Math.min(high, Math.max(low, value));
  return 100 - ((clamped - low) / (high - low)) * 100;
}

const round = (n: number) => Math.round(n * 100) / 100;

/** "x,y x,y …" polyline runs, broken where a value is missing (a day nobody marked). */
export function lineRuns(values: (number | null)[], domain: [number, number]): string[] {
  const runs: string[] = [];
  let current: string[] = [];
  values.forEach((value, i) => {
    if (value === null || !Number.isFinite(value)) {
      if (current.length) runs.push(current.join(" "));
      current = [];
      return;
    }
    current.push(`${round(xAt(i, values.length))},${round(yAt(value, domain))}`);
  });
  if (current.length) runs.push(current.join(" "));
  return runs;
}

/** Rounds up to 1, 2, 2.5 or 5 times a power of ten, for an axis maximum. */
export function niceCeil(value: number): number {
  if (!(value > 0)) return 0;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (value <= step * magnitude + 1e-9) return step * magnitude;
  }
  return 10 * magnitude;
}

const short = (n: number) => (n >= 100 ? String(Math.round(n)) : String(Number(n.toFixed(1))));

/** Paise → a short rupee label for an axis: ₹950, ₹12K, ₹1.5L, ₹2Cr. */
export function compactRupees(paise: number): string {
  const rupees = Math.max(0, paise) / 100;
  if (rupees >= 1e7) return `₹${short(rupees / 1e7)}Cr`;
  if (rupees >= 1e5) return `₹${short(rupees / 1e5)}L`;
  if (rupees >= 1e3) return `₹${short(rupees / 1e3)}K`;
  return `₹${Math.round(rupees)}`;
}

function ScreenReaderTable({
  caption,
  head,
  rows,
}: {
  caption: string;
  head: [string, string];
  rows: { key: string; label: string; value: string }[];
}) {
  return (
    <table className="sr-only">
      <caption>{caption}</caption>
      <thead>
        <tr>
          <th scope="col">{head[0]}</th>
          <th scope="col">{head[1]}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key}>
            <th scope="row">{row.label}</th>
            <td>{row.value}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** A line over time, e.g. attendance % for the last 30 school days. Gaps where a value is missing. */
export function LineChart({
  points,
  caption,
  head,
  formatValue,
  testId,
}: {
  points: LinePoint[];
  caption: string;
  head: [string, string];
  formatValue: (value: number) => string;
  testId?: string;
}) {
  const values = points.map((p) => p.value);
  const domain = percentDomain(values);
  const runs = lineRuns(values, domain);
  const ticks = [domain[1], (domain[0] + domain[1]) / 2, domain[0]];
  const first = points[0];
  const last = points.length > 1 ? points[points.length - 1] : undefined;
  return (
    <figure className="chart" data-testid={testId}>
      <div className="chart-frame" aria-hidden="true">
        {ticks.map((tick) => (
          <div key={tick} className="chart-gridline" style={{ top: `${yAt(tick, domain)}%` }}>
            <span className="chart-ylabel">{Math.round(tick)}</span>
          </div>
        ))}
        <svg className="chart-svg" viewBox="0 0 100 100" preserveAspectRatio="none" focusable="false">
          {runs.map((run) => (
            <polyline key={run} className="chart-line" points={run} vectorEffect="non-scaling-stroke" />
          ))}
        </svg>
        {points.map((point, i) =>
          point.value === null ? null : (
            <span
              key={point.key}
              className="chart-dot"
              style={{ left: `${xAt(i, points.length)}%`, top: `${yAt(point.value, domain)}%` }}
              title={`${point.label}: ${formatValue(point.value)}`}
            />
          ),
        )}
      </div>
      <div className="chart-xaxis" aria-hidden="true">
        <span>{first?.label}</span>
        <span>{last?.label}</span>
      </div>
      <ScreenReaderTable
        caption={caption}
        head={head}
        rows={points.map((p) => ({ key: p.key, label: p.label, value: p.value === null ? "—" : formatValue(p.value) }))}
      />
    </figure>
  );
}

export type BarDatum = {
  key: string;
  /** full label for the tooltip and the screen-reader table */
  label: string;
  /** short axis label, shown only when `tick` is set */
  tick?: string;
  value: number;
  highlight?: boolean;
};

/** Vertical bars, e.g. fee collections by day this month. */
export function BarChart({
  bars,
  caption,
  head,
  formatValue,
  formatAxis,
  testId,
}: {
  bars: BarDatum[];
  caption: string;
  head: [string, string];
  formatValue: (value: number) => string;
  formatAxis: (value: number) => string;
  testId?: string;
}) {
  const max = niceCeil(Math.max(0, ...bars.map((b) => b.value)));
  const height = (value: number) => (max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0);
  return (
    <figure className="chart" data-testid={testId}>
      <div className="chart-frame" aria-hidden="true">
        {[1, 0.5, 0].map((share) => (
          <div key={share} className="chart-gridline" style={{ top: `${100 - share * 100}%` }}>
            <span className="chart-ylabel">{max > 0 || share === 0 ? formatAxis(max * share) : ""}</span>
          </div>
        ))}
        <div className="chart-bars">
          {bars.map((bar) => (
            <span key={bar.key} className="chart-col" title={`${bar.label}: ${formatValue(bar.value)}`}>
              <span
                className={`chart-bar${bar.highlight ? " is-highlight" : ""}${bar.value > 0 ? " has-value" : ""}`}
                style={{ height: `${height(bar.value)}%` }}
              />
            </span>
          ))}
        </div>
      </div>
      <div className="chart-xticks" aria-hidden="true">
        {bars.map((bar) => (
          <span key={bar.key}>{bar.tick ?? ""}</span>
        ))}
      </div>
      <ScreenReaderTable
        caption={caption}
        head={head}
        rows={bars.map((b) => ({ key: b.key, label: b.label, value: formatValue(b.value) }))}
      />
    </figure>
  );
}

export type FunnelRow = {
  key: string;
  label: string;
  value: number;
  /** e.g. "60% of enquiries"; empty for the first stage */
  note?: string;
};

/** Horizontal bars narrowing stage by stage, e.g. enquiry → admitted. */
export function FunnelBars({ rows, caption, testId }: { rows: FunnelRow[]; caption: string; testId?: string }) {
  const id = useId();
  const max = Math.max(0, ...rows.map((r) => r.value));
  return (
    <figure className="chart" data-testid={testId} aria-labelledby={`${id}-caption`}>
      <figcaption id={`${id}-caption`} className="sr-only">
        {caption}
      </figcaption>
      <ol className="funnel">
        {rows.map((row) => (
          <li key={row.key} className="funnel-row">
            <span className="funnel-label">{row.label}</span>
            <span className="funnel-track" aria-hidden="true">
              <span className="funnel-bar" style={{ width: `${max > 0 ? (row.value / max) * 100 : 0}%` }} />
            </span>
            <span className="funnel-value">
              <b className="num">{row.value}</b>
              {row.note ? <span className="funnel-note">{row.note}</span> : null}
            </span>
          </li>
        ))}
      </ol>
    </figure>
  );
}

/** Funnel stages as chart rows: how many reached each stage and the share of the stage before. Exported for tests. */
export function funnelRows(stages: FunnelStage[], t: Translate): FunnelRow[] {
  return stages.map((stage) => ({
    key: stage.stage,
    label: translateOr(t, `admissions.stage.${stage.stage}`, stage.stage),
    value: stage.reached,
    note:
      stage.fromPrevious === null ? undefined : t("reports.funnel.fromPrevious", { percent: formatPercent(stage.fromPrevious) }),
  }));
}
