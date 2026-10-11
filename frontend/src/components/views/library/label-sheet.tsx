"use client";

import { code128Bars } from "@/lib/code128";
import { useI18n } from "@/lib/i18n";
import type { LabelCopy } from "@/lib/types";
import { shortTitle } from "./library-shared";

/* Printed labels: an A4 sheet of 3 × 8 barcode labels, or of 5 × 13 spine labels with the call number. */

export type LabelKind = "barcode" | "spine";

/** Labels on one A4 sheet of each kind. */
export const SHEET_LAYOUT: Record<LabelKind, { columns: number; rows: number }> = {
  barcode: { columns: 3, rows: 8 },
  spine: { columns: 5, rows: 13 },
};

export const labelsPerSheet = (kind: LabelKind) => SHEET_LAYOUT[kind].columns * SHEET_LAYOUT[kind].rows;

/**
 * The copies laid out on sheets: `skip` empty places first (labels already used on a part sheet), then one place per
 * copy, `labelsPerSheet` to a sheet. Pure and exported for tests.
 */
export function sheetsOf<T>(copies: readonly T[], kind: LabelKind, skip = 0): (T | null)[][] {
  const size = labelsPerSheet(kind);
  const places: (T | null)[] = [...Array.from({ length: Math.max(0, Math.min(skip, size - 1)) }, () => null), ...copies];
  const sheets: (T | null)[][] = [];
  for (let i = 0; i < places.length; i += size) sheets.push(places.slice(i, i + size));
  return sheets;
}

/** A Code 128 barcode as SVG bars (black on white), one SVG unit per module. */
export function Barcode({ value, height = 28 }: { value: string; height?: number }) {
  const { t } = useI18n();
  let drawn: ReturnType<typeof code128Bars>;
  try {
    drawn = code128Bars(value);
  } catch {
    return <span className="label-barcode-error">{t("library.labels.cannotEncode")}</span>;
  }
  return (
    <svg
      className="label-barcode"
      viewBox={`0 0 ${drawn.modules} ${height}`}
      preserveAspectRatio="none"
      shapeRendering="crispEdges"
      role="img"
      aria-label={t("library.labels.barcodeOf", { value })}
      data-testid="barcode"
      data-modules={drawn.modules}
    >
      <rect x={0} y={0} width={drawn.modules} height={height} fill="#fff" />
      {drawn.bars.map(([x, width]) => (
        <rect key={x} x={x} y={0} width={width} height={height} fill="#000" />
      ))}
    </svg>
  );
}

/** One or more A4 sheets of labels, for the screen preview and for printing. */
export function LabelSheet({ copies, kind, skip = 0 }: { copies: readonly LabelCopy[]; kind: LabelKind; skip?: number }) {
  const { t } = useI18n();
  const sheets = sheetsOf(copies, kind, skip);
  const { columns, rows } = SHEET_LAYOUT[kind];
  return (
    <div className="label-sheet" data-kind={kind} data-testid="label-sheet">
      {sheets.map((places, sheet) => (
        <section
          key={sheet}
          className="label-page"
          aria-label={t("library.labels.sheetNo", { n: sheet + 1, total: sheets.length })}
          style={{ gridTemplateColumns: `repeat(${columns}, 1fr)`, gridTemplateRows: `repeat(${rows}, 1fr)` }}
        >
          {places.map((copy, i) =>
            copy === null ? (
              <div key={`blank-${i}`} className="label label-blank" aria-hidden="true" />
            ) : kind === "barcode" ? (
              <div key={copy.id} className="label" data-testid="label">
                <Barcode value={copy.accessionNo} />
                <b className="label-accession">{copy.accessionNo}</b>
                <span className="label-title">{shortTitle(copy.title)}</span>
              </div>
            ) : (
              <div key={copy.id} className="label label-spine" data-testid="spine-label">
                <b className="label-call">
                  {(copy.callNumber ?? "—").split(/\s+/).map((part, n) => (
                    <span key={n}>{part}</span>
                  ))}
                </b>
                <span className="label-accession">{copy.accessionNo}</span>
              </div>
            ),
          )}
        </section>
      ))}
    </div>
  );
}
