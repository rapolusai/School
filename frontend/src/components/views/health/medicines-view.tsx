"use client";

import { Pencil, Pill as PillIcon, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate } from "@/lib/format";
import { healthApi } from "@/lib/health-api";
import { localeFor, useI18n } from "@/lib/i18n";
import type { Medicine, MedicineRequest } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { DATE, HealthNav, useCanManageHealth } from "./health-shared";

type MedicineValues = { name: string; unit: string; quantity: string; reorderLevel: string; expiresOn: string };

/** Client-side checks mirroring MedicineForm on the API. Pure and exported for tests. */
export function medicineProblems(values: MedicineValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "health.v.name";
  if (!values.unit.trim()) problems.unit = "health.v.unit";
  if (!/^\d+$/.test(values.quantity)) problems.quantity = "health.v.quantity";
  if (values.reorderLevel && !/^\d+$/.test(values.reorderLevel)) problems.reorderLevel = "health.v.quantity";
  if (values.expiresOn && !DATE.test(values.expiresOn)) problems.expiresOn = "health.v.date";
  return problems;
}

/** /app/health/medicines: the small medicine stock the infirmary gives from. */
export function MedicinesView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageHealth();
  const list = useApiData("health:medicines", healthApi.medicines);
  const [editing, setEditing] = useState<Medicine | "new" | null>(null);
  const [removing, setRemoving] = useState<Medicine | null>(null);
  const rows = list.data ?? [];
  return (
    <>
      <PageHead
        eyebrow={t("nav.health")}
        title={t("health.medicine.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="medicine-add">
              <Plus size={18} aria-hidden="true" />
              {t("health.medicine.add")}
            </button>
          ) : undefined
        }
      />
      <HealthNav />
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={3} />
      ) : rows.length === 0 ? (
        <p className="empty">{t("health.medicine.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="medicine-list">
          {rows.map((m) => (
            <li key={m.id} className="rowcard items-start" data-testid="medicine-row" data-low={m.low}>
              <span className="badge-ic" aria-hidden="true">
                <PillIcon size={18} />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold text-ink">{m.name}</span>
                  <span className="num text-[13px] text-ink-2">
                    {m.quantity} {m.unit}
                  </span>
                  {m.low ? <Pill tone="warn">{t("health.medicine.lowPill")}</Pill> : null}
                  {m.expired ? <Pill tone="bad">{t("health.medicine.expired")}</Pill> : null}
                </div>
                <p className="text-[12.5px] text-ink-3">
                  {t("health.medicine.reorderAt", { count: m.reorderLevel })}
                  {m.expiresOn ? ` · ${t("health.medicine.expires", { date: formatPlainDate(m.expiresOn, locale) })}` : ""}
                </p>
              </div>
              {canManage ? (
                <div className="flex gap-1">
                  <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => setEditing(m)}>
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => setRemoving(m)}>
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      <MedicineDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        medicine={editing === "new" ? null : editing}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("health.medicine.saved"));
          list.reload();
        }}
      />
      <ConfirmDialog
        open={removing !== null}
        title={t("health.medicine.removeTitle")}
        body={removing ? t("health.medicine.removeBody", { name: removing.name }) : ""}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await healthApi.removeMedicine(removing.id);
          setRemoving(null);
          list.reload();
        }}
        onClose={() => setRemoving(null)}
      />
    </>
  );
}

function MedicineDialog({ medicine, open, onClose, onSaved }: { medicine: Medicine | null; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<MedicineValues>(
    medicine
      ? { name: medicine.name, unit: medicine.unit, quantity: String(medicine.quantity), reorderLevel: String(medicine.reorderLevel), expiresOn: medicine.expiresOn ?? "" }
      : { name: "", unit: "tablet", quantity: "0", reorderLevel: "5", expiresOn: "" },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(medicineProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: MedicineRequest = {
      name: v.name.trim(),
      unit: v.unit.trim(),
      quantity: Number(v.quantity),
      reorderLevel: v.reorderLevel ? Number(v.reorderLevel) : null,
      expiresOn: v.expiresOn || null,
    };
    const ok = await form.submit(t, async () => {
      if (medicine) await healthApi.updateMedicine(medicine.id, body);
      else await healthApi.addMedicine(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={medicine ? t("health.medicine.edit") : t("health.medicine.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="medicine-form">
        <FormAlert message={form.formError} />
        <TextField label={t("health.medicine.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required />
        <div className="grid grid-cols-3 gap-3">
          <TextField label={t("health.medicine.unit")} name="unit" value={form.values.unit} onChange={(e) => form.set("unit", e.target.value)} error={form.errors.unit} maxLength={20} required />
          <TextField label={t("health.medicine.quantity")} name="quantity" type="number" inputMode="numeric" min={0} value={form.values.quantity} onChange={(e) => form.set("quantity", e.target.value)} error={form.errors.quantity} required />
          <TextField label={t("health.medicine.reorderLevel")} name="reorderLevel" type="number" inputMode="numeric" min={0} value={form.values.reorderLevel} onChange={(e) => form.set("reorderLevel", e.target.value)} error={form.errors.reorderLevel} />
        </div>
        <TextField label={t("health.medicine.expiresOn")} name="expiresOn" type="date" value={form.values.expiresOn} onChange={(e) => form.set("expiresOn", e.target.value)} error={form.errors.expiresOn} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="medicine-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
