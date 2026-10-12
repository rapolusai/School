"use client";

import { Package, Pencil, Trash2, Truck } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, useI18n } from "@/lib/i18n";
import type { CourierDirection, CourierRequest, CourierView as CourierItem } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { monthStart, rangeProblem } from "@/components/views/reports/report-frame";
import { courierDirectionLabel, FrontOfficeNav, useCanManageOffice } from "./frontoffice-shared";

type CourierValues = { direction: CourierDirection; item: string; sender: string; receiver: string; itemDate: string; carrier: string; trackingNo: string; receivedBy: string; note: string };

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Client-side checks mirroring CourierForm on the API. Pure and exported for tests. */
export function courierProblems(values: CourierValues): Problems {
  const problems: Problems = {};
  if (!values.item.trim()) problems.item = "frontoffice.v.item";
  if (!values.sender.trim()) problems.sender = "frontoffice.v.sender";
  if (!values.receiver.trim()) problems.receiver = "frontoffice.v.receiver";
  if (!DATE.test(values.itemDate)) problems.itemDate = "frontoffice.v.date";
  return problems;
}

/** /app/frontoffice/courier: the postal and courier register, inward and outward. */
export function CourierView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageOffice();
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => monthStart(today));
  const [to, setTo] = useState(today);
  const problem = rangeProblem(from, to);
  const list = useApiData(problem ? null : `frontoffice:courier:${from}:${to}`, () => frontOfficeApi.courier({ from, to }));
  const [editing, setEditing] = useState<CourierItem | "new" | null>(null);
  const [removing, setRemoving] = useState<CourierItem | null>(null);
  const rows = list.data ?? [];
  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.courier.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="courier-add">
              <Package size={18} aria-hidden="true" />
              {t("frontoffice.courier.add")}
            </button>
          ) : undefined
        }
      />
      <FrontOfficeNav />
      <div className="toolbar no-print">
        <label className="field w-[calc(50%-4px)] sm:w-auto">
          <span className="field-label">{t("reports.filter.from")}</span>
          <input type="date" className="input" name="from" value={from} onChange={(e) => setFrom(e.target.value)} aria-invalid={problem ? true : undefined} />
        </label>
        <label className="field w-[calc(50%-4px)] sm:w-auto">
          <span className="field-label">{t("reports.filter.to")}</span>
          <input type="date" className="input" name="to" value={to} onChange={(e) => setTo(e.target.value)} aria-invalid={problem ? true : undefined} />
        </label>
        {problem ? (
          <p className="field-error" role="alert">
            {t(problem)}
          </p>
        ) : null}
      </div>
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={3} />
      ) : rows.length === 0 ? (
        <p className="empty">{t("frontoffice.courier.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="courier-list">
          {rows.map((row) => (
            <li key={row.id} className="rowcard items-start" data-testid="courier-row" data-direction={row.direction}>
              <span className="badge-ic" aria-hidden="true">
                {row.direction === "INWARD" ? <Package size={18} /> : <Truck size={18} />}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold text-ink">{row.item}</span>
                  <Pill tone={row.direction === "INWARD" ? "info" : "accent"}>{courierDirectionLabel(t, row.direction)}</Pill>
                </div>
                <p className="text-[13px] text-ink-2">{t("frontoffice.courier.fromTo", { sender: row.sender, receiver: row.receiver })}</p>
                <p className="text-[12.5px] text-ink-3">
                  {formatPlainDate(row.itemDate, locale)}
                  {row.carrier ? ` · ${row.carrier}` : ""}
                  {row.trackingNo ? ` · ${row.trackingNo}` : ""}
                  {row.receivedBy ? ` · ${t("frontoffice.courier.receivedBy", { name: row.receivedBy })}` : ""}
                </p>
                {row.note ? <p className="text-[12.5px] text-ink-3">{row.note}</p> : null}
              </div>
              {canManage ? (
                <div className="flex gap-1">
                  <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => setEditing(row)}>
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => setRemoving(row)}>
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      <CourierDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        entry={editing === "new" ? null : editing}
        today={today}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("frontoffice.courier.saved"));
          list.reload();
        }}
      />
      <ConfirmDialog
        open={removing !== null}
        title={t("frontoffice.courier.removeTitle")}
        body={removing ? t("frontoffice.courier.removeBody", { item: removing.item }) : ""}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await frontOfficeApi.removeCourier(removing.id);
          setRemoving(null);
          list.reload();
        }}
        onClose={() => setRemoving(null)}
      />
    </>
  );
}

