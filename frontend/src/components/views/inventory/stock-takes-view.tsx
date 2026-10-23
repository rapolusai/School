"use client";

import { ArrowLeft, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { parseQuantity } from "@/lib/inventory";
import type { StockTakeView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { InventoryNav, useLookups } from "./inventory-ui";

/** /app/inventory/stock-takes: every count, open ones first, and a new one. */
export function StockTakesView() {
  const { t, lang } = useI18n();
  const router = useRouter();
  const locale = localeFor(lang);
  const [starting, setStarting] = useState(false);
  const list = useApiData("inventory:stock-takes", inventoryApi.stockTakes);

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.takes.title")}
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setStarting(true)} data-testid="take-new">
            <Plus size={18} aria-hidden="true" />
            {t("inventory.takes.new")}
          </button>
        }
      />
      <InventoryNav />
      <section className="card" aria-labelledby="takes-title">
        <div className="card-head">
          <h2 id="takes-title">{t("inventory.takes.list")}</h2>
        </div>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={4} />
        ) : list.data.length === 0 ? (
          <p className="empty" data-testid="takes-empty">
            {t("inventory.takes.empty")}
          </p>
        ) : (
          <ul className="rowcards" data-testid="takes-list">
            {list.data.map((take) => (
              <li key={take.id}>
                <Link href={`/app/inventory/stock-takes/${take.id}`} className="rowcard">
                  <span className="min-w-0 flex-1">
                    <b className="block truncate font-semibold">
                      {formatPlainDate(take.startedOn, locale)} · {take.locationName ?? t("inventory.takes.allLocations")}
                    </b>
                    <span className="block truncate text-[12.5px] text-ink-3">
                      {t("inventory.takes.progress", { counted: take.counted, total: take.lines, variances: take.variances })} · {take.createdByName}
                    </span>
                  </span>
                  <Pill tone={take.status === "OPEN" ? "warn" : "good"} dot>
                    {t(take.status === "OPEN" ? "inventory.open" : "inventory.closed")}
                  </Pill>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      {starting ? (
        <StartStockTakeDialog
          onClose={() => setStarting(false)}
          onStarted={(take) => {
            setStarting(false);
            router.push(`/app/inventory/stock-takes/${take.id}`);
          }}
        />
      ) : null}
    </>
  );
}

function StartStockTakeDialog({ onClose, onStarted }: { onClose: () => void; onStarted: (take: StockTakeView) => void }) {
  const { t } = useI18n();
  const lookups = useLookups();
  const form = useForm({ locationId: "", notes: "" });
  const start = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    let saved: StockTakeView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await inventoryApi.startStockTake({ locationId: form.values.locationId || null, notes: form.values.notes.trim() || null });
    });
    if (ok && saved) onStarted(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("inventory.takes.new")} description={t("inventory.takes.newDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void start(e)} noValidate data-testid="take-form">
        <FormAlert message={form.formError} />
        <SelectField
          label={t("inventory.col.location")}
          name="locationId"
          value={form.values.locationId}
          onChange={(e) => form.set("locationId", e.target.value)}
          options={[{ value: "", label: t("inventory.takes.allLocations") }, ...(lookups.data?.locations ?? []).filter((l) => l.active).map((l) => ({ value: l.id, label: l.name }))]}
          data-autofocus
        />
        <TextAreaField label={t("inventory.col.notes")} name="notes" value={form.values.notes} onChange={(e) => form.set("notes", e.target.value)} rows={2} maxLength={500} error={form.errors.notes} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("inventory.takes.start")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** /app/inventory/stock-takes/{id}: count every item, see the variance, and close to post the adjustments. */
export function StockTakeDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const detail = useApiData(`inventory:stock-take:${id}`, () => inventoryApi.stockTake(id));
  const [counts, setCounts] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [closing, setClosing] = useState(false);
  const data = detail.data;
  const open = data?.status === "OPEN";

  const value = (itemId: string, counted: number | null) => counts[itemId] ?? (counted === null ? "" : String(counted));

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!data) return;
    const next: Record<string, string> = {};
    const body: { itemId: string; countedQty: number }[] = [];
    for (const [itemId, text] of Object.entries(counts)) {
      if (!text.trim()) continue;
      const qty = parseQuantity(text);
      if (qty === null) next[itemId] = t("inventory.v.quantity0");
      else body.push({ itemId, countedQty: qty });
    }
    setErrors(next);
    if (Object.keys(next).length > 0) return;
    if (body.length === 0) {
      setFormError(t("inventory.takes.nothingCounted"));
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      await inventoryApi.countStock(data.id, { counts: body });
      setCounts({});
      toast(t("inventory.takes.counted"));
      detail.reload();
    } catch (caught) {
      setFormError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={data ? `${t("inventory.takes.single")} · ${formatPlainDate(data.startedOn, locale)}` : t("inventory.takes.single")}
        actions={
          <>
            <Link href="/app/inventory/stock-takes" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {open ? (
              <button type="button" className="btn btn-primary" onClick={() => setClosing(true)} data-testid="take-close">
                {t("inventory.takes.close")}
              </button>
            ) : null}
          </>
        }
      />
      <InventoryNav />
      {detail.error && !data ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <section className="card" aria-labelledby="take-title">
          <div className="card-head">
            <div>
              <h2 id="take-title">{data.locationName ?? t("inventory.takes.allLocations")}</h2>
              <p className="mt-1 text-sm text-ink-2" data-testid="take-progress">
                {t("inventory.takes.progress", { counted: data.counted, total: data.lines, variances: data.variances })}
                {data.notes ? ` · ${data.notes}` : ""}
              </p>
            </div>
            <Pill tone={open ? "warn" : "good"} dot>
              {t(open ? "inventory.open" : "inventory.closed")}
            </Pill>
          </div>
          {!open ? <p className="alert alert-info">{t("inventory.takes.closedNote")}</p> : null}
          <form method="post" className="flex flex-col gap-3" onSubmit={(e) => void save(e)} noValidate data-testid="count-form">
            <FormAlert message={formError} />
            <div className="table-wrap">
              <table className="table" data-testid="take-table">
                <thead>
                  <tr>
                    <th scope="col">{t("inventory.col.item")}</th>
                    <th scope="col" className="r">
                      {t("inventory.col.expected")}
                    </th>
                    <th scope="col" className="r">
                      {t("inventory.col.counted")}
                    </th>
                    <th scope="col" className="r">
                      {t("inventory.col.variance")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((line) => (
                    <tr key={line.itemId} data-testid="take-row">
                      <td>
                        {line.itemName ?? "—"}
                        <span className="ml-1 text-[12.5px] text-ink-3">{line.unit}</span>
                      </td>
                      <td className="r num">{line.expectedQty}</td>
                      <td className="r">
                        {open ? (
                          <label className="field inline-block w-24">
                            <span className="sr-only">{t("inventory.col.counted")}</span>
                            <input
                              className="input text-right"
                              name={`count-${line.itemId}`}
                              inputMode="numeric"
                              value={value(line.itemId, line.countedQty)}
                              onChange={(e) => setCounts((c) => ({ ...c, [line.itemId]: e.target.value }))}
                              aria-invalid={errors[line.itemId] ? true : undefined}
                            />
                            {errors[line.itemId] ? <span className="field-error">{errors[line.itemId]}</span> : null}
                          </label>
                        ) : (
                          <span className="num">{line.countedQty ?? "—"}</span>
                        )}
                      </td>
                      <td className={`r num font-semibold${line.variance ? " text-bad" : ""}`}>{line.variance === null ? "—" : line.variance > 0 ? `+${line.variance}` : line.variance}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {open ? (
              <div className="flex justify-end">
                <button type="submit" className="btn btn-primary" disabled={saving} data-testid="count-save">
                  {saving ? t("common.saving") : t("inventory.takes.saveCounts")}
                </button>
              </div>
            ) : null}
          </form>
        </section>
      )}
      <ConfirmDialog
        open={closing}
        title={t("inventory.takes.close")}
        body={data ? plural(t, "inventory.takes.closeBody", data.variances) : ""}
        confirmLabel={t("inventory.takes.close")}
        danger={false}
        onClose={() => setClosing(false)}
        onConfirm={async () => {
          if (!data) return;
          await inventoryApi.closeStockTake(data.id);
          setClosing(false);
          toast(t("inventory.takes.closed"));
          detail.reload();
        }}
      />
    </>
  );
}
