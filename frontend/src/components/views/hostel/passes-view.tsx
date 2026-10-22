"use client";

import { DoorOpen, Plus } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { todayInIndia } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { plural, useI18n } from "@/lib/i18n";
import { PASS_KINDS, type BoarderView, type HostelPassView, type PassKind } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { HostelNav, HostelPassPill, ListState, passDates, passKindLabel, useChosenHostel, useLocale, when } from "./hostel-shared";

/* /app/hostel/passes: outing and home-leave passes; the warden raises, decides and marks out and in. */

export type PassValues = { kind: PassKind; fromDate: string; toDate: string; reason: string };

export const EMPTY_PASS = (): PassValues => ({ kind: "OUTING", fromDate: todayInIndia(), toDate: "", reason: "" });

/** Days between two "YYYY-MM-DD" dates. */
function daysBetween(from: string, to: string): number {
  return Math.round((new Date(`${to}T00:00:00Z`).getTime() - new Date(`${from}T00:00:00Z`).getTime()) / 86_400_000);
}

/**
 * Client-side checks mirroring HostelRules.passDatesProblem on the API: not in the past, at most 60 days ahead; an
 * outing is one day; home leave ends on or after it starts, within 30 days. Pure and exported for tests.
 */
export function passProblems(v: PassValues, today = todayInIndia()): Problems {
  const problems: Problems = {};
  if (!v.fromDate || Number.isNaN(daysBetween(today, v.fromDate))) problems.fromDate = "hostel.v.fromDate";
  else {
    const ahead = daysBetween(today, v.fromDate);
    if (ahead < 0 || ahead > 60) problems.fromDate = "hostel.v.passDate";
  }
  if (v.kind === "HOME_LEAVE") {
    if (!v.toDate) problems.toDate = "hostel.v.toDate";
    else if (v.fromDate && (daysBetween(v.fromDate, v.toDate) < 0 || daysBetween(v.fromDate, v.toDate) > 30)) problems.toDate = "hostel.v.leaveDates";
  }
  if (!v.reason.trim()) problems.reason = "hostel.v.reason";
  return problems;
}

/** The fields shared by the warden's form and the family app's. */
/** What PassFields needs of a form: the warden's form carries a studentId too. */
export type PassForm = {
  values: PassValues;
  errors: Partial<Record<string, string>>;
  set: {
    (field: "kind", value: PassKind): void;
    (field: "fromDate" | "toDate" | "reason", value: string): void;
  };
};

export function PassFields({ form }: { form: PassForm }) {
  const { t } = useI18n();
  return (
    <>
      <SelectField
        label={t("hostel.field.passKind")}
        name="kind"
        value={form.values.kind}
        onChange={(e) => form.set("kind", e.target.value as PassKind)}
        options={PASS_KINDS.map((kind) => ({ value: kind, label: passKindLabel(t, kind) }))}
      />
      <div className="grid grid-cols-2 gap-3">
        <TextField
          label={form.values.kind === "OUTING" ? t("hostel.field.outingDate") : t("hostel.field.fromDate")}
          name="fromDate"
          type="date"
          value={form.values.fromDate}
          onChange={(e) => form.set("fromDate", e.target.value)}
          error={form.errors.fromDate}
          required
        />
        {form.values.kind === "HOME_LEAVE" ? (
          <TextField label={t("hostel.field.toDate")} name="toDate" type="date" value={form.values.toDate} onChange={(e) => form.set("toDate", e.target.value)} error={form.errors.toDate} required />
        ) : null}
      </div>
      <p className="text-[12.5px] text-ink-3">{form.values.kind === "OUTING" ? t("hostel.pass.outingHint") : t("hostel.pass.leaveHint")}</p>
      <TextAreaField label={t("hostel.field.reason")} name="reason" value={form.values.reason} onChange={(e) => form.set("reason", e.target.value)} error={form.errors.reason} rows={2} maxLength={500} required />
    </>
  );
}

