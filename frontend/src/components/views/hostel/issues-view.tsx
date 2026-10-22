"use client";

import { Plus, TriangleAlert, Wrench } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { FormAlert, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { plural, useI18n } from "@/lib/i18n";
import { COMPLAINT_CATEGORIES, COMPLAINT_STATUSES, type ComplaintCategory, type ComplaintStatus, type ComplaintView, type HostelAlertView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { ComplaintPill, complaintCategoryLabel, complaintStatusLabel, HostelNav, ListState, useChosenHostel, useLocale, when } from "./hostel-shared";

/* /app/hostel/issues: open alerts (absentees, late returns) and the rooms' complaints and maintenance requests. */

type ComplaintValues = { roomId: string; category: ComplaintCategory; description: string };

export function complaintProblems(v: ComplaintValues): Problems {
  const problems: Problems = {};
  if (!v.roomId) problems.roomId = "hostel.v.room";
  if (!v.description.trim()) problems.description = "hostel.v.description";
  return problems;
}

export function IssuesView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const { hostels, list, hostel, choose } = useChosenHostel();
  const alerts = useApiData("hostel:alerts", hostelApi.alerts);
  const complaints = useApiData("hostel:complaints", () => hostelApi.complaints());
  const [raising, setRaising] = useState(false);
  const [resolving, setResolving] = useState<HostelAlertView | null>(null);
  const [moving, setMoving] = useState<ComplaintView | null>(null);
  const canWarden = Boolean(hostels.data?.some((h) => h.canWarden));
  return (
    <>
      <PageHead
        eyebrow={t("nav.hostel")}
        title={t("hostel.issues.title")}
        actions={
          canWarden ? (
            <button type="button" className="btn btn-primary" onClick={() => setRaising(true)} data-testid="complaint-raise">
              <Plus size={18} aria-hidden="true" />
              {t("hostel.complaints.raise")}
            </button>
          ) : null
        }
      />
      <HostelNav />
      <section className="card flex flex-col gap-3" aria-labelledby="hostel-alerts" data-testid="hostel-alerts">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2 id="hostel-alerts" className="flex items-center gap-2">
            <TriangleAlert size={18} className="text-ink-3" aria-hidden="true" />
            {t("hostel.alerts.title")}
          </h2>
          {alerts.data ? <Pill tone={alerts.data.length > 0 ? "bad" : "neutral"}>{plural(t, "hostel.alerts.count", alerts.data.length)}</Pill> : null}
        </div>
        <ListState data={alerts} empty={t("hostel.alerts.empty")}>
          {(rows) => (
            <ul className="rowcards">
              {rows.map((alert) => (
                <AlertRow key={alert.id} alert={alert} onResolve={() => setResolving(alert)} />
              ))}
            </ul>
          )}
        </ListState>
      </section>
      <section className="card flex flex-col gap-3" aria-labelledby="hostel-complaints" data-testid="hostel-complaints">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2 id="hostel-complaints" className="flex items-center gap-2">
            <Wrench size={18} className="text-ink-3" aria-hidden="true" />
            {t("hostel.complaints.title")}
          </h2>
        </div>
        <ListState data={complaints} empty={t("hostel.complaints.empty")}>
          {(rows) => (
            <ul className="rowcards">
              {rows.map((c) => (
                <ComplaintRow key={c.id} complaint={c} canWarden={canWarden} onMove={() => setMoving(c)} />
              ))}
            </ul>
          )}
        </ListState>
      </section>
      {hostel ? (
        <ComplaintDialog
          open={raising}
          hostelId={hostel.id}
          list={list}
          onChooseHostel={choose}
          onClose={() => setRaising(false)}
          onSaved={() => {
            setRaising(false);
            toast(t("hostel.complaints.raised"));
            complaints.reload();
          }}
        />
      ) : null}
      <NoteDialog
        key={resolving?.id ?? "resolve-closed"}
        open={resolving !== null}
        title={t("hostel.alerts.resolve")}
        description={resolving?.message}
        required={false}
        onClose={() => setResolving(null)}
        onSave={async (note) => {
          if (!resolving) return;
          await hostelApi.resolveAlert(resolving.id, { note });
          setResolving(null);
          toast(t("hostel.alerts.resolved"));
          alerts.reload();
        }}
      />
      <StatusDialog
        key={moving?.id ?? "status-closed"}
        open={moving !== null}
        complaint={moving}
        onClose={() => setMoving(null)}
        onSaved={() => {
          setMoving(null);
          toast(t("hostel.complaints.updated"));
          complaints.reload();
        }}
      />
    </>
  );
}

function AlertRow({ alert, onResolve }: { alert: HostelAlertView; onResolve: () => void }) {
  const { t } = useI18n();
  const locale = useLocale();
  return (
    <li className="rowcard items-start" data-testid="hostel-alert" data-kind={alert.kind}>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <b>{alert.studentName}</b>
          <Pill tone="bad" dot>
            {alert.kind === "LATE_RETURN" ? t("hostel.alerts.LATE_RETURN") : t("hostel.alerts.ABSENT_AT_ROLL_CALL")}
          </Pill>
        </div>
        <p className="text-[13px] text-ink-2">{alert.message}</p>
        <p className="text-[12.5px] text-ink-3">
          {alert.hostelName} · {formatPlainDate(alert.day, locale)}
        </p>
      </div>
      <button type="button" className="btn btn-sm" onClick={onResolve} data-testid="alert-resolve">
        {t("hostel.alerts.resolve")}
      </button>
    </li>
  );
}

function ComplaintRow({ complaint, canWarden, onMove }: { complaint: ComplaintView; canWarden: boolean; onMove: () => void }) {
  const { t } = useI18n();
  const locale = useLocale();
  return (
    <li className="rowcard items-start" data-testid="hostel-complaint" data-status={complaint.status}>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <b>
            {complaint.hostelName} · {complaint.blockName ? `${complaint.blockName} · ` : ""}
            {t("hostel.occupancy.room", { number: complaint.roomNumber ?? "" })}
          </b>
          <Pill tone="neutral">{complaintCategoryLabel(t, complaint.category)}</Pill>
          <ComplaintPill status={complaint.status} />
        </div>
        <p className="text-[13px] text-ink-2">{complaint.description}</p>
        <p className="text-[12.5px] text-ink-3">
          {t("hostel.complaints.raisedBy", { who: complaint.raisedByName ?? "", time: when(complaint.createdAt, locale) })}
          {complaint.resolvedAt ? ` · ${t("hostel.complaints.resolvedAt", { time: when(complaint.resolvedAt, locale) })}` : ""}
        </p>
        {complaint.resolutionNote ? <p className="text-[12.5px] text-ink-3">{complaint.resolutionNote}</p> : null}
      </div>
      {canWarden && complaint.status !== "RESOLVED" ? (
        <button type="button" className="btn btn-sm" onClick={onMove} data-testid="complaint-move">
          {t("hostel.complaints.update")}
        </button>
      ) : null}
    </li>
  );
}

function ComplaintDialog({
  open,
  hostelId,
  list,
  onChooseHostel,
  onClose,
  onSaved,
}: {
  open: boolean;
  hostelId: string;
  list: readonly { id: string; name: string }[];
  onChooseHostel: (id: string) => void;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const detail = useApiData(open ? `hostel:detail:${hostelId}` : null, () => hostelApi.hostel(hostelId));
  const form = useForm<ComplaintValues>({ roomId: "", category: "PLUMBING", description: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(complaintProblems(form.values), t, formEl)) return;
    const ok = await form.submit(t, async () => {
      await hostelApi.raiseComplaint({ roomId: form.values.roomId, category: form.values.category, description: form.values.description.trim() });
    });
    if (ok) {
      form.reset({ roomId: "", category: "PLUMBING", description: "" });
      onSaved();
    }
  };
  const rooms = (detail.data?.rooms ?? []).map((r) => ({ value: r.id, label: `${r.blockName ? `${r.blockName} · ` : ""}${r.number}` }));
  return (
    <Dialog open={open} onClose={onClose} title={t("hostel.complaints.raise")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="complaint-form">
        <FormAlert message={form.formError} />
        {list.length > 1 ? (
          <SelectField label={t("hostel.field.hostel")} name="hostel" value={hostelId} onChange={(e) => onChooseHostel(e.target.value)} options={list.map((h) => ({ value: h.id, label: h.name }))} />
        ) : null}
        <SelectField
          label={t("hostel.field.room")}
          name="roomId"
          value={form.values.roomId}
          onChange={(e) => form.set("roomId", e.target.value)}
          error={form.errors.roomId}
          options={[{ value: "", label: t("hostel.complaints.pickRoom") }, ...rooms]}
          required
        />
        <SelectField
          label={t("hostel.field.category")}
          name="category"
          value={form.values.category}
          onChange={(e) => form.set("category", e.target.value as ComplaintCategory)}
          options={COMPLAINT_CATEGORIES.map((c) => ({ value: c, label: complaintCategoryLabel(t, c) }))}
        />
        <TextAreaField label={t("hostel.field.description")} name="description" value={form.values.description} onChange={(e) => form.set("description", e.target.value)} error={form.errors.description} rows={3} maxLength={1000} required />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="complaint-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function NoteDialog({ open, title, description, required, onClose, onSave }: { open: boolean; title: string; description?: string; required: boolean; onClose: () => void; onSave: (note: string | null) => Promise<void> }) {
  const { t } = useI18n();
  const form = useForm<{ note: string }>({ note: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (required && !form.check(form.values.note.trim() ? {} : { note: "hostel.v.note" }, t, formEl)) return;
    await form.submit(t, async () => {
      await onSave(form.values.note.trim() || null);
    });
  };
  return (
    <Dialog open={open} onClose={onClose} title={title} description={description} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="note-form">
        <FormAlert message={form.formError} />
        <TextAreaField label={t("hostel.field.note")} name="note" value={form.values.note} onChange={(e) => form.set("note", e.target.value)} error={form.errors.note} rows={2} maxLength={300} required={required} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="note-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function StatusDialog({ open, complaint, onClose, onSaved }: { open: boolean; complaint: ComplaintView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ status: ComplaintStatus; note: string }>({ status: complaint?.status === "OPEN" ? "IN_PROGRESS" : "RESOLVED", note: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!complaint) return;
    if (!form.check(form.values.status === "RESOLVED" && !form.values.note.trim() ? { note: "hostel.v.resolution" } : {}, t, formEl)) return;
    const ok = await form.submit(t, async () => {
      await hostelApi.moveComplaint(complaint.id, { status: form.values.status, note: form.values.note.trim() || null });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("hostel.complaints.update")} description={complaint?.description} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="status-form">
        <FormAlert message={form.formError} />
        <SelectField
          label={t("hostel.field.status")}
          name="status"
          value={form.values.status}
          onChange={(e) => form.set("status", e.target.value as ComplaintStatus)}
          options={COMPLAINT_STATUSES.filter((s) => s !== complaint?.status).map((s) => ({ value: s, label: complaintStatusLabel(t, s) }))}
        />
        <TextAreaField label={t("hostel.field.note")} name="note" value={form.values.note} onChange={(e) => form.set("note", e.target.value)} error={form.errors.note} rows={2} maxLength={500} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="status-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
