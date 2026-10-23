"use client";

import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { parseRupees, todayInIndia } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { blank, parseQuantity, validateMovement, type MovementValues } from "@/lib/inventory";
import type { ItemView, MovementKind, MovementView } from "@/lib/types";
import { useForm } from "@/lib/use-form";
import { useLookups } from "./inventory-ui";

export type MovementDialogKind = Exclude<MovementKind, "ADJUSTMENT">;

/**
 * Records a goods receipt (without an order), an issue to a department or person with its purpose, a return, or a
 * write-off with its reason, for one item or any consumable.
 */
export function MovementDialog({
  kind,
  item,
  items,
  onClose,
  onSaved,
}: {
  kind: MovementDialogKind;
  /** the item the ledger shows, or null to pick one */
  item: ItemView | null;
  items?: ItemView[];
  onClose: () => void;
  onSaved: (movement: MovementView) => void;
}) {
  const { t } = useI18n();
  const today = todayInIndia();
  const lookups = useLookups();
  const form = useForm<MovementValues>({
    itemId: item?.id ?? "",
    locationId: "",
    quantity: "",
    unitCost: "",
    movedOn: today,
    departmentId: "",
    issuedToId: "",
    purpose: "",
  });
  const chosen = item ?? (items ?? []).find((i) => i.id === form.values.itemId) ?? null;
  const locations = (lookups.data?.locations ?? []).filter((l) => l.active);
  const people = lookups.data?.people ?? [];
  const departments = lookups.data?.departments ?? [];
  const titles: Record<MovementDialogKind, string> = {
    RECEIPT: t("inventory.move.receiptTitle"),
    ISSUE: t("inventory.move.issueTitle"),
    RETURN: t("inventory.move.returnTitle"),
    WRITE_OFF: t("inventory.move.writeOffTitle"),
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateMovement(form.values, kind, today, chosen?.stock), t, event.currentTarget)) return;
    const v = form.values;
    const quantity = parseQuantity(v.quantity) ?? 0;
    const locationId = v.locationId || null;
    let saved: MovementView | undefined;
    const ok = await form.submit(t, async () => {
      if (kind === "RECEIPT") {
        saved = await inventoryApi.receive({
          itemId: v.itemId,
          locationId,
          quantity,
          unitCostPaise: v.unitCost.trim() ? parseRupees(v.unitCost) : null,
          movedOn: v.movedOn,
          purpose: blank(v.purpose),
        });
      } else if (kind === "WRITE_OFF") {
        saved = await inventoryApi.writeOff({ itemId: v.itemId, locationId, quantity, movedOn: v.movedOn, reason: v.purpose.trim() });
      } else {
        const body = { itemId: v.itemId, locationId, quantity, movedOn: v.movedOn, departmentId: v.departmentId || null, issuedToId: v.issuedToId || null, purpose: v.purpose.trim() };
        saved = kind === "ISSUE" ? await inventoryApi.issue(body) : await inventoryApi.giveBack(body);
      }
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog open onClose={onClose} title={titles[kind]} description={chosen ? t("inventory.move.onHand", { stock: chosen.stock, unit: chosen.unit }) : undefined} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid={`movement-form-${kind}`}>
        <FormAlert message={form.formError} />
        {!item ? (
          <SelectField
            label={t("inventory.col.item")}
            name="itemId"
            value={form.values.itemId}
            onChange={(e) => form.set("itemId", e.target.value)}
            options={[{ value: "", label: t("common.choose") }, ...(items ?? []).filter((i) => i.active).map((i) => ({ value: i.id, label: `${i.name} (${i.stock} ${i.unit})` }))]}
            error={form.errors.itemId}
            required
            data-autofocus
          />
        ) : null}
        <div className="grid gap-3.5 sm:grid-cols-2">
          <TextField
            label={t("inventory.col.quantity")}
            name="quantity"
            inputMode="numeric"
            value={form.values.quantity}
            onChange={(e) => form.set("quantity", e.target.value)}
            error={form.errors.quantity}
            hint={chosen ? chosen.unit : undefined}
            required
            data-autofocus={item ? true : undefined}
          />
          <TextField label={t("inventory.col.date")} name="movedOn" type="date" value={form.values.movedOn} max={today} onChange={(e) => form.set("movedOn", e.target.value)} error={form.errors.movedOn} required />
          {kind === "RECEIPT" ? (
            <TextField label={t("inventory.move.unitCost")} name="unitCost" inputMode="decimal" value={form.values.unitCost} onChange={(e) => form.set("unitCost", e.target.value)} error={form.errors.unitCost} hint={t("inventory.move.unitCostHint")} />
          ) : null}
          <SelectField
            label={t("inventory.col.location")}
            name="locationId"
            value={form.values.locationId}
            onChange={(e) => form.set("locationId", e.target.value)}
            options={[{ value: "", label: t("inventory.none") }, ...locations.map((l) => ({ value: l.id, label: l.name }))]}
            error={form.errors.locationId}
          />
          {kind === "ISSUE" || kind === "RETURN" ? (
            <>
              <SelectField
                label={t("inventory.col.department")}
                name="departmentId"
                value={form.values.departmentId}
                onChange={(e) => form.set("departmentId", e.target.value)}
                options={[{ value: "", label: t("inventory.none") }, ...departments.map((d) => ({ value: d.id, label: d.name }))]}
                error={form.errors.departmentId}
              />
              <SelectField
                label={t("inventory.col.person")}
                name="issuedToId"
                value={form.values.issuedToId}
                onChange={(e) => form.set("issuedToId", e.target.value)}
                options={[{ value: "", label: t("inventory.none") }, ...people.map((p) => ({ value: p.id, label: p.departmentName ? `${p.name} · ${p.departmentName}` : p.name }))]}
                error={form.errors.issuedToId}
              />
            </>
          ) : null}
        </div>
        <TextAreaField
          label={kind === "WRITE_OFF" ? t("inventory.col.reason") : t("inventory.col.purpose")}
          name="purpose"
          value={form.values.purpose}
          onChange={(e) => form.set("purpose", e.target.value)}
          error={form.errors.purpose ?? form.errors.reason}
          rows={2}
          maxLength={500}
          required={kind === "ISSUE" || kind === "WRITE_OFF"}
        />
        <div className="flex justify-end gap-2 pt-1">
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
