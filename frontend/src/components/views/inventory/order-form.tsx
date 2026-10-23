"use client";

import { Plus, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { formatPaise, rupeesInput, todayInIndia } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { emptyLine, orderTotals, toOrderLines, toOrderRequest, validateOrder, type LineValues, type OrderValues } from "@/lib/inventory";
import { GST_RATES_BP, type OrderView, type RequisitionView, type RfqView } from "@/lib/types";
import { useForm } from "@/lib/use-form";
import { useItems, useLookups } from "./inventory-ui";

function initialValues(order: OrderView | null, today: string, requisitionId: string, rfqId: string): OrderValues {
  if (!order) return { vendorId: "", requisitionId, rfqId, orderedOn: today, expectedOn: "", terms: "", lines: [emptyLine()] };
  return {
    vendorId: order.vendorId,
    requisitionId: order.requisitionId ?? "",
    rfqId: order.rfqId ?? "",
    orderedOn: order.orderedOn,
    expectedOn: order.expectedOn ?? "",
    terms: order.terms ?? "",
    lines: order.lines.map((l) => ({ itemId: l.itemId ?? "", description: l.description, quantity: String(l.quantity), unit: l.unit, unitPrice: rupeesInput(l.unitPaise), gstRateBp: String(l.gstRateBp) })),
  };
}

/** The lines of an approved requisition as order lines (its estimates become the prices). Exported for tests. */
export function linesFromRequisition(req: RequisitionView): LineValues[] {
  return req.lines.map((l) => ({ itemId: l.itemId ?? "", description: l.description, quantity: String(l.quantity), unit: l.unit, unitPrice: l.estimatedUnitPaise ? rupeesInput(l.estimatedUnitPaise) : "", gstRateBp: "1800" }));
}

/**
 * Raises or edits a purchase order: the vendor (the RFQ's chosen one when there is one), the requisition it
 * serves, dates, terms and the lines with GST; the totals update as the lines are typed. Saved as a draft or
 * sent for approval.
 */
export function OrderFormDialog({
  order,
  requisitionId = "",
  rfqId = "",
  onClose,
  onSaved,
}: {
  order: OrderView | null;
  requisitionId?: string;
  rfqId?: string;
  onClose: () => void;
  onSaved: (saved: OrderView, submitted: boolean) => void;
}) {
  const { t } = useI18n();
  const today = todayInIndia();
  const lookups = useLookups();
  const items = useItems();
  const form = useForm<OrderValues>(initialValues(order, today, requisitionId, rfqId));
  const [prefilled, setPrefilled] = useState(false);
  const [approved, setApproved] = useState<RequisitionView[]>([]);
  const [rfqs, setRfqs] = useState<RfqView[]>([]);
  const activeItems = (items.data ?? []).filter((i) => i.active);
  const vendors = (lookups.data?.vendors ?? []).filter((v) => v.active || v.id === form.values.vendorId);
  const setValues = form.setValues;

  // The approved requisitions and the RFQs with a chosen quote; a new order for one of them starts from its lines.
  useEffect(() => {
    if (order) return;
    let gone = false;
    void Promise.all([inventoryApi.requisitions({ status: "APPROVED" }), inventoryApi.rfqs()]).then(([reqs, allRfqs]) => {
      if (gone) return;
      setApproved(reqs);
      setRfqs(allRfqs.filter((r) => r.status === "SELECTED"));
      if (prefilled) return;
      setPrefilled(true);
      const rfq = rfqId ? allRfqs.find((r) => r.id === rfqId) : undefined;
      const reqId = requisitionId || rfq?.requisitionId || "";
      const req = reqId ? reqs.find((r) => r.id === reqId) : undefined;
      const quote = rfq?.quotes.find((q) => q.selected);
      if (req || quote) {
        setValues((prev) => ({
          ...prev,
          vendorId: quote ? quote.vendorId : prev.vendorId,
          requisitionId: req ? req.id : prev.requisitionId,
          lines: req ? linesFromRequisition(req) : prev.lines,
        }));
      }
    });
    return () => {
      gone = true;
    };
  }, [order, requisitionId, rfqId, prefilled, setValues]);

  const setLine = (i: number, patch: Partial<LineValues>) => form.set("lines", form.values.lines.map((l, n) => (n === i ? { ...l, ...patch } : l)));
  const pickItem = (i: number, itemId: string) => {
    const item = activeItems.find((it) => it.id === itemId);
    setLine(i, { itemId, description: item ? item.name : form.values.lines[i].description, unit: item ? item.unit : form.values.lines[i].unit });
  };
  const pickRequisition = (id: string) => {
    const req = approved.find((r) => r.id === id);
    form.set("requisitionId", id);
    if (req && !order) form.set("lines", linesFromRequisition(req));
  };
  const pickRfq = (id: string) => {
    const rfq = rfqs.find((r) => r.id === id);
    form.set("rfqId", id);
    const quote = rfq?.quotes.find((q) => q.selected);
    if (quote) form.set("vendorId", quote.vendorId);
    if (rfq?.requisitionId) pickRequisition(rfq.requisitionId);
  };

  const totals = orderTotals(toOrderLines(form.values.lines));

  const save = async (submit: boolean, formEl: HTMLFormElement) => {
    if (!form.check(validateOrder(form.values, today), t, formEl)) return;
    let saved: OrderView | undefined;
    const ok = await form.submit(t, async () => {
      const body = toOrderRequest(form.values);
      if (order) {
        saved = await inventoryApi.updateOrder(order.id, body);
        if (submit) saved = await inventoryApi.submitOrder(saved.id);
      } else {
        saved = await inventoryApi.createOrder(body, submit);
      }
    });
    if (ok && saved) onSaved(saved, submit);
  };

  return (
    <Dialog open onClose={onClose} title={order ? t("inventory.po.editTitle") : t("inventory.po.newTitle")} description={t("inventory.po.newDescription")} closeLabel={t("common.close")} variant="sheet">
      <form
        method="post"
        className="flex flex-col gap-3.5"
        onSubmit={(e) => {
          e.preventDefault();
          void save(false, e.currentTarget);
        }}
        noValidate
        data-testid="order-form"
      >
        <FormAlert message={form.formError} />
        {lookups.error ? <FormAlert message={t("inventory.lookupsFailed")} /> : null}
        <div className="grid gap-3.5 sm:grid-cols-2">
          <SelectField label={t("inventory.col.vendor")} name="vendorId" value={form.values.vendorId} onChange={(e) => form.set("vendorId", e.target.value)} options={[{ value: "", label: t("common.choose") }, ...vendors.map((v) => ({ value: v.id, label: v.name }))]} error={form.errors.vendorId} required data-autofocus />
          <TextField label={t("inventory.col.date")} name="orderedOn" type="date" value={form.values.orderedOn} max={today} onChange={(e) => form.set("orderedOn", e.target.value)} error={form.errors.orderedOn} required />
          {!order ? (
            <>
              <SelectField label={t("inventory.col.requisition")} name="requisitionId" value={form.values.requisitionId} onChange={(e) => pickRequisition(e.target.value)} options={[{ value: "", label: t("inventory.none") }, ...approved.map((r) => ({ value: r.id, label: `${r.number ?? ""} · ${r.purpose}` }))]} error={form.errors.requisitionId} hint={t("inventory.po.requisitionHint")} />
              <SelectField label={t("inventory.col.rfq")} name="rfqId" value={form.values.rfqId} onChange={(e) => pickRfq(e.target.value)} options={[{ value: "", label: t("inventory.none") }, ...rfqs.map((r) => ({ value: r.id, label: `${r.number} · ${r.title}` }))]} error={form.errors.rfqId} />
            </>
          ) : null}
          <TextField label={t("inventory.col.expected")} name="expectedOn" type="date" value={form.values.expectedOn} onChange={(e) => form.set("expectedOn", e.target.value)} error={form.errors.expectedOn} />
        </div>
        <TextAreaField label={t("inventory.col.terms")} name="terms" value={form.values.terms} onChange={(e) => form.set("terms", e.target.value)} error={form.errors.terms} rows={2} maxLength={1000} hint={t("inventory.po.termsHint")} />

        <fieldset className="flex flex-col gap-3">
          <legend className="field-label">{t("inventory.req.lines")}</legend>
          {form.errors.lines ? <p className="field-error">{form.errors.lines}</p> : null}
          {form.values.lines.map((line, i) => {
            const lt = orderTotals([toOrderLines([line])[0]]);
            return (
              <div key={i} className="grid gap-2 rounded-lg border border-line p-3 sm:grid-cols-12" data-testid="order-line">
                <SelectField label={t("inventory.col.item")} name={`lines.${i}.itemId`} value={line.itemId} onChange={(e) => pickItem(i, e.target.value)} options={[{ value: "", label: t("inventory.req.freeText") }, ...activeItems.map((it) => ({ value: it.id, label: it.name }))]} className="sm:col-span-5" />
                <TextField label={t("inventory.col.description")} name={`lines.${i}.description`} value={line.description} onChange={(e) => setLine(i, { description: e.target.value })} error={form.errors[`lines.${i}.description`]} maxLength={200} required className="sm:col-span-7" />
                <TextField label={t("inventory.col.quantity")} name={`lines.${i}.quantity`} inputMode="numeric" value={line.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} error={form.errors[`lines.${i}.quantity`]} required className="sm:col-span-2" />
                <TextField label={t("inventory.col.unit")} name={`lines.${i}.unit`} value={line.unit} onChange={(e) => setLine(i, { unit: e.target.value })} error={form.errors[`lines.${i}.unit`]} maxLength={20} required className="sm:col-span-2" />
                <TextField label={t("inventory.col.unitPrice")} name={`lines.${i}.unitPrice`} inputMode="decimal" value={line.unitPrice} onChange={(e) => setLine(i, { unitPrice: e.target.value })} error={form.errors[`lines.${i}.unitPrice`]} required className="sm:col-span-3" />
                <SelectField label={t("inventory.col.gst")} name={`lines.${i}.gstRateBp`} value={line.gstRateBp} onChange={(e) => setLine(i, { gstRateBp: e.target.value })} options={GST_RATES_BP.map((bp) => ({ value: String(bp), label: `${bp / 100}%` }))} className="sm:col-span-2" />
                <div className="flex items-end justify-between gap-2 sm:col-span-3">
                  <span className="num pb-2 text-[13px]" data-testid="order-line-total">
                    {formatPaise(lt.totalPaise)}
                  </span>
                  <button type="button" className="btn btn-sm" onClick={() => form.set("lines", form.values.lines.filter((_, n) => n !== i))} disabled={form.values.lines.length === 1} aria-label={t("inventory.req.removeLine")}>
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </div>
              </div>
            );
          })}
          <div>
            <button type="button" className="btn btn-sm" onClick={() => form.set("lines", [...form.values.lines, emptyLine()])} disabled={form.values.lines.length >= 50} data-testid="order-add-line">
              <Plus size={16} aria-hidden="true" />
              {t("inventory.req.addLine")}
            </button>
          </div>
        </fieldset>
        <dl className="kv text-[13.5px]" data-testid="order-totals">
          <dt>{t("inventory.col.subtotal")}</dt>
          <dd className="num">{formatPaise(totals.amountPaise)}</dd>
          <dt>{t("inventory.col.gst")}</dt>
          <dd className="num">{formatPaise(totals.taxPaise)}</dd>
          <dt>{t("inventory.col.total")}</dt>
          <dd className="num font-semibold">{formatPaise(totals.totalPaise)}</dd>
        </dl>
        <div className="flex flex-wrap justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("inventory.saveDraft")}
          </button>
          <button type="button" className="btn btn-primary" disabled={form.submitting} onClick={(e) => void save(true, e.currentTarget.form as HTMLFormElement)} data-testid="order-submit">
            {form.submitting ? t("common.working") : t("inventory.sendForApproval")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