function CourierDialog({ entry, today, open, onClose, onSaved }: { entry: CourierItem | null; today: string; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<CourierValues>(
    entry
      ? { direction: entry.direction, item: entry.item, sender: entry.sender, receiver: entry.receiver, itemDate: entry.itemDate, carrier: entry.carrier ?? "", trackingNo: entry.trackingNo ?? "", receivedBy: entry.receivedBy ?? "", note: entry.note ?? "" }
      : { direction: "INWARD", item: "", sender: "", receiver: "", itemDate: today, carrier: "", trackingNo: "", receivedBy: "", note: "" },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(courierProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: CourierRequest = {
      direction: v.direction,
      item: v.item.trim(),
      sender: v.sender.trim(),
      receiver: v.receiver.trim(),
      itemDate: v.itemDate,
      carrier: v.carrier.trim() || null,
      trackingNo: v.trackingNo.trim() || null,
      receivedBy: v.receivedBy.trim() || null,
      note: v.note.trim() || null,
    };
    const ok = await form.submit(t, async () => {
      if (entry) await frontOfficeApi.updateCourier(entry.id, body);
      else await frontOfficeApi.logCourier(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={entry ? t("frontoffice.courier.edit") : t("frontoffice.courier.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="courier-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <SelectField
            label={t("frontoffice.courier.direction")}
            name="direction"
            value={form.values.direction}
            onChange={(e) => form.set("direction", e.target.value as CourierDirection)}
            options={[
              { value: "INWARD", label: courierDirectionLabel(t, "INWARD") },
              { value: "OUTWARD", label: courierDirectionLabel(t, "OUTWARD") },
            ]}
          />
          <TextField label={t("frontoffice.courier.date")} name="itemDate" type="date" value={form.values.itemDate} onChange={(e) => form.set("itemDate", e.target.value)} error={form.errors.itemDate} required />
        </div>
        <TextField label={t("frontoffice.courier.item")} name="item" value={form.values.item} onChange={(e) => form.set("item", e.target.value)} error={form.errors.item} maxLength={300} required />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField label={t("frontoffice.courier.sender")} name="sender" value={form.values.sender} onChange={(e) => form.set("sender", e.target.value)} error={form.errors.sender} maxLength={200} required />
          <TextField label={t("frontoffice.courier.receiver")} name="receiver" value={form.values.receiver} onChange={(e) => form.set("receiver", e.target.value)} error={form.errors.receiver} maxLength={200} required />
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <TextField label={t("frontoffice.courier.carrier")} name="carrier" value={form.values.carrier} onChange={(e) => form.set("carrier", e.target.value)} error={form.errors.carrier} maxLength={100} />
          <TextField label={t("frontoffice.courier.tracking")} name="trackingNo" value={form.values.trackingNo} onChange={(e) => form.set("trackingNo", e.target.value)} error={form.errors.trackingNo} maxLength={60} />
          <TextField label={t("frontoffice.courier.receivedByField")} name="receivedBy" value={form.values.receivedBy} onChange={(e) => form.set("receivedBy", e.target.value)} error={form.errors.receivedBy} hint={t("frontoffice.courier.receivedByHint")} maxLength={200} />
        </div>
        <TextAreaField label={t("frontoffice.field.note")} name="note" value={form.values.note} onChange={(e) => form.set("note", e.target.value)} error={form.errors.note} maxLength={500} rows={2} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="courier-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
