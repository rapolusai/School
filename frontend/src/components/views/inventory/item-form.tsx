"use client";

import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { parseQuantity, validateItem, type ItemValues } from "@/lib/inventory";
import type { ItemRequest, ItemView } from "@/lib/types";
import { useForm } from "@/lib/use-form";
import { useLookups } from "./inventory-ui";

function initialValues(item: ItemView | null): ItemValues {
  return item
    ? {
        name: item.name,
        categoryId: item.categoryId,
        unit: item.unit,
        kind: item.kind,
        reorderLevel: String(item.reorderLevel),
        preferredVendorId: item.preferredVendorId ?? "",
        active: item.active,
      }
    : { name: "", categoryId: "", unit: "", kind: "CONSUMABLE", reorderLevel: "0", preferredVendorId: "", active: true };
}

/** The request the form sends. Exported for tests. */
export function toItemRequest(values: ItemValues): ItemRequest {
  return {
    name: values.name.trim(),
    categoryId: values.categoryId,
    unit: values.unit.trim(),
    kind: values.kind,
    reorderLevel: parseQuantity(values.reorderLevel || "0") ?? 0,
    preferredVendorId: values.preferredVendorId || null,
    active: values.active,
  };
}

/** Adds or edits an item: name, category, unit, consumable or asset, reorder level and preferred vendor. */
export function ItemFormDialog({ item, onClose, onSaved }: { item: ItemView | null; onClose: () => void; onSaved: (item: ItemView) => void }) {
  const { t } = useI18n();
  const lookups = useLookups();
  const form = useForm<ItemValues>(initialValues(item));
  const categories = (lookups.data?.categories ?? []).filter((c) => c.active || c.id === form.values.categoryId);
  const vendors = (lookups.data?.vendors ?? []).filter((v) => v.active || v.id === form.values.preferredVendorId);

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateItem(form.values), t, event.currentTarget)) return;
    let saved: ItemView | undefined;
    const ok = await form.submit(t, async () => {
      const body = toItemRequest(form.values);
      saved = item ? await inventoryApi.updateItem(item.id, body) : await inventoryApi.createItem(body);
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog open onClose={onClose} title={item ? t("inventory.item.editTitle") : t("inventory.item.newTitle")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="item-form">
        <FormAlert message={form.formError} />
        {lookups.error ? <FormAlert message={t("inventory.lookupsFailed")} /> : null}
        <TextField label={t("inventory.col.item")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required data-autofocus />
        <div className="grid gap-3.5 sm:grid-cols-2">
          <SelectField
            label={t("inventory.col.category")}
            name="categoryId"
            value={form.values.categoryId}
            onChange={(e) => form.set("categoryId", e.target.value)}
            options={[{ value: "", label: t("common.choose") }, ...categories.map((c) => ({ value: c.id, label: c.name }))]}
            error={form.errors.categoryId}
            required
          />
          <SelectField
            label={t("inventory.col.kind")}
            name="kind"
            value={form.values.kind}
            onChange={(e) => form.set("kind", e.target.value as ItemValues["kind"])}
            options={[
              { value: "CONSUMABLE", label: t("inventory.kind.CONSUMABLE") },
              { value: "ASSET", label: t("inventory.kind.ASSET") },
            ]}
            hint={t("inventory.item.kindHint")}
          />
          <TextField label={t("inventory.col.unit")} name="unit" value={form.values.unit} onChange={(e) => form.set("unit", e.target.value)} error={form.errors.unit} maxLength={20} placeholder={t("inventory.item.unitExample")} required />
          <TextField
            label={t("inventory.col.reorderLevel")}
            name="reorderLevel"
            inputMode="numeric"
            value={form.values.reorderLevel}
            onChange={(e) => form.set("reorderLevel", e.target.value)}
            error={form.errors.reorderLevel}
            hint={t("inventory.item.reorderHint")}
          />
          <SelectField
            label={t("inventory.item.preferredVendor")}
            name="preferredVendorId"
            value={form.values.preferredVendorId}
            onChange={(e) => form.set("preferredVendorId", e.target.value)}
            options={[{ value: "", label: t("inventory.none") }, ...vendors.map((v) => ({ value: v.id, label: v.name }))]}
            error={form.errors.preferredVendorId}
          />
        </div>
        {item ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            <span>{t("inventory.active")}</span>
          </label>
        ) : null}
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
