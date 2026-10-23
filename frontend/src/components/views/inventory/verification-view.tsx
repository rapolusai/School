"use client";

import { ArrowLeft, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { ScanField } from "@/components/views/library/scan-field";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { normalizeTag } from "@/lib/inventory";
import type { RoundView, VerificationItemView, VerificationResult } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { InventoryNav, ResultPill, useLookups } from "./inventory-ui";

/** /app/inventory/verification: the physical verification rounds, open ones first, and a new one. */
export function VerificationRoundsView() {
  const { t, lang } = useI18n();
  const router = useRouter();
  const locale = localeFor(lang);
  const [starting, setStarting] = useState(false);
  const list = useApiData("inventory:rounds", inventoryApi.rounds);

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.rounds.title")}
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setStarting(true)} data-testid="round-new">
            <Plus size={18} aria-hidden="true" />
            {t("inventory.rounds.new")}
          </button>
        }
      />
      <InventoryNav />
      <section className="card" aria-labelledby="rounds-title">
        <div className="card-head">
          <h2 id="rounds-title">{t("inventory.rounds.list")}</h2>
        </div>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={4} />
        ) : list.data.length === 0 ? (
          <p className="empty" data-testid="rounds-empty">
            {t("inventory.rounds.empty")}
          </p>
        ) : (
          <ul className="rowcards" data-testid="rounds-list">
            {list.data.map((round) => (
              <li key={round.id}>
                <Link href={`/app/inventory/verification/${round.id}`} className="rowcard">
                  <span className="min-w-0 flex-1">
                    <b className="block truncate font-semibold">{round.name}</b>
                    <span className="block truncate text-[12.5px] text-ink-3">
                      {formatPlainDate(round.startedOn, locale)} · {round.locationName ?? t("inventory.takes.allLocations")} ·{" "}
                      {t("inventory.rounds.progress", { checked: round.checked, total: round.assets, missing: round.missing, damaged: round.damaged })}
                    </span>
                  </span>
                  <Pill tone={round.status === "OPEN" ? "warn" : "good"} dot>
                    {t(round.status === "OPEN" ? "inventory.open" : "inventory.closed")}
                  </Pill>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      {starting ? (
        <StartRoundDialog
          onClose={() => setStarting(false)}
          onStarted={(round) => {
            setStarting(false);
            router.push(`/app/inventory/verification/${round.id}`);
          }}
        />
      ) : null}
    </>
  );
}

function StartRoundDialog({ onClose, onStarted }: { onClose: () => void; onStarted: (round: RoundView) => void }) {
  const { t } = useI18n();
  const lookups = useLookups();
  const form = useForm({ name: "", locationId: "" });
  const start = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(form.values.name.trim() ? {} : { name: "inventory.v.name" }, t, event.currentTarget)) return;
    let saved: RoundView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await inventoryApi.startRound({ name: form.values.name.trim(), locationId: form.values.locationId || null });
    });
    if (ok && saved) onStarted(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("inventory.rounds.new")} description={t("inventory.rounds.newDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void start(e)} noValidate data-testid="round-form">
        <FormAlert message={form.formError} />
        <TextField label={t("inventory.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required data-autofocus />
        <SelectField
          label={t("inventory.col.location")}
          name="locationId"
          value={form.values.locationId}
          onChange={(e) => form.set("locationId", e.target.value)}
          options={[{ value: "", label: t("inventory.takes.allLocations") }, ...(lookups.data?.locations ?? []).filter((l) => l.active).map((l) => ({ value: l.id, label: l.name }))]}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("inventory.rounds.start")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

const RESULTS: VerificationResult[] = ["FOUND", "MISSING", "DAMAGED"];

/**
 * /app/inventory/verification/{id}: scan or type asset tags on a phone, marking each found, missing or damaged;
 * the list shows what is still to check. Closing the round marks everything unchecked as missing.
 */
export function VerificationRoundView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const detail = useApiData(`inventory:round:${id}`, () => inventoryApi.round(id));
  const [result, setResult] = useState<VerificationResult>("FOUND");
  const [note, setNote] = useState("");
  const [last, setLast] = useState<VerificationItemView | null>(null);
  const [closing, setClosing] = useState(false);
  const [filter, setFilter] = useState<"pending" | "all">("pending");
  const data = detail.data;
  const open = data?.status === "OPEN";

  const mark = async (body: { assetId?: string; assetTag?: string }): Promise<string | null> => {
    try {
      const saved = await inventoryApi.verify(id, { ...body, result, note: note.trim() || null });
      const item = saved.items.find((i) => (body.assetId ? i.assetId === body.assetId : i.assetTag === normalizeTag(body.assetTag ?? ""))) ?? null;
      setLast(item);
      setNote("");
      detail.reload();
      return null;
    } catch (caught) {
      const error = toApiError(caught);
      return error.errors?.assetTag ?? error.errors?.assetId ?? errorMessage(error, t);
    }
  };

  const rows = (data?.items ?? []).filter((i) => filter === "all" || i.result === null);

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={data ? data.name : t("inventory.rounds.single")}
        actions={
          <>
            <Link href="/app/inventory/verification" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {open ? (
              <button type="button" className="btn btn-primary" onClick={() => setClosing(true)} data-testid="round-close">
                {t("inventory.rounds.close")}
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
        <>
          <ul className="rep-kpis" data-testid="round-figures">
            <li className="rep-kpi">
              <span className="text-[13px] font-medium text-ink-2">{t("inventory.rounds.checked")}</span>
              <span className="kpi-value" data-testid="round-checked">
                {data.checked} / {data.assets}
              </span>
            </li>
            <li className="rep-kpi">
              <span className="text-[13px] font-medium text-ink-2">{t("inventory.result.FOUND")}</span>
              <span className="kpi-value text-good">{data.found}</span>
            </li>
            <li className={`rep-kpi${data.missing > 0 ? " is-attention" : ""}`}>
              <span className="text-[13px] font-medium text-ink-2">{t("inventory.result.MISSING")}</span>
              <span className="kpi-value text-bad" data-testid="round-missing">
                {data.missing}
              </span>
            </li>
            <li className="rep-kpi">
              <span className="text-[13px] font-medium text-ink-2">{t("inventory.result.DAMAGED")}</span>
              <span className="kpi-value">{data.damaged}</span>
            </li>
          </ul>

          {open ? (
            <section className="card flex flex-col gap-3.5" aria-labelledby="scan-title">
              <div className="card-head">
                <h2 id="scan-title">{t("inventory.rounds.scan")}</h2>
              </div>
              <div className="grid gap-3.5 sm:grid-cols-2">
                <SelectField label={t("inventory.col.result")} name="result" value={result} onChange={(e) => setResult(e.target.value as VerificationResult)} options={RESULTS.map((r) => ({ value: r, label: translateOr(t, `inventory.result.${r}`, r) }))} />
                <TextField label={t("inventory.col.note")} name="note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={300} />
              </div>
              <ScanField
                label={t("inventory.col.tag")}
                name="assetTag"
                action={t("inventory.rounds.mark")}
                onScan={(tag) => mark({ assetTag: tag })}
                hint={t("inventory.rounds.scanHint")}
                requiredMessage={t("inventory.v.tag")}
                testId="verify-scan"
              />
              {last ? (
                <p className="alert alert-info" data-testid="verify-last">
                  <span>
                    <span className="mono">{last.assetTag}</span> · {last.assetName} · <ResultPill result={last.result} />
                  </span>
                </p>
              ) : null}
            </section>
          ) : (
            <p className="alert alert-info">{t("inventory.rounds.closedNote", { date: data.closedAt ? formatDateTime(data.closedAt, locale) : "" })}</p>
          )}

          <section className="card" aria-labelledby="round-items">
            <div className="card-head">
              <h2 id="round-items">{t("inventory.rounds.assets")}</h2>
              <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()}>
                <label className="field w-full sm:w-auto sm:min-w-40">
                  <span className="field-label">{t("inventory.rounds.show")}</span>
                  <select className="input" name="filter" value={filter} onChange={(e) => setFilter(e.target.value as "pending" | "all")}>
                    <option value="pending">{t("inventory.rounds.show.pending")}</option>
                    <option value="all">{t("inventory.rounds.show.all")}</option>
                  </select>
                </label>
              </form>
            </div>
            {rows.length === 0 ? (
              <p className="empty" data-testid="round-empty">
                {filter === "pending" ? t("inventory.rounds.allChecked") : t("inventory.rounds.noAssets")}
              </p>
            ) : (
              <ul className="rowcards" data-testid="round-list">
                {rows.map((item) => (
                  <li key={item.assetId} className="rowcard" data-testid="round-item">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">
                        <span className="mono">{item.assetTag}</span> · {item.assetName}
                      </b>
                      <span className="block truncate text-[12.5px] text-ink-3">
                        {item.locationName ?? "—"} · {item.custodianName ?? t("inventory.none")}
                        {item.note ? ` · ${item.note}` : ""}
                      </span>
                    </span>
                    <span className="flex items-center gap-2">
                      <ResultPill result={item.result} />
                      {open ? (
                        <button type="button" className="btn btn-sm" onClick={() => void mark({ assetId: item.assetId })} data-testid="round-mark">
                          {t("inventory.rounds.mark")}
                        </button>
                      ) : null}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
      <ConfirmDialog
        open={closing}
        title={t("inventory.rounds.close")}
        body={data ? t("inventory.rounds.closeBody", { pending: data.assets - data.checked }) : ""}
        confirmLabel={t("inventory.rounds.close")}
        danger={false}
        onClose={() => setClosing(false)}
        onConfirm={async () => {
          await inventoryApi.closeRound(id);
          setClosing(false);
          toast(t("inventory.rounds.closed"));
          detail.reload();
        }}
      />
    </>
  );
}
