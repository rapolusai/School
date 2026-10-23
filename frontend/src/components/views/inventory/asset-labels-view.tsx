"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { TextField } from "@/components/ui/field";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { Barcode, labelsPerSheet, sheetsOf } from "@/components/views/library/label-sheet";
import { plural, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import type { AssetLabel } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { InventoryNav } from "./inventory-ui";

/** A label's name, shortened to fit (the tag and barcode matter most). Exported for tests. */
export function shortName(name: string, max = 34): string {
  return name.length <= max ? name : `${name.slice(0, max - 1).trimEnd()}…`;
}

/**
 * /app/inventory/assets/labels: printable A4 sheets of barcode labels (3 × 8, like the library's), for the assets
 * asked for or every asset in use. Labels already used on a part sheet can be skipped.
 */
export function AssetLabelsView({ ids }: { ids: string[] }) {
  const { t } = useI18n();
  const [startAt, setStartAt] = useState("1");
  const [left, setLeft] = useState<Set<string>>(new Set());
  const labels = useApiData(`inventory:labels:${ids.join(",")}`, () => inventoryApi.assetLabels(ids));
  const list = labels.data ?? [];
  const chosen = list.filter((l) => !left.has(l.id));
  const perSheet = labelsPerSheet("barcode");
  const start = Number(startAt);
  const startProblem = !Number.isInteger(start) || start < 1 || start > perSheet;
  const skip = startProblem ? 0 : start - 1;
  const sheets = sheetsOf(chosen, "barcode", skip);

  const toggle = (id: string) =>
    setLeft((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.labels.title")}
        actions={
          <>
            <Link href="/app/inventory/assets" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            <button type="button" className="btn btn-primary" onClick={() => window.print()} disabled={chosen.length === 0 || startProblem} data-testid="labels-print">
              <Printer size={18} aria-hidden="true" />
              {t("inventory.labels.print")}
            </button>
          </>
        }
      />
      <InventoryNav />

      <section className="card flex flex-col gap-3.5 no-print">
        <form method="post" className="flex flex-wrap items-end gap-3" onSubmit={(e) => e.preventDefault()} aria-label={t("inventory.labels.layout")}>
          <TextField
            label={t("inventory.labels.startAt")}
            name="startAt"
            inputMode="numeric"
            value={startAt}
            onChange={(e) => setStartAt(e.target.value)}
            error={startProblem ? t("inventory.labels.v.startAt", { max: perSheet }) : undefined}
            hint={t("inventory.labels.startAtHint", { perSheet })}
            className="w-32"
          />
          <p className="pb-2 text-sm text-ink-2" data-testid="labels-summary">
            {plural(t, "inventory.labels.count", chosen.length, { sheets: sheets.length })}
          </p>
        </form>
        {labels.error && !labels.data ? (
          <ErrorState error={labels.error} onRetry={labels.reload} />
        ) : !labels.data ? (
          <LoadingRows rows={4} />
        ) : list.length === 0 ? (
          <p className="empty" data-testid="labels-empty">
            {t("inventory.labels.empty")}
          </p>
        ) : (
          <ul className="grid gap-1.5 sm:grid-cols-2 lg:grid-cols-3" data-testid="labels-choice">
            {list.map((label) => (
              <li key={label.id}>
                <label className="check">
                  <input type="checkbox" name={`label-${label.id}`} checked={!left.has(label.id)} onChange={() => toggle(label.id)} />
                  <span>
                    <span className="mono">{label.assetTag}</span> · {label.name}
                  </span>
                </label>
              </li>
            ))}
          </ul>
        )}
      </section>

      {chosen.length > 0 ? <AssetLabelSheet sheets={sheets} /> : null}
    </>
  );
}

function AssetLabelSheet({ sheets }: { sheets: (AssetLabel | null)[][] }) {
  const { t } = useI18n();
  return (
    <div className="label-sheet" data-kind="barcode" data-testid="label-sheet">
      {sheets.map((places, sheet) => (
        <section key={sheet} className="label-page" aria-label={t("inventory.labels.sheetNo", { n: sheet + 1, total: sheets.length })} style={{ gridTemplateColumns: "repeat(3, 1fr)", gridTemplateRows: "repeat(8, 1fr)" }}>
          {places.map((label, i) =>
            label === null ? (
              <div key={`blank-${i}`} className="label label-blank" aria-hidden="true" />
            ) : (
              <div key={label.id} className="label" data-testid="asset-label">
                <Barcode value={label.assetTag} />
                <b className="label-accession">{label.assetTag}</b>
                <span className="label-title">
                  {shortName(label.name)}
                  {label.locationName ? ` · ${label.locationName}` : ""}
                </span>
              </div>
            ),
          )}
        </section>
      ))}
    </div>
  );
}