export function PassesView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const { hostels, list, hostel, choose } = useChosenHostel();
  const [raising, setRaising] = useState(false);
  const passes = useApiData(hostel ? `hostel:passes:${hostel.id}` : null, () => hostelApi.passes({ hostelId: hostel?.id }));
  return (
    <>
      <PageHead
        eyebrow={t("nav.hostel")}
        title={t("hostel.passes.title")}
        actions={
          hostel?.canWarden ? (
            <button type="button" className="btn btn-primary" onClick={() => setRaising(true)} data-testid="pass-raise">
              <Plus size={18} aria-hidden="true" />
              {t("hostel.passes.raise")}
            </button>
          ) : null
        }
      />
      <HostelNav />
      {hostels.error && !hostels.data ? (
        <ErrorState error={hostels.error} onRetry={hostels.reload} />
      ) : !hostels.data ? (
        <LoadingRows rows={3} />
      ) : !hostel ? (
        <p className="empty">{t("hostel.hostels.empty")}</p>
      ) : (
        <>
          {list.length > 1 ? (
            <SelectField label={t("hostel.field.hostel")} name="hostel" value={hostel.id} onChange={(e) => choose(e.target.value)} options={list.map((h) => ({ value: h.id, label: h.name }))} className="max-w-xs" />
          ) : null}
          <ListState data={passes} empty={t("hostel.passes.empty")}>
            {(rows) => (
              <ul className="rowcards" data-testid="pass-list">
                {rows.map((pass) => (
                  <PassRow key={pass.id} pass={pass} onChange={passes.reload} />
                ))}
              </ul>
            )}
          </ListState>
          <RaiseDialog
            open={raising}
            hostelId={hostel.id}
            onClose={() => setRaising(false)}
            onSaved={() => {
              setRaising(false);
              toast(t("hostel.passes.raised"));
              passes.reload();
            }}
          />
        </>
      )}
    </>
  );
}

export function PassRow({ pass, onChange }: { pass: HostelPassView; onChange: () => void }) {
  const { t } = useI18n();
  const locale = useLocale();
  const { toast } = useToast();
  const [deciding, setDeciding] = useState<"approve" | "reject" | null>(null);
  const [gate, setGate] = useState<"out" | "in" | null>(null);
  return (
    <li className="rowcard items-start" data-testid="hostel-pass" data-status={pass.status}>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <b>{pass.studentName}</b>
          <span className="text-[12.5px] text-ink-3">{pass.className}</span>
          <HostelPassPill status={pass.status} late={pass.late} />
        </div>
        <p className="text-[13px] text-ink-2">
          {passKindLabel(t, pass.kind)} · {passDates(pass.fromDate, pass.toDate, locale, t)} · {t("hostel.pass.returnBy", { time: when(pass.returnBy, locale) })}
        </p>
        <p className="text-[13px] text-ink-2">{pass.reason}</p>
        <p className="text-[12.5px] text-ink-3">
          {t("hostel.pass.requestedBy", { who: pass.requestedByName ?? "" })}
          {pass.decidedByName ? ` · ${t("hostel.pass.decidedBy", { who: pass.decidedByName, time: when(pass.decidedAt, locale) })}` : ""}
          {pass.outAt ? ` · ${t("hostel.pass.outAt", { time: when(pass.outAt, locale) })}` : ""}
          {pass.inAt ? ` · ${t("hostel.pass.inAt", { time: when(pass.inAt, locale) })}` : ""}
        </p>
        {pass.decisionNote ? <p className="text-[12.5px] text-ink-3">{pass.decisionNote}</p> : null}
      </div>
      <div className="flex flex-wrap gap-1">
        {pass.canDecide ? (
          <>
            <button type="button" className="btn btn-sm btn-primary" onClick={() => setDeciding("approve")} data-testid="pass-approve">
              {t("hostel.pass.approve")}
            </button>
            <button type="button" className="btn btn-sm" onClick={() => setDeciding("reject")} data-testid="pass-reject">
              {t("hostel.pass.reject")}
            </button>
          </>
        ) : null}
        {pass.canMarkOut ? (
          <button type="button" className="btn btn-sm" onClick={() => setGate("out")} data-testid="pass-out">
            <DoorOpen size={16} aria-hidden="true" />
            {t("hostel.pass.markOut")}
          </button>
        ) : null}
        {pass.canMarkIn ? (
          <button type="button" className="btn btn-sm btn-primary" onClick={() => setGate("in")} data-testid="pass-in">
            {t("hostel.pass.markIn")}
          </button>
        ) : null}
      </div>
      <DecisionDialog
        key={deciding ?? "closed"}
        open={deciding !== null}
        kind={deciding ?? "approve"}
        pass={pass}
        onClose={() => setDeciding(null)}
        onSaved={() => {
          setDeciding(null);
          toast(deciding === "approve" ? t("hostel.pass.approved") : t("hostel.pass.rejected"));
          onChange();
        }}
      />
      <ConfirmDialog
        open={gate !== null}
        title={gate === "in" ? t("hostel.pass.markIn") : t("hostel.pass.markOut")}
        body={gate === "in" ? t("hostel.pass.markInBody", { name: pass.studentName ?? "" }) : t("hostel.pass.markOutBody", { name: pass.studentName ?? "" })}
        confirmLabel={gate === "in" ? t("hostel.pass.markIn") : t("hostel.pass.markOut")}
        danger={false}
        onConfirm={async () => {
          const result = gate === "in" ? await hostelApi.markIn(pass.id) : await hostelApi.markOut(pass.id);
          setGate(null);
          toast(result.late ? t("hostel.pass.lateReturn") : gate === "in" ? t("hostel.pass.markedIn") : t("hostel.pass.markedOut"));
          onChange();
        }}
        onClose={() => setGate(null)}
      />
    </li>
  );
}

