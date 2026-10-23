"use client";

import { Plus } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, parseRupees, rupeesInput } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { MAX_PAISE } from "@/lib/inventory";
import { LOCATION_KINDS, type CategoryView, type LocationKind, type LocationView, type PersonView, type PurchaseApproverView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { InventoryNav } from "./inventory-ui";

/** /app/inventory/setup: categories with their depreciation rate, locations, and who approves purchases up to what. */
export function InventorySetupView() {
  const { t } = useI18n();
  return (
    <>
      <PageHead eyebrow={t("inventory.eyebrow")} title={t("inventory.setup.title")} />
      <InventoryNav />
      <div className="grid gap-3.5 lg:grid-cols-2">
        <CategoriesCard />
        <LocationsCard />
      </div>
      <ApproversCard />
    </>
  );
}

type CategoryValues = { name: string; rate: string; active: boolean };

/** The same checks the category endpoints make. Exported for tests. */
export function validateCategory(values: CategoryValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "inventory.v.name";
  else if (values.name.trim().length > 80) problems.name = "inventory.v.nameLong";
  const rate = Number(values.rate.trim() || "0");
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(values.rate.trim() || "0") || rate > 100) problems.rate = "inventory.v.rate";
  return problems;
}

function CategoriesCard() {
  const { t } = useI18n();
  const { toast } = useToast();
  const list = useApiData("inventory:categories", inventoryApi.categories);
  const [editing, setEditing] = useState<CategoryView | null | "new">(null);
  const [deleting, setDeleting] = useState<CategoryView | null>(null);
  return (
    <section className="card" aria-labelledby="categories-title">
      <div className="card-head">
        <h2 id="categories-title">{t("inventory.setup.categories")}</h2>
        <button type="button" className="btn btn-sm" onClick={() => setEditing("new")} data-testid="category-new">
          <Plus size={16} aria-hidden="true" />
          {t("inventory.setup.newCategory")}
        </button>
      </div>
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={3} />
      ) : list.data.length === 0 ? (
        <p className="empty" data-testid="categories-empty">
          {t("inventory.setup.noCategories")}
        </p>
      ) : (
        <ul className="rowcards" data-testid="categories-list">
          {list.data.map((c) => (
            <li key={c.id} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block font-semibold">
                  {c.name}
                  {!c.active ? <span className="ml-2 pill pill-neutral">{t("inventory.inactive")}</span> : null}
                </b>
                <span className="block text-[12.5px] text-ink-3">{t("inventory.setup.rate", { rate: String(c.depreciationRateBp / 100) })}</span>
              </span>
              <span className="flex gap-2">
                <button type="button" className="btn btn-sm" onClick={() => setEditing(c)}>
                  {t("common.edit")}
                </button>
                {!c.inUse ? (
                  <button type="button" className="btn btn-sm" onClick={() => setDeleting(c)}>
                    {t("common.delete")}
                  </button>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
      )}
      {editing ? (
        <CategoryDialog
          category={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("inventory.saved"));
            list.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting !== null}
        title={t("inventory.setup.deleteCategory")}
        body={t("inventory.setup.deleteBody", { name: deleting?.name ?? "" })}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          await inventoryApi.deleteCategory(deleting.id);
          setDeleting(null);
          toast(t("common.deleted"));
          list.reload();
        }}
      />
    </section>
  );
}

function CategoryDialog({ category, onClose, onSaved }: { category: CategoryView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<CategoryValues>({ name: category?.name ?? "", rate: category ? String(category.depreciationRateBp / 100) : "0", active: category?.active ?? true });
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateCategory(form.values), t, event.currentTarget)) return;
    const body = { name: form.values.name.trim(), depreciationRateBp: Math.round(Number(form.values.rate.trim() || "0") * 100), active: form.values.active };
    const ok = await form.submit(t, async () => {
      if (category) await inventoryApi.updateCategory(category.id, body);
      else await inventoryApi.createCategory(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open onClose={onClose} title={category ? t("inventory.setup.editCategory") : t("inventory.setup.newCategory")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="category-form">
        <FormAlert message={form.formError} />
        <TextField label={t("inventory.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={80} required data-autofocus />
        <TextField label={t("inventory.setup.depreciationRate")} name="rate" inputMode="decimal" value={form.values.rate} onChange={(e) => form.set("rate", e.target.value)} error={form.errors.rate ?? form.errors.depreciationRateBp} hint={t("inventory.setup.rateHint")} />
        {category ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            <span>{t("inventory.active")}</span>
          </label>
        ) : null}
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

function LocationsCard() {
  const { t } = useI18n();
  const { toast } = useToast();
  const list = useApiData("inventory:locations", inventoryApi.locations);
  const [editing, setEditing] = useState<LocationView | null | "new">(null);
  const [deleting, setDeleting] = useState<LocationView | null>(null);
  return (
    <section className="card" aria-labelledby="locations-title">
      <div className="card-head">
        <h2 id="locations-title">{t("inventory.setup.locations")}</h2>
        <button type="button" className="btn btn-sm" onClick={() => setEditing("new")} data-testid="location-new">
          <Plus size={16} aria-hidden="true" />
          {t("inventory.setup.newLocation")}
        </button>
      </div>
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={3} />
      ) : list.data.length === 0 ? (
        <p className="empty" data-testid="locations-empty">
          {t("inventory.setup.noLocations")}
        </p>
      ) : (
        <ul className="rowcards" data-testid="locations-list">
          {list.data.map((l) => (
            <li key={l.id} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block font-semibold">
                  {l.name}
                  {!l.active ? <span className="ml-2 pill pill-neutral">{t("inventory.inactive")}</span> : null}
                </b>
                <span className="block text-[12.5px] text-ink-3">{translateOr(t, `inventory.locationKind.${l.kind}`, l.kind)}</span>
              </span>
              <span className="flex gap-2">
                <button type="button" className="btn btn-sm" onClick={() => setEditing(l)}>
                  {t("common.edit")}
                </button>
                {!l.inUse ? (
                  <button type="button" className="btn btn-sm" onClick={() => setDeleting(l)}>
                    {t("common.delete")}
                  </button>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
      )}
      {editing ? (
        <LocationDialog
          location={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("inventory.saved"));
            list.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting !== null}
        title={t("inventory.setup.deleteLocation")}
        body={t("inventory.setup.deleteBody", { name: deleting?.name ?? "" })}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          await inventoryApi.deleteLocation(deleting.id);
          setDeleting(null);
          toast(t("common.deleted"));
          list.reload();
        }}
      />
    </section>
  );
}

function LocationDialog({ location, onClose, onSaved }: { location: LocationView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ name: string; kind: LocationKind; active: boolean }>({ name: location?.name ?? "", kind: location?.kind ?? "STORE", active: location?.active ?? true });
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!form.values.name.trim()) problems.name = "inventory.v.name";
    else if (form.values.name.trim().length > 80) problems.name = "inventory.v.nameLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const body = { name: form.values.name.trim(), kind: form.values.kind, active: form.values.active };
    const ok = await form.submit(t, async () => {
      if (location) await inventoryApi.updateLocation(location.id, body);
      else await inventoryApi.createLocation(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open onClose={onClose} title={location ? t("inventory.setup.editLocation") : t("inventory.setup.newLocation")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="location-form">
        <FormAlert message={form.formError} />
        <TextField label={t("inventory.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={80} required data-autofocus />
        <SelectField label={t("inventory.col.kind")} name="kind" value={form.values.kind} onChange={(e) => form.set("kind", e.target.value as LocationKind)} options={LOCATION_KINDS.map((k) => ({ value: k, label: translateOr(t, `inventory.locationKind.${k}`, k) }))} />
        {location ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            <span>{t("inventory.active")}</span>
          </label>
        ) : null}
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

type ApproverRow = { userId: string; name: string; on: boolean; limit: string };

/** Approvers with a limit above zero (or none); exported for tests. */
export function approverProblems(rows: ApproverRow[]): Record<string, string> {
  const problems: Record<string, string> = {};
  for (const row of rows) {
    if (!row.on || !row.limit.trim()) continue;
    const paise = parseRupees(row.limit);
    if (paise === null || paise <= 0 || paise > MAX_PAISE) problems[row.userId] = "inventory.v.limit";
  }
  return problems;
}

function initialRows(saved: PurchaseApproverView[], candidates: PersonView[]): ApproverRow[] {
  const current = new Map(saved.map((a) => [a.userId, a]));
  const all = [...candidates];
  for (const a of saved) if (!all.some((c) => c.id === a.userId)) all.push({ id: a.userId, name: a.userName, departmentName: null });
  return all.map((c) => ({ userId: c.id, name: c.name, on: current.has(c.id), limit: current.get(c.id)?.limitPaise ? rupeesInput(current.get(c.id)?.limitPaise ?? 0) : "" }));
}

function ApproversCard() {
  const { t } = useI18n();
  const { toast } = useToast();
  const approvers = useApiData("inventory:approvers", inventoryApi.approvers);
  const candidates = useApiData("inventory:approver-candidates", inventoryApi.approverCandidates);
  const [rows, setRows] = useState<ApproverRow[] | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Until the person edits, the rows come from what is saved; their edits then take over until saved.
  const shown: ApproverRow[] | null = rows ?? (approvers.data && candidates.data ? initialRows(approvers.data, candidates.data) : null);
  const edit = (userId: string, patch: Partial<ApproverRow>) => setRows((shown ?? []).map((r) => (r.userId === userId ? { ...r, ...patch } : r)));

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!shown) return;
    const rows = shown;
    const problems = approverProblems(rows);
    setErrors(Object.fromEntries(Object.entries(problems).map(([k, v]) => [k, t(v as "inventory.v.limit")])));
    if (Object.keys(problems).length > 0) return;
    setSaving(true);
    setFormError(null);
    try {
      await inventoryApi.saveApprovers({ approvers: rows.filter((r) => r.on).map((r) => ({ userId: r.userId, limitPaise: r.limit.trim() ? parseRupees(r.limit) : null })) });
      toast(t("inventory.saved"));
      setRows(null);
      approvers.reload();
    } catch (caught) {
      setFormError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="card" aria-labelledby="approvers-title">
      <div className="card-head">
        <div>
          <h2 id="approvers-title">{t("inventory.setup.approvers")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("inventory.setup.approversHint")}</p>
        </div>
      </div>
      {(approvers.error ?? candidates.error) && !shown ? (
        <ErrorState
          error={(approvers.error ?? candidates.error)!}
          onRetry={() => {
            approvers.reload();
            candidates.reload();
          }}
        />
      ) : !shown ? (
        <LoadingRows rows={3} />
      ) : shown.length === 0 ? (
        <p className="empty" data-testid="approvers-empty">
          {t("inventory.setup.noApprovers")}
        </p>
      ) : (
        <form method="post" className="flex flex-col gap-3" onSubmit={(e) => void save(e)} noValidate data-testid="approvers-form">
          <FormAlert message={formError} />
          <ul className="flex flex-col gap-2" data-testid="approvers-list">
            {shown.map((row) => (
              <li key={row.userId} className="flex flex-wrap items-center gap-3 rounded-lg border border-line p-3">
                <label className="check min-w-48 flex-1">
                  <input type="checkbox" name={`approver-${row.userId}`} checked={row.on} onChange={(e) => edit(row.userId, { on: e.target.checked })} />
                  <span>{row.name}</span>
                </label>
                <TextField
                  label={t("inventory.setup.limit")}
                  name={`limit-${row.userId}`}
                  inputMode="decimal"
                  value={row.limit}
                  onChange={(e) => edit(row.userId, { limit: e.target.value })}
                  disabled={!row.on}
                  error={errors[row.userId]}
                  hint={row.on && !row.limit.trim() ? t("inventory.setup.noLimit") : row.on && parseRupees(row.limit) ? formatPaise(parseRupees(row.limit) ?? 0) : undefined}
                  className="w-48"
                />
              </li>
            ))}
          </ul>
          <div className="flex justify-end">
            <button type="submit" className="btn btn-primary" disabled={saving} data-testid="approvers-save">
              {saving ? t("common.saving") : t("common.save")}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
