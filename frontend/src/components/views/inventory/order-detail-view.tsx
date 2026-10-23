"use client";

import { ArrowLeft, Check, Pencil, Printer, Send, X } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPaise, formatPlainDate, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { blank, orderTone, outstanding, parseQuantity, validateReceive, type ReceiveValues } from "@/lib/inventory";
import type { OrderView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { InventoryNav, ReasonDialog, useCanManage, useLookups } from "./inventory-ui";
import { OrderFormDialog } from "./order-form";
import { ApprovalTrail } from "./requisition-detail-view";

/**
 * /app/inventory/purchase-orders/{id}: the order as printed (vendor, lines with GST, terms), its receipts and
 * invoice, and the actions: edit, send, approve or reject, cancel, receive (in parts) and record the invoice.
 */
export function OrderDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = useCanManage();
  const detail = useApiData(`inventory:order:${id}`, () => inventoryApi.order(id));
  const [editing, setEditing] = useState(false);
  const [receiving, setReceiving] = useState(false);
  const [invoicing, setInvoicing] = useState(false);
  const [dialog, setDialog] = useState<"reject" | "cancel" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const o = detail.data;
  const pending = o ? outstanding(o).some((l) => l.pending > 0) : false;
  const receivable = o?.status === "APPROVED" || o?.status === "PARTIAL";

  const run = async (action: () => Promise<OrderView>, message: (saved: OrderView) => string) => {
    setBusy(true);
    setError(null);
    try {
      const saved = await action();
      toast(message(saved));
      detail.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  const done = (message: string) => {
    setEditing(false);
    setReceiving(false);
    setInvoicing(false);
    setDialog(null);
    toast(message);
    detail.reload();
  };

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={o ? o.number : t("inventory.po.single")}
        actions={
          <>
            <Link href="/app/inventory/purchase-orders" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {o ? (
              <button type="button" className="btn" onClick={() => window.print()} data-testid="order-print">
                <Printer size={18} aria-hidden="true" />
                {t("inventory.po.print")}
              </button>
            ) : null}
            {o && canManage && o.status === "DRAFT" ? (
              <>
                <button type="button" className="btn" onClick={() => setEditing(true)} data-testid="order-edit">
                  <Pencil size={18} aria-hidden="true" />
                  {t("common.edit")}
                </button>
                <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void run(() => inventoryApi.submitOrder(id), () => t("inventory.po.sent", { number: o.number }))} data-testid="order-send">
                  <Send size={18} aria-hidden="true" />
                  {t("inventory.sendForApproval")}
                </button>
              </>
            ) : null}
            {o?.canAct ? (
              <>
                <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void run(() => inventoryApi.approveOrder(id, { note: null }), (s) => (s.status === "APPROVED" ? t("inventory.po.approved") : t("inventory.passedOn")))} data-testid="order-approve">
                  <Check size={18} aria-hidden="true" />
                  {t("inventory.approve")}
                </button>
                <button type="button" className="btn" disabled={busy} onClick={() => setDialog("reject")} data-testid="order-reject">
                  <X size={18} aria-hidden="true" />
                  {t("inventory.reject")}
                </button>
              </>
            ) : null}
            {o && canManage && receivable && pending ? (
              <button type="button" className="btn btn-primary" onClick={() => setReceiving(true)} data-testid="order-receive">
                {t("inventory.po.receive")}
              </button>
            ) : null}
            {o && canManage && (receivable || o.status === "RECEIVED") ? (
              <button type="button" className="btn" onClick={() => setInvoicing(true)} data-testid="order-invoice">
                {o.invoiceNumber ? t("inventory.po.editInvoice") : t("inventory.po.recordInvoice")}
              </button>
            ) : null}
            {o && canManage && (o.status === "DRAFT" || o.status === "SUBMITTED" || o.status === "APPROVED") && o.receipts.length === 0 ? (
              <button type="button" className="btn" onClick={() => setDialog("cancel")} data-testid="order-cancel">
                {t("inventory.po.cancel")}
              </button>
            ) : null}
          </>
        }
      />
      <InventoryNav />
      <FormAlert message={error} />
      {detail.error && !o ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !o ? (
        <LoadingRows rows={8} />
      ) : (
        <>
          {o.status === "REJECTED" || o.status === "CANCELLED" ? (
            <p className="alert alert-warn no-print" data-testid="order-rejected">
              {t("inventory.rejectedBecause", { reason: o.rejectionReason ?? "" })}
            </p>
          ) : o.status === "SUBMITTED" && o.escalatedAbovePaise !== null ? (
            <p className="alert alert-info no-print" data-testid="order-escalated">
              {t("inventory.escalatedAbove", { limit: formatPaise(o.escalatedAbovePaise) })}
            </p>
          ) : null}

          <section className="card">
            <article className="receipt print-area" aria-label={o.number} data-testid="order-document">
              <header className="receipt-head">
                <div className="min-w-0">
                  <p className="receipt-school">{me?.tenant?.name}</p>
                  <p className="text-[12.5px] text-ink-3">{t("inventory.po.document")}</p>
                </div>
                <div className="receipt-title">
                  <h2 className="mono">{o.number}</h2>
                  <Pill tone={orderTone(o.status)} dot>
                    {translateOr(t, `inventory.poStatus.${o.status}`, o.status)}
                  </Pill>
                </div>
              </header>
              <dl className="receipt-meta">
                <div>
                  <dt>{t("inventory.col.vendor")}</dt>
                  <dd data-testid="order-vendor">{o.vendorName}</dd>
                  {o.vendorGstin ? <dd className="mono text-[12.5px] font-normal">GSTIN {o.vendorGstin}</dd> : null}
                  {o.vendorAddress ? <dd className="text-[12.5px] font-normal">{o.vendorAddress}</dd> : null}
                </div>
                <div>
                  <dt>{t("inventory.col.date")}</dt>
                  <dd>{formatPlainDate(o.orderedOn, locale)}</dd>
                </div>
                <div>
                  <dt>{t("inventory.col.expected")}</dt>
                  <dd>{o.expectedOn ? formatPlainDate(o.expectedOn, locale) : "—"}</dd>
                </div>
                <div>
                  <dt>{t("inventory.col.requisition")}</dt>
                  <dd>{o.requisitionNumber ? <Link href={`/app/inventory/requisitions/${o.requisitionId}`} className="link">{o.requisitionNumber}</Link> : "—"}</dd>
                </div>
                <div>
                  <dt>{t("inventory.col.rfq")}</dt>
                  <dd>{o.rfqNumber ? <Link href={`/app/inventory/rfqs/${o.rfqId}`} className="link">{o.rfqNumber}</Link> : "—"}</dd>
                </div>
                <div>
                  <dt>{t("inventory.col.by")}</dt>
                  <dd>{o.createdByName}</dd>
                </div>
              </dl>
              <div className="table-wrap">
                <table className="table receipt-lines" data-testid="order-lines">
                  <thead>
                    <tr>
                      <th scope="col">#</th>
                      <th scope="col">{t("inventory.col.description")}</th>
                      <th scope="col" className="r">
                        {t("inventory.col.quantity")}
                      </th>
                      <th scope="col" className="r">
                        {t("inventory.col.unitPrice")}
                      </th>
                      <th scope="col" className="r">
                        {t("inventory.col.gst")}
                      </th>
                      <th scope="col" className="r">
                        {t("inventory.col.total")}
                      </th>
                      <th scope="col" className="r no-print">
                        {t("inventory.col.received")}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {o.lines.map((l) => (
                      <tr key={l.id} data-testid="order-line-row">
                        <td className="num">{l.lineNo}</td>
                        <td>{l.description}</td>
                        <td className="r num">
                          {l.quantity} {l.unit}
                        </td>
                        <td className="r num">{formatPaise(l.unitPaise)}</td>
                        <td className="r num">
                          {formatPaise(l.taxPaise)} <span className="text-[12px] text-ink-3">({l.gstRateBp / 100}%)</span>
                        </td>
                        <td className="r num font-semibold">{formatPaise(l.totalPaise)}</td>
                        <td className={`r num no-print${l.receivedQty >= l.quantity ? " text-good" : l.receivedQty > 0 ? " text-warn" : ""}`} data-testid="order-line-received">
                          {l.receivedQty} / {l.quantity}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <th scope="row" colSpan={5}>
                        {t("inventory.col.subtotal")}
                      </th>
                      <td className="r num">{formatPaise(o.subtotalPaise)}</td>
                      <td className="no-print" />
                    </tr>
                    <tr>
                      <th scope="row" colSpan={5}>
                        {t("inventory.col.gst")}
                      </th>
                      <td className="r num">{formatPaise(o.taxPaise)}</td>
                      <td className="no-print" />
                    </tr>
                    <tr>
                      <th scope="row" colSpan={5}>
                        {t("inventory.col.total")}
                      </th>
                      <td className="r num font-bold" data-testid="order-total">
                        {formatPaise(o.totalPaise)}
                      </td>
                      <td className="no-print" />
                    </tr>
                  </tfoot>
                </table>
              </div>
              {o.terms ? (
                <p className="text-[13px]">
                  <b>{t("inventory.col.terms")}:</b> {o.terms}
                </p>
              ) : null}
              {o.invoiceNumber ? (
                <p className="text-[13px]" data-testid="order-invoice-line">
                  <b>{t("inventory.po.invoice")}:</b> {o.invoiceNumber} · {o.invoiceDate ? formatPlainDate(o.invoiceDate, locale) : ""} · {formatPaise(o.invoicePaise ?? 0)}
                  {o.invoicePaise !== null && o.invoicePaise !== o.totalPaise ? <span className="ml-2 pill pill-warn">{t("inventory.po.invoiceDiffers")}</span> : null}
                </p>
              ) : null}
              <p className="receipt-foot">{t("inventory.po.footer")}</p>
            </article>
          </section>

          <section className="card no-print" aria-labelledby="receipts-title">
            <div className="card-head">
              <h2 id="receipts-title">{t("inventory.po.receipts")}</h2>
            </div>
            {o.receipts.length === 0 ? (
              <p className="empty" data-testid="receipts-empty">
                {t("inventory.po.noReceipts")}
              </p>
            ) : (
              <ul className="rowcards" data-testid="receipts-list">
                {o.receipts.map((g) => (
                  <li key={g.id} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block font-semibold">
                        <span className="mono">{g.number}</span> · {formatPlainDate(g.receivedOn, locale)}
                      </b>
                      <span className="block text-[12.5px] text-ink-3">
                        {g.lines.map((l) => `${l.description ?? ""} × ${l.quantity}`).join(", ")} · {g.locationName ?? t("inventory.none")}
                        {g.invoiceNumber ? ` · ${g.invoiceNumber}` : ""} · {g.receivedByName}
                      </span>
                    </span>
                    <span className="num font-semibold">{formatPaise(g.totalPaise)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <div className="no-print">
            <ApprovalTrail approvals={o.approvals} locale={locale} />
          </div>
          <p className="text-[12.5px] text-ink-3 no-print">
            {o.submittedAt ? `${t("inventory.col.submitted")}: ${formatDateTime(o.submittedAt, locale)}` : ""}
            {o.approvedAt ? ` · ${t("inventory.col.approved")}: ${formatDateTime(o.approvedAt, locale)}` : ""}
          </p>
        </>
      )}

      {editing && o ? <OrderFormDialog order={o} onClose={() => setEditing(false)} onSaved={(saved, submitted) => done(submitted ? t("inventory.po.sent", { number: saved.number }) : t("inventory.saved"))} /> : null}
      {receiving && o ? <ReceiveDialog order={o} onClose={() => setReceiving(false)} onSaved={(saved) => done(saved.status === "RECEIVED" ? t("inventory.po.receivedAll") : t("inventory.po.receivedPart"))} /> : null}
      {invoicing && o ? <InvoiceDialog order={o} onClose={() => setInvoicing(false)} onSaved={() => done(t("inventory.po.invoiceSaved"))} /> : null}
      <ReasonDialog
        open={dialog === "reject"}
        title={t("inventory.reject")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.reject")}
        testId="reject-form"
        onClose={() => setDialog(null)}
        onConfirm={async (reason) => {
          await inventoryApi.rejectOrder(id, { reason });
          done(t("inventory.rejected"));
        }}
      />
      <ReasonDialog
        open={dialog === "cancel"}
        title={t("inventory.po.cancel")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.po.cancel")}
        testId="cancel-form"
        onClose={() => setDialog(null)}
        onConfirm={async (reason) => {
          await inventoryApi.cancelOrder(id, { reason });
          done(t("inventory.po.cancelled"));
        }}
      />
    </>
  );
}

/** Records a goods receipt against the order: how many of each line arrived (the rest stays pending). */
export function ReceiveDialog({ order, onClose, onSaved }: { order: OrderView; onClose: () => void; onSaved: (saved: OrderView) => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const lookups = useLookups();
  const pendingLines = outstanding(order).filter((l) => l.pending > 0);
  const form = useForm<ReceiveValues>({
    receivedOn: today,
    locationId: "",
    invoiceNumber: "",
    notes: "",
    quantities: Object.fromEntries(pendingLines.map((l) => [l.poLineId, String(l.pending)])),
  });
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateReceive(form.values, order, today), t, event.currentTarget)) return;
    let saved: OrderView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await inventoryApi.receiveOrder(order.id, {
        receivedOn: form.values.receivedOn,
        locationId: form.values.locationId || null,
        invoiceNumber: blank(form.values.invoiceNumber),
        notes: blank(form.values.notes),
        lines: pendingLines
          .map((l) => ({ poLineId: l.poLineId, quantity: parseQuantity(form.values.quantities[l.poLineId] ?? "") ?? 0 }))
          .filter((l) => l.quantity > 0),
      });
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("inventory.po.receive")} description={t("inventory.po.receiveDescription")} closeLabel={t("common.close")} variant="sheet">
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="receive-form">
        <FormAlert message={form.formError} />
        {form.errors.lines ? <FormAlert message={form.errors.lines} /> : null}
        <div className="grid gap-3.5 sm:grid-cols-2">
          <TextField label={t("inventory.col.date")} name="receivedOn" type="date" value={form.values.receivedOn} max={today} min={order.orderedOn} onChange={(e) => form.set("receivedOn", e.target.value)} error={form.errors.receivedOn} required data-autofocus />
          <SelectField label={t("inventory.col.location")} name="locationId" value={form.values.locationId} onChange={(e) => form.set("locationId", e.target.value)} options={[{ value: "", label: t("inventory.none") }, ...(lookups.data?.locations ?? []).filter((l) => l.active).map((l) => ({ value: l.id, label: l.name }))]} error={form.errors.locationId} />
          <TextField label={t("inventory.po.invoiceNumber")} name="invoiceNumber" value={form.values.invoiceNumber} onChange={(e) => form.set("invoiceNumber", e.target.value)} error={form.errors.invoiceNumber} maxLength={60} />
          <TextField label={t("inventory.col.notes")} name="notes" value={form.values.notes} onChange={(e) => form.set("notes", e.target.value)} error={form.errors.notes} maxLength={500} />
        </div>
        <div className="table-wrap">
          <table className="table" data-testid="receive-lines">
            <thead>
              <tr>
                <th scope="col">{t("inventory.col.description")}</th>
                <th scope="col" className="r">
                  {t("inventory.col.pending")}
                </th>
                <th scope="col" className="r">
                  {t("inventory.col.receivedNow")}
                </th>
              </tr>
            </thead>
            <tbody>
              {pendingLines.map((l) => {
                const line = order.lines.find((x) => x.id === l.poLineId);
                return (
                  <tr key={l.poLineId} data-testid="receive-line">
                    <td>{line?.description}</td>
                    <td className="r num">
                      {l.pending} {line?.unit}
                    </td>
                    <td className="r">
                      <label className="field inline-block w-24">
                        <span className="sr-only">{t("inventory.col.receivedNow")}</span>
                        <input className="input text-right" name={`lines.${l.poLineId}`} inputMode="numeric" value={form.values.quantities[l.poLineId] ?? ""} onChange={(e) => form.set("quantities", { ...form.values.quantities, [l.poLineId]: e.target.value })} aria-invalid={form.errors[`lines.${l.poLineId}`] ? true : undefined} />
                        {form.errors[`lines.${l.poLineId}`] ? <span className="field-error">{form.errors[`lines.${l.poLineId}`]}</span> : null}
                      </label>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="receive-save">
            {form.submitting ? t("common.saving") : t("inventory.po.receive")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type InvoiceValues = { invoiceNumber: string; invoiceDate: string; amount: string };

/** The same checks POST /api/inventory/purchase-orders/{id}/invoice makes. Exported for tests. */
export function validateInvoice(values: InvoiceValues, today: string): Problems {
  const problems: Problems = {};
  if (!values.invoiceNumber.trim()) problems.invoiceNumber = "inventory.v.invoiceNumber";
  if (!values.invoiceDate) problems.invoiceDate = "inventory.v.date";
  else if (values.invoiceDate > today) problems.invoiceDate = "inventory.v.futureDate";
  const amount = parseRupees(values.amount);
  if (amount === null || amount <= 0) problems.amount = "inventory.v.amount";
  return problems;
}

function InvoiceDialog({ order, onClose, onSaved }: { order: OrderView; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const form = useForm<InvoiceValues>({ invoiceNumber: order.invoiceNumber ?? "", invoiceDate: order.invoiceDate ?? today, amount: rupeesInput(order.invoicePaise ?? order.totalPaise) });
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateInvoice(form.values, today), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await inventoryApi.recordInvoice(order.id, { invoiceNumber: form.values.invoiceNumber.trim(), invoiceDate: form.values.invoiceDate, invoicePaise: parseRupees(form.values.amount) ?? 0 });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open onClose={onClose} title={t("inventory.po.recordInvoice")} description={t("inventory.po.invoiceDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="invoice-form">
        <FormAlert message={form.formError} />
        <TextField label={t("inventory.po.invoiceNumber")} name="invoiceNumber" value={form.values.invoiceNumber} onChange={(e) => form.set("invoiceNumber", e.target.value)} error={form.errors.invoiceNumber} maxLength={60} required data-autofocus />
        <div className="grid gap-3.5 sm:grid-cols-2">
          <TextField label={t("inventory.col.date")} name="invoiceDate" type="date" value={form.values.invoiceDate} max={today} onChange={(e) => form.set("invoiceDate", e.target.value)} error={form.errors.invoiceDate} required />
          <TextField label={t("inventory.col.amount")} name="amount" inputMode="decimal" value={form.values.amount} onChange={(e) => form.set("amount", e.target.value)} error={form.errors.amount ?? form.errors.invoicePaise} hint={t("inventory.po.orderTotalIs", { total: formatPaise(order.totalPaise) })} required />
        </div>
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
