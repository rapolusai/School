"use client";

import { ArrowLeft, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatDateTime, formatPaise, formatPlainDate, parseRupees } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { blank, MAX_PAISE } from "@/lib/inventory";
import type { RfqStatus, RfqView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { InventoryNav, ReasonDialog, useLookups } from "./inventory-ui";

const MAX_QUOTES = 3;

function rfqTone(status: RfqStatus): PillTone {
  return status === "OPEN" ? "warn" : status === "SELECTED" ? "good" : "neutral";
}

function RfqPill({ status }: { status: RfqStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={rfqTone(status)} dot>
      {translateOr(t, `inventory.rfqStatus.${status}`, status)}
    </Pill>
  );
}

/** /app/inventory/rfqs: requests for quotation, newest first, and a new one (for an approved requisition or on its own). */
export function RfqsView({ requisitionId }: { requisitionId?: string }) {
  const { t, lang } = useI18n();
  const router = useRouter();
  const locale = localeFor(lang);
  const [creating, setCreating] = useState(Boolean(requisitionId));
  const list = useApiData("inventory:rfqs", inventoryApi.rfqs);

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.rfq.title")}
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} data-testid="rfq-new">
            <Plus size={18} aria-hidden="true" />
            {t("inventory.rfq.new")}
          </button>
        }
      />
      <InventoryNav />
      <section className="card" aria-labelledby="rfqs-title">
        <div className="card-head">
          <h2 id="rfqs-title">{t("inventory.rfq.list")}</h2>
        </div>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={4} />
        ) : list.data.length === 0 ? (
          <p className="empty" data-testid="rfqs-empty">
            {t("inventory.rfq.empty")}
          </p>
        ) : (
          <ul className="rowcards" data-testid="rfqs-list">
            {list.data.map((rfq) => (
              <li key={rfq.id}>
                <Link href={`/app/inventory/rfqs/${rfq.id}`} className="rowcard">
                  <span className="min-w-0 flex-1">
                    <b className="block truncate font-semibold">
                      <span className="mono">{rfq.number}</span> · {rfq.title}
                    </b>
                    <span className="block truncate text-[12.5px] text-ink-3">
                      {t("inventory.rfq.quoteCount", { count: rfq.quotes.length })}
                      {rfq.requisitionNumber ? ` · ${rfq.requisitionNumber}` : ""} · {formatDateTime(rfq.createdAt, locale)}
                    </span>
                  </span>
                  <RfqPill status={rfq.status} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      {creating ? (
        <NewRfqDialog
          requisitionId={requisitionId ?? ""}
          onClose={() => setCreating(false)}
          onSaved={(rfq) => {
            setCreating(false);
            router.push(`/app/inventory/rfqs/${rfq.id}`);
          }}
        />
      ) : null}
    </>
  );
}

function NewRfqDialog({ requisitionId, onClose, onSaved }: { requisitionId: string; onClose: () => void; onSaved: (rfq: RfqView) => void }) {
  const { t } = useI18n();
  const approved = useApiData("inventory:requisitions:APPROVED", () => inventoryApi.requisitions({ status: "APPROVED" }));
  const form = useForm({ title: "", requisitionId });
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(form.values.title.trim() ? {} : { title: "inventory.v.title" }, t, event.currentTarget)) return;
    let saved: RfqView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await inventoryApi.createRfq({ title: form.values.title.trim(), requisitionId: form.values.requisitionId || null });
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("inventory.rfq.new")} description={t("inventory.rfq.newDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="rfq-form">
        <FormAlert message={form.formError} />
        <TextField label={t("inventory.col.title")} name="title" value={form.values.title} onChange={(e) => form.set("title", e.target.value)} error={form.errors.title} maxLength={200} required data-autofocus />
        <SelectField
          label={t("inventory.col.requisition")}
          name="requisitionId"
          value={form.values.requisitionId}
          onChange={(e) => form.set("requisitionId", e.target.value)}
          options={[{ value: "", label: t("inventory.none") }, ...(approved.data ?? []).map((r) => ({ value: r.id, label: `${r.number ?? ""} · ${r.purpose}` }))]}
          error={form.errors.requisitionId}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type QuoteValues = { vendorId: string; amount: string; deliveryDays: string; validUntil: string; notes: string };

/** The same checks POST /api/inventory/rfqs/{id}/quotes makes. Exported for tests. */
export function validateQuote(values: QuoteValues): Problems {
  const problems: Problems = {};
  if (!values.vendorId) problems.vendorId = "inventory.v.vendor";
  const amount = parseRupees(values.amount);
  if (amount === null || amount <= 0 || amount > MAX_PAISE) problems.amount = "inventory.v.amount";
  if (values.deliveryDays.trim() && !/^\d{1,3}$/.test(values.deliveryDays.trim())) problems.deliveryDays = "inventory.v.days";
  return problems;
}

/** /app/inventory/rfqs/{id}: up to three vendors' quotes side by side; pick one with a reason, or cancel the RFQ. */
export function RfqDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const detail = useApiData(`inventory:rfq:${id}`, () => inventoryApi.rfq(id));
  const lookups = useLookups();
  const [adding, setAdding] = useState(false);
  const [selecting, setSelecting] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const rfq = detail.data;
  const open = rfq?.status === "OPEN";
  const lowest = rfq && rfq.quotes.length ? Math.min(...rfq.quotes.map((q) => q.amountPaise)) : null;
  const form = useForm<QuoteValues>({ vendorId: "", amount: "", deliveryDays: "", validUntil: "", notes: "" });

  const addQuote = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateQuote(form.values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await inventoryApi.addQuote(id, {
        vendorId: form.values.vendorId,
        amountPaise: parseRupees(form.values.amount) ?? 0,
        deliveryDays: form.values.deliveryDays.trim() ? Number(form.values.deliveryDays) : null,
        validUntil: form.values.validUntil || null,
        notes: blank(form.values.notes),
      });
    });
    if (ok) {
      form.reset({ vendorId: "", amount: "", deliveryDays: "", validUntil: "", notes: "" });
      setAdding(false);
      toast(t("inventory.rfq.quoteAdded"));
      detail.reload();
    }
  };

  const vendors = (lookups.data?.vendors ?? []).filter((v) => v.active && !rfq?.quotes.some((q) => q.vendorId === v.id));

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={rfq ? `${rfq.number} · ${rfq.title}` : t("inventory.rfq.single")}
        actions={
          <>
            <Link href="/app/inventory/rfqs" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {open && rfq && rfq.quotes.length < MAX_QUOTES ? (
              <button type="button" className="btn btn-primary" onClick={() => setAdding(true)} data-testid="quote-new">
                <Plus size={18} aria-hidden="true" />
                {t("inventory.rfq.addQuote")}
              </button>
            ) : null}
            {open ? (
              <button type="button" className="btn" onClick={() => setCancelling(true)} data-testid="rfq-cancel">
                {t("inventory.rfq.cancel")}
              </button>
            ) : null}
            {rfq?.status === "SELECTED" && rfq.selectedQuoteId ? (
              <Link href={`/app/inventory/purchase-orders?new=1&rfqId=${rfq.id}`} className="btn btn-primary" data-testid="rfq-order">
                {t("inventory.req.raiseOrder")}
              </Link>
            ) : null}
          </>
        }
      />
      <InventoryNav />
      {detail.error && !rfq ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !rfq ? (
        <LoadingRows rows={4} />
      ) : (
        <section className="card" aria-labelledby="quotes-title">
          <div className="card-head">
            <div>
              <h2 id="quotes-title">{t("inventory.rfq.quotes")}</h2>
              <p className="mt-1 text-sm text-ink-2">
                {rfq.requisitionNumber ? (
                  <Link href={`/app/inventory/requisitions/${rfq.requisitionId}`} className="link">
                    {rfq.requisitionNumber}
                  </Link>
                ) : (
                  t("inventory.rfq.noRequisition")
                )}
                {" · "}
                {t("inventory.rfq.upTo", { max: MAX_QUOTES })}
              </p>
            </div>
            <RfqPill status={rfq.status} />
          </div>
          {rfq.status === "SELECTED" ? <p className="alert alert-info" data-testid="rfq-selection">{t("inventory.rfq.selectedBecause", { reason: rfq.selectionReason ?? "" })}</p> : null}
          {rfq.quotes.length === 0 ? (
            <p className="empty" data-testid="quotes-empty">
              {t("inventory.rfq.noQuotes")}
            </p>
          ) : (
            <div className="grid gap-3.5 md:grid-cols-3" data-testid="quotes">
              {rfq.quotes.map((q) => (
                <article key={q.id} className={`rounded-lg border p-3.5 ${q.selected ? "border-accent" : "border-line"}`} data-testid="quote-card">
                  <h3 className="font-semibold">{q.vendorName}</h3>
                  <p className="num text-2xl font-bold" data-testid="quote-amount">
                    {formatPaise(q.amountPaise)}
                    {lowest !== null && q.amountPaise === lowest && rfq.quotes.length > 1 ? <span className="ml-2 pill pill-good">{t("inventory.rfq.lowest")}</span> : null}
                  </p>
                  <dl className="kv mt-2 text-[13px]">
                    <dt>{t("inventory.rfq.delivery")}</dt>
                    <dd>{q.deliveryDays !== null ? t("inventory.rfq.days", { count: q.deliveryDays }) : "—"}</dd>
                    <dt>{t("inventory.rfq.validUntil")}</dt>
                    <dd>{q.validUntil ? formatPlainDate(q.validUntil, locale) : "—"}</dd>
                    <dt>{t("inventory.col.notes")}</dt>
                    <dd>{q.notes ?? "—"}</dd>
                  </dl>
                  {q.selected ? (
                    <p className="mt-2 pill pill-good">{t("inventory.rfq.selected")}</p>
                  ) : open ? (
                    <button type="button" className="btn btn-sm mt-2" onClick={() => setSelecting(q.id)} data-testid={`quote-select-${q.id}`}>
                      {t("inventory.rfq.select")}
                    </button>
                  ) : null}
                </article>
              ))}
            </div>
          )}
        </section>
      )}

      {adding ? (
        <Dialog open onClose={() => setAdding(false)} title={t("inventory.rfq.addQuote")} closeLabel={t("common.close")}>
          <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void addQuote(e)} noValidate data-testid="quote-form">
            <FormAlert message={form.formError} />
            <SelectField label={t("inventory.col.vendor")} name="vendorId" value={form.values.vendorId} onChange={(e) => form.set("vendorId", e.target.value)} options={[{ value: "", label: t("common.choose") }, ...vendors.map((v) => ({ value: v.id, label: v.name }))]} error={form.errors.vendorId} required data-autofocus />
            <div className="grid gap-3.5 sm:grid-cols-3">
              <TextField label={t("inventory.col.amount")} name="amount" inputMode="decimal" value={form.values.amount} onChange={(e) => form.set("amount", e.target.value)} error={form.errors.amount} required />
              <TextField label={t("inventory.rfq.deliveryDays")} name="deliveryDays" inputMode="numeric" value={form.values.deliveryDays} onChange={(e) => form.set("deliveryDays", e.target.value)} error={form.errors.deliveryDays} />
              <TextField label={t("inventory.rfq.validUntil")} name="validUntil" type="date" value={form.values.validUntil} onChange={(e) => form.set("validUntil", e.target.value)} error={form.errors.validUntil} />
            </div>
            <TextAreaField label={t("inventory.col.notes")} name="notes" value={form.values.notes} onChange={(e) => form.set("notes", e.target.value)} error={form.errors.notes} rows={2} maxLength={500} />
            <div className="flex justify-end gap-2">
              <button type="button" className="btn" onClick={() => setAdding(false)}>
                {t("common.cancel")}
              </button>
              <button type="submit" className="btn btn-primary" disabled={form.submitting}>
                {form.submitting ? t("common.saving") : t("common.save")}
              </button>
            </div>
          </form>
        </Dialog>
      ) : null}
      <ReasonDialog
        open={selecting !== null}
        title={t("inventory.rfq.select")}
        description={t("inventory.rfq.selectDescription")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.rfq.select")}
        danger={false}
        testId="select-quote-form"
        onClose={() => setSelecting(null)}
        onConfirm={async (reason) => {
          if (!selecting) return;
          await inventoryApi.selectQuote(id, { quoteId: selecting, reason });
          setSelecting(null);
          toast(t("inventory.rfq.quoteSelected"));
          detail.reload();
        }}
      />
      <ReasonDialog
        open={cancelling}
        title={t("inventory.rfq.cancel")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.rfq.cancel")}
        required={false}
        testId="cancel-rfq-form"
        onClose={() => setCancelling(false)}
        onConfirm={async () => {
          await inventoryApi.cancelRfq(id);
          setCancelling(false);
          toast(t("inventory.rfq.cancelled"));
          detail.reload();
        }}
      />
    </>
  );
}
