"use client";

import { Plus, Trash2 } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { formatPaise, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { emptyLine, parseQuantity, toRequisitionRequest, validateRequisition, type LineValues, type RequisitionValues } from "@/lib/inventory";
import type { RequisitionView } from "@/lib/types";
import { useForm } from "@/lib/use-form";
import { useItems, useLookups } from "./inventory-ui";

function initialValues(req: RequisitionView | null): RequisitionValues {
  if (!req) return { departmentId: "", neededBy: "", purpose: "", lines: [emptyLine()] };
  return {
    departmentId: req.departmentId ?? "",
    neededBy: req.neededBy ?? "",
    purpose: req.purpose,
    lines: req.lines.map((l) => ({ itemId: l.itemId ?? "", description: l.description, quantity: String(l.quantity), unit: l.unit, unitPrice: l.estimatedUnitPaise ? rupeesInput(l.estimatedUnitPaise) : "", gstRateBp: "0" })),
  };
}

/** The estimated total of the lines typed so far. Exported for tests. */
export function estimatedTotal(lines: readonly LineValues[]): number {
  return lines.reduce((sum, l) => sum + (parseQuantity(l.quantity) ?? 0) * (l.unitPrice.trim() ? (parseRupees(l.unitPrice) ?? 0) : 0), 0);
}

/**
 * Raises or edits a requisition: the department, when it is needed, what for, and the lines (an item from the
 * stores or free text, a quantity, a unit and an estimated price). Saved as a draft or sent for approval.
 */
export function RequisitionFormDialog({ requisition, onClose, onSaved }: { requisition: RequisitionView | null; onClose: () => void; onSaved: (saved: RequisitionView, submitted: boolean) => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const lookups = useLookups();
  const items = useItems();
  const form = useForm<RequisitionValues>(initialValues(requisition));
  const activeItems = (items.data ?? []).filter((i) => i.active);

  const setLine = (i: number, patch: Partial<LineValues>) => {
    form.set(
      "lines",
      form.values.lines.map((l, n) => (n === i ? { ...l, ...patch } : l)),
    );
  };
  const pickItem = (i: number, itemId: string) => {
    const item = activeItems.find((it) => it.id === itemId);
    setLine(i, { itemId, description: item ? item.name : form.values.lines[i].description, unit: item ? item.unit : form.values.lines[i].unit });
  };

  const save = async (submit: boolean, formEl: HTMLFormElement) => {
    if (!form.check(validateRequisition(form.values, today), t, formEl)) return;
    let saved: RequisitionView | undefined;
    const ok = await form.submit(t, async () => {
      const body = toRequisitionRequest(form.values);
      if (requisition) {
        saved = await inventoryApi.updateRequisition(requisition.id, body);
        if (submit) saved = await inventoryApi.submitRequisition(saved.id);
      } else {
        saved = await inventoryApi.createRequisition(body, submit);
      }
    });
    if (ok && saved) onSaved(saved, submit);
  };

  return (
    <Dialog open onClose={onClose} title={requisition ? t("inventory.req.editTitle") : t("inventory.req.newTitle")} description={t("inventory.req.newDescription")} closeLabel={t("common.close")} variant="sheet">
      <form
        method="post"
        className="flex flex-col gap-3.5"
        onSubmit={(e) => {
          e.preventDefault();
          void save(false, e.currentTarget);
        }}
        noValidate
        data-testid="requisition-form"
      >
        <FormAlert message={form.formError} />
        {lookups.error ? <FormAlert message={t("inventory.lookupsFailed")} /> : null}
        <div className="grid gap-3.5 sm:grid-cols-2">
          <SelectField
            label={t("inventory.col.department")}
            name="departmentId"
            value={form.values.departmentId}
            onChange={(e) => form.set("departmentId", e.target.value)}
            options={[{ value: "", label: t("inventory.none") }, ...(lookups.data?.departments ?? []).map((d) => ({ value: d.id, label: d.name }))]}
            error={form.errors.departmentId}
            hint={t("inventory.req.departmentHint")}
            data-autofocus
          />
          <TextField label={t("inventory.col.neededBy")} name="neededBy" type="date" value={form.values.neededBy} min={today} onChange={(e) => form.set("neededBy", e.target.value)} error={form.errors.neededBy} />
        </div>
        <TextAreaField label={t("inventory.col.purpose")} name="purpose" value={form.values.purpose} onChange={(e) => form.set("purpose", e.target.value)} error={form.errors.purpose} rows={2} maxLength={500} required />

        <fieldset className="flex flex-col gap-3">
          <legend className="field-label">{t("inventory.req.lines")}</legend>
          {form.errors.lines ? <p className="field-error">{form.errors.lines}</p> : null}
          {form.values.lines.map((line, i) => (
            <div key={i} className="grid gap-2 rounded-lg border border-line p-3 sm:grid-cols-12" data-testid="requisition-line">
              <SelectField
                label={t("inventory.col.item")}
                name={`lines.${i}.itemId`}
                value={line.itemId}
                onChange={(e) => pickItem(i, e.target.value)}
                options={[{ value: "", label: t("inventory.req.freeText") }, ...activeItems.map((it) => ({ value: it.id, label: `${it.name} (${it.stock} ${it.unit})` }))]}
                className="sm:col-span-5"
              />
              <TextField label={t("inventory.col.description")} name={`lines.${i}.description`} value={line.description} onChange={(e) => setLine(i, { description: e.target.value })} error={form.errors[`lines.${i}.description`]} maxLength={200} required className="sm:col-span-7" />
              <TextField label={t("inventory.col.quantity")} name={`lines.${i}.quantity`} inputMode="numeric" value={line.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} error={form.errors[`lines.${i}.quantity`]} required className="sm:col-span-3" />
              <TextField label={t("inventory.col.unit")} name={`lines.${i}.unit`} value={line.unit} onChange={(e) => setLine(i, { unit: e.target.value })} error={form.errors[`lines.${i}.unit`]} maxLength={20} required className="sm:col-span-3" />
              <TextField label={t("inventory.req.estimatedPrice")} name={`lines.${i}.unitPrice`} inputMode="decimal" value={line.unitPrice} onChange={(e) => setLine(i, { unitPrice: e.target.value })} error={form.errors[`lines.${i}.unitPrice`]} className="sm:col-span-4" />
              <div className="flex items-end justify-end sm:col-span-2">
                <button type="button" className="btn btn-sm" onClick={() => form.set("lines", form.values.lines.filter((_, n) => n !== i))} disabled={form.values.lines.length === 1} aria-label={t("inventory.req.removeLine")}>
                  <Trash2 size={16} aria-hidden="true" />
                </button>
              </div>
            </div>
          ))}
          <div>
            <button type="button" className="btn btn-sm" onClick={() => form.set("lines", [...form.values.lines, emptyLine()])} disabled={form.values.lines.length >= 50} data-testid="requisition-add-line">
              <Plus size={16} aria-hidden="true" />
              {t("inventory.req.addLine")}
            </button>
          </div>
        </fieldset>
        <dl className="kv text-[13.5px]" data-testid="requisition-total">
          <dt>{t("inventory.req.estimated")}</dt>
          <dd className="num font-semibold">{formatPaise(estimatedTotal(form.values.lines))}</dd>
        </dl>
        <div className="flex flex-wrap justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("inventory.saveDraft")}
          </button>
          <button type="button" className="btn btn-primary" disabled={form.submitting} onClick={(e) => void save(true, e.currentTarget.form as HTMLFormElement)} data-testid="requisition-submit">
            {form.submitting ? t("common.working") : t("inventory.sendForApproval")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