function DecisionDialog({ open, kind, pass, onClose, onSaved }: { open: boolean; kind: "approve" | "reject"; pass: HostelPassView; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ note: string }>({ note: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (kind === "reject" && !form.check(form.values.note.trim() ? {} : { note: "hostel.v.note" }, t, formEl)) return;
    const body = { note: form.values.note.trim() || null };
    const ok = await form.submit(t, async () => {
      if (kind === "approve") await hostelApi.approvePass(pass.id, body);
      else await hostelApi.rejectPass(pass.id, body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={kind === "approve" ? t("hostel.pass.approve") : t("hostel.pass.reject")}
      description={t("hostel.pass.decisionIntro", { name: pass.studentName ?? "" })}
      closeLabel={t("common.close")}
    >
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="decision-form">
        <FormAlert message={form.formError} />
        <TextAreaField label={t("hostel.field.note")} name="note" value={form.values.note} onChange={(e) => form.set("note", e.target.value)} error={form.errors.note} rows={2} maxLength={500} required={kind === "reject"} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className={`btn ${kind === "approve" ? "btn-primary" : "btn-danger"}`} disabled={form.submitting} data-testid="decision-save">
            {kind === "approve" ? t("hostel.pass.approve") : t("hostel.pass.reject")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function RaiseDialog({ open, hostelId, onClose, onSaved }: { open: boolean; hostelId: string; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const boarders = useApiData(open ? `hostel:boarders:${hostelId}` : null, () => hostelApi.boarders(hostelId));
  const form = useForm<PassValues & { studentId: string }>({ ...EMPTY_PASS(), studentId: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems = passProblems(form.values);
    if (!form.values.studentId) problems.studentId = "hostel.v.boarder";
    if (!form.check(problems, t, formEl)) return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await hostelApi.raisePass({ studentId: v.studentId, kind: v.kind, fromDate: v.fromDate, toDate: v.kind === "HOME_LEAVE" ? v.toDate : null, reason: v.reason.trim() });
    });
    if (ok) {
      form.reset({ ...EMPTY_PASS(), studentId: "" });
      onSaved();
    }
  };
  const options = (boarders.data ?? []).map((b: BoarderView) => ({ value: b.studentId, label: `${b.studentName ?? ""} · ${b.roomNumber ?? ""}/${b.bedLabel ?? ""}` }));
  return (
    <Dialog open={open} onClose={onClose} title={t("hostel.passes.raise")} description={t("hostel.passes.raiseHint")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="raise-form">
        <FormAlert message={form.formError} />
        <SelectField
          label={t("hostel.field.boarder")}
          name="studentId"
          value={form.values.studentId}
          onChange={(e) => form.set("studentId", e.target.value)}
          error={form.errors.studentId}
          options={[{ value: "", label: boarders.data ? plural(t, "hostel.boarders.count", boarders.data.length) : t("common.loading") }, ...options]}
          required
        />
        <PassFields form={form} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="raise-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
