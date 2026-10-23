"use client";

import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { blank, validateAsset, type AssetValues } from "@/lib/inventory";
import { ASSET_CONDITIONS, type AssetCondition, type AssetRequest, type AssetView } from "@/lib/types";
import { useForm } from "@/lib/use-form";
import { useItems, useLookups } from "./inventory-ui";

function initialValues(asset: AssetView | null, today: string): AssetValues {
  return asset
    ? {
        name: asset.name,
        categoryId: asset.categoryId,
        itemId: asset.itemId ?? "",
        locationId: asset.locationId ?? "",
        custodianId: asset.custodianId ?? "",
        purchaseDate: asset.purchaseDate,
        cost: rupeesInput(asset.costPaise),
        vendorId: asset.vendorId ?? "",
        serialNo: asset.serialNo ?? "",
        condition: asset.condition,
        warrantyExpiresOn: asset.warrantyExpiresOn ?? "",
        notes: asset.notes ?? "",
      }
    : { name: "", categoryId: "", itemId: "", locationId: "", custodianId: "", purchaseDate: today, cost: "", vendorId: "", serialNo: "", condition: "GOOD", warrantyExpiresOn: "", notes: "" };
}

/** The request the form sends. Exported for tests. */
export function toAssetRequest(values: AssetValues): AssetRequest {
  return {
    name: values.name.trim(),
    categoryId: values.categoryId,
    itemId: values.itemId || null,
    locationId: values.locationId || null,
    custodianId: values.custodianId || null,
    purchaseDate: values.purchaseDate,
    costPaise: parseRupees(values.cost) ?? 0,
    vendorId: values.vendorId || null,
    serialNo: blank(values.serialNo),
    condition: values.condition,
    warrantyExpiresOn: values.warrantyExpiresOn || null,
    notes: blank(values.notes),
  };
}

/** Adds or edits a fixed asset; the tag is given by the server. */
export function AssetForm({ asset, onClose, onSaved }: { asset: AssetView | null; onClose: () => void; onSaved: (asset: AssetView) => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const lookups = useLookups();
  const items = useItems();
  const form = useForm<AssetValues>(initialValues(asset, today));
  const categories = (lookups.data?.categories ?? []).filter((c) => c.active || c.id === form.values.categoryId);
  const locations = (lookups.data?.locations ?? []).filter((l) => l.active || l.id === form.values.locationId);
  const vendors = (lookups.data?.vendors ?? []).filter((v) => v.active || v.id === form.values.vendorId);
  const people = lookups.data?.people ?? [];
  const assetItems = (items.data ?? []).filter((i) => i.kind === "ASSET" && (i.active || i.id === form.values.itemId));
  const category = categories.find((c) => c.id === form.values.categoryId);

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateAsset(form.values, today), t, event.currentTarget)) return;
    let saved: AssetView | undefined;
    const ok = await form.submit(t, async () => {
      const body = toAssetRequest(form.values);
      saved = asset ? await inventoryApi.updateAsset(asset.id, body) : await inventoryApi.createAsset(body);
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog open onClose={onClose} title={asset ? t("inventory.asset.editTitle") : t("inventory.asset.newTitle")} description={asset ? undefined : t("inventory.asset.newDescription")} closeLabel={t("common.close")} variant="sheet">
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="asset-form">
        <FormAlert message={form.formError} />
        {lookups.error ? <FormAlert message={t("inventory.lookupsFailed")} /> : null}
        <TextField label={t("inventory.col.asset")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required data-autofocus />
        <div className="grid gap-3.5 sm:grid-cols-2">
          <SelectField
            label={t("inventory.col.category")}
            name="categoryId"
            value={form.values.categoryId}
            onChange={(e) => form.set("categoryId", e.target.value)}
            options={[{ value: "", label: t("common.choose") }, ...categories.map((c) => ({ value: c.id, label: c.name }))]}
            error={form.errors.categoryId}
            hint={category ? t("inventory.asset.depreciationHint", { rate: (category.depreciationRateBp / 100).toString() }) : undefined}
            required
          />
          <SelectField
            label={t("inventory.col.item")}
            name="itemId"
            value={form.values.itemId}
            onChange={(e) => form.set("itemId", e.target.value)}
            options={[{ value: "", label: t("inventory.none") }, ...assetItems.map((i) => ({ value: i.id, label: i.name }))]}
            error={form.errors.itemId}
          />
          <TextField label={t("inventory.col.purchased")} name="purchaseDate" type="date" value={form.values.purchaseDate} max={today} onChange={(e) => form.set("purchaseDate", e.target.value)} error={form.errors.purchaseDate} required />
          <TextField label={t("inventory.col.cost")} name="cost" inputMode="decimal" value={form.values.cost} onChange={(e) => form.set("cost", e.target.value)} error={form.errors.cost} hint={t("inventory.asset.costHint")} required />
          <SelectField
            label={t("inventory.col.location")}
            name="locationId"
            value={form.values.locationId}
            onChange={(e) => form.set("locationId", e.target.value)}
            options={[{ value: "", label: t("inventory.none") }, ...locations.map((l) => ({ value: l.id, label: l.name }))]}
            error={form.errors.locationId}
          />
          <SelectField
            label={t("inventory.col.custodian")}
            name="custodianId"
            value={form.values.custodianId}
            onChange={(e) => form.set("custodianId", e.target.value)}
            options={[{ value: "", label: t("inventory.none") }, ...people.map((p) => ({ value: p.id, label: p.departmentName ? `${p.name} · ${p.departmentName}` : p.name }))]}
            error={form.errors.custodianId}
          />
          <SelectField
            label={t("inventory.col.vendor")}
            name="vendorId"
            value={form.values.vendorId}
            onChange={(e) => form.set("vendorId", e.target.value)}
            options={[{ value: "", label: t("inventory.none") }, ...vendors.map((v) => ({ value: v.id, label: v.name }))]}
            error={form.errors.vendorId}
          />
          <TextField label={t("inventory.col.serialNo")} name="serialNo" value={form.values.serialNo} onChange={(e) => form.set("serialNo", e.target.value)} error={form.errors.serialNo} maxLength={80} />
          <SelectField
            label={t("inventory.col.condition")}
            name="condition"
            value={form.values.condition}
            onChange={(e) => form.set("condition", e.target.value as AssetCondition)}
            options={ASSET_CONDITIONS.map((c) => ({ value: c, label: translateOr(t, `inventory.condition.${c}`, c) }))}
          />
          <TextField label={t("inventory.col.warranty")} name="warrantyExpiresOn" type="date" value={form.values.warrantyExpiresOn} onChange={(e) => form.set("warrantyExpiresOn", e.target.value)} error={form.errors.warrantyExpiresOn} />
        </div>
        <TextAreaField label={t("inventory.col.notes")} name="notes" value={form.values.notes} onChange={(e) => form.set("notes", e.target.value)} error={form.errors.notes} rows={2} maxLength={500} />
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
