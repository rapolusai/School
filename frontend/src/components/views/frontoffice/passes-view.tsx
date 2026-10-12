"use client";

import { Check, KeyRound, Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, initials, todayInIndia } from "@/lib/format";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, plural, useI18n, type Translate } from "@/lib/i18n";
import type { CollectorKind, GatePassView, OfficePassRequest, StudentRef } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { FrontOfficeNav, localNow, localToInstant, PassStatusPill, phoneProblem, useCanManageOffice, when } from "./frontoffice-shared";

export type PassValues = { pickupAt: string; reason: string; collectorKind: CollectorKind; collectorName: string; collectorPhone: string };

/** Client-side checks mirroring PickupRequest on the API (today, not in the past, a named collector). Pure. */
export function passProblems(values: PassValues, now: Date = new Date()): Problems {
  const problems: Problems = {};
  const at = localToInstant(values.pickupAt);
  if (!at) problems.pickupAt = "frontoffice.v.pickupAt";
  else if (new Date(at).getTime() < now.getTime() - 15 * 60_000) problems.pickupAt = "frontoffice.v.pickupPast";
  else if (new Date(at).toDateString() !== now.toDateString()) problems.pickupAt = "frontoffice.v.pickupToday";
  if (!values.reason.trim()) problems.reason = "frontoffice.v.reason";
  if (values.collectorKind === "OTHER") {
    if (!values.collectorName.trim()) problems.collectorName = "frontoffice.v.collectorName";
    const phone = phoneProblem(values.collectorPhone, true);
    if (phone) problems.collectorPhone = "frontoffice.v.collectorPhone";
  }
  return problems;
}

/** "Mother or father (self)" or "Ravi Rao · 98765•••09" for the collector line. */
export function collectorLine(t: Translate, pass: Pick<GatePassView, "collectorKind" | "collectorName" | "collectorPhoneMasked">): string {
  if (pass.collectorKind === "SELF") return t("frontoffice.pass.collectorSelf");
  return [pass.collectorName, pass.collectorPhoneMasked].filter(Boolean).join(" · ");
}

/** /app/frontoffice/passes: today's early pickup passes for the office to approve, reject or raise. */
export function PassesView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const canManage = useCanManageOffice();
  const [today] = useState(() => todayInIndia());
  const [date, setDate] = useState(today);
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(date);
  const passes = useApiData(valid ? `frontoffice:passes:${date}` : null, () => frontOfficeApi.passes(date));
  const [raising, setRaising] = useState(false);
  const rows = passes.data ?? [];
  const waiting = rows.filter((p) => p.status === "REQUESTED");
  const others = rows.filter((p) => p.status !== "REQUESTED");
  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.passes.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setRaising(true)} data-testid="pass-raise">
              <KeyRound size={18} aria-hidden="true" />
              {t("frontoffice.passes.raise")}
            </button>
          ) : undefined
        }
      />
      <FrontOfficeNav />
      <p className="text-[13.5px] text-ink-2">{t("frontoffice.passes.intro")}</p>
      <div className="toolbar no-print">
        <label className="field w-full sm:w-auto">
          <span className="field-label">{t("frontoffice.col.date")}</span>
          <input type="date" className="input" name="date" value={date} onChange={(e) => setDate(e.target.value)} required />
        </label>
      </div>
      {passes.error && !passes.data ? (
        <ErrorState error={passes.error} onRetry={passes.reload} />
      ) : !passes.data ? (
        <LoadingRows rows={3} />
      ) : (
        <>
          <section className="card flex flex-col gap-3" aria-labelledby="passes-waiting" data-testid="passes-waiting">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2 id="passes-waiting">{t("frontoffice.passes.waiting")}</h2>
              <Pill tone={waiting.length > 0 ? "warn" : "neutral"}>{plural(t, "frontoffice.passes.count", waiting.length)}</Pill>
            </div>
            {waiting.length === 0 ? (
              <p className="text-[13.5px] text-ink-3">{t("frontoffice.passes.noneWaiting")}</p>
            ) : (
              <ul className="rowcards">
                {waiting.map((pass) => (
                  <PassRow key={pass.id} pass={pass} locale={locale} onChange={passes.reload} />
                ))}
              </ul>
            )}
          </section>
          <section className="card flex flex-col gap-3" aria-labelledby="passes-day" data-testid="passes-day">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2 id="passes-day">{t("frontoffice.passes.onDay", { date: valid ? formatPlainDate(date, locale) : date })}</h2>
            </div>
            {others.length === 0 ? (
              <p className="text-[13.5px] text-ink-3">{t("frontoffice.passes.noneDay")}</p>
            ) : (
              <ul className="rowcards">
                {others.map((pass) => (
                  <PassRow key={pass.id} pass={pass} locale={locale} onChange={passes.reload} />
                ))}
              </ul>
            )}
          </section>
        </>
      )}
      <RaisePassDialog
        open={raising}
        onClose={() => setRaising(false)}
        onSaved={() => {
          setRaising(false);
          setDate(today);
          passes.reload();
        }}
      />
    </>
  );
}

/** One pass with the office's decision buttons; the OTP is never shown to anyone at the school. */
function PassRow({ pass, locale, onChange }: { pass: GatePassView; locale: string; onChange: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManageOffice();
  const [note, setNote] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const decide = async (approve: boolean) => {
    if (!approve && !note.trim()) {
      setError(t("frontoffice.v.rejectNote"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (approve) {
        await frontOfficeApi.approve(pass.id, { note: note.trim() || null });
        toast(t("frontoffice.passes.approved", { name: pass.studentName }));
      } else {
        await frontOfficeApi.reject(pass.id, { note: note.trim() });
        toast(t("frontoffice.passes.rejected", { name: pass.studentName }));
      }
      onChange();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="rowcard items-start" data-testid="pass-row" data-status={pass.status}>
      <span className="avatar" aria-hidden="true">
        {initials(pass.studentName)}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold text-ink">{pass.studentName}</span>
          {pass.className ? <span className="text-[12.5px] text-ink-3">{pass.className}</span> : null}
          <PassStatusPill status={pass.status} />
        </div>
        <p className="text-[13px] text-ink-2">
          {t("frontoffice.pass.at", { time: when(pass.pickupAt, locale) })} · {pass.reason}
        </p>
        <p className="text-[12.5px] text-ink-3">
          {t("frontoffice.pass.collector", { who: collectorLine(t, pass) })}
          {" · "}
          {pass.source === "PARENT_APP"
            ? t("frontoffice.pass.fromApp", { name: pass.requestedByName ?? "" })
            : t("frontoffice.pass.fromOffice", { name: pass.requestedByName ?? "" })}
        </p>
        <p className="text-[12.5px] text-ink-3">
          {t("frontoffice.pass.otpTo", { name: pass.guardianName ?? "—", phone: pass.guardianPhoneMasked || "—" })}
          {pass.status === "APPROVED" ? ` · ${t("frontoffice.pass.validTill", { time: when(pass.otpExpiresAt, locale) })}` : ""}
          {pass.status === "RELEASED" ? ` · ${t("frontoffice.pass.releasedLine", { who: pass.collectedByName ?? "—", by: pass.releasedByName ?? "—", time: when(pass.releasedAt, locale) })}` : ""}
          {pass.decisionNote ? ` · ${pass.decisionNote}` : ""}
        </p>
        {error ? (
          <p className="alert alert-warn mt-2 text-[13px]" role="alert">
            {error}
          </p>
        ) : null}
        {canManage && pass.canDecide ? (
          <div className="mt-2 flex flex-col gap-2">
            {rejecting ? (
              <TextField label={t("frontoffice.pass.note")} name="note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />
            ) : null}
            <div className="flex flex-wrap gap-2">
              <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={() => decide(true)} data-testid="pass-approve">
                <Check size={16} aria-hidden="true" />
                {t("frontoffice.passes.approve")}
              </button>
              {rejecting ? (
                <button type="button" className="btn btn-sm btn-danger" disabled={busy} onClick={() => decide(false)} data-testid="pass-reject">
                  <X size={16} aria-hidden="true" />
                  {t("frontoffice.passes.reject")}
                </button>
              ) : (
                <button type="button" className="btn btn-sm" disabled={busy} onClick={() => setRejecting(true)}>
                  <X size={16} aria-hidden="true" />
                  {t("frontoffice.passes.reject")}
                </button>
              )}
            </div>
          </div>
        ) : null}
      </div>
    </li>
  );
}

/** Finds an active student by name or admission number (GET /api/frontoffice/students), 250 ms after typing stops. */
export function StudentPicker({ onPick }: { onPick: (student: StudentRef) => void }) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const q = query.trim();
  const ready = q.length >= 2;
  const results = useApiData(ready ? `frontoffice:students:${q}` : null, () => frontOfficeApi.students(q));
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const onChange = (value: string) => {
    setText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setQuery(value), 250);
  };
  const hits = results.data ?? [];
  return (
    <div className="flex flex-col gap-2" data-testid="student-picker">
      <label className="search max-w-none flex-none">
        <Search size={18} aria-hidden="true" />
        <span className="sr-only">{t("frontoffice.pass.findStudent")}</span>
        <input type="search" name="studentSearch" value={text} onChange={(e) => onChange(e.target.value)} placeholder={t("frontoffice.pass.findStudent")} maxLength={100} autoComplete="off" />
      </label>
      {!q ? null : !ready ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("frontoffice.pass.searchMore")}
        </p>
      ) : results.error && !results.data ? (
        <ErrorState error={results.error} onRetry={results.reload} />
      ) : results.loading && !results.data ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("common.loading")}
        </p>
      ) : hits.length === 0 ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("frontoffice.pass.noStudent", { query: q })}
        </p>
      ) : (
        <ul className="pick-list">
          {hits.map((hit) => (
            <li key={hit.id}>
              <button type="button" className="pick" onClick={() => onPick(hit)}>
                <span className="avatar" aria-hidden="true">
                  {initials(hit.fullName)}
                </span>
                <span className="min-w-0 flex-1 text-left">
                  <b className="block truncate font-semibold">{hit.fullName}</b>
                  <span className="block truncate text-[12.5px] text-ink-3">{[hit.admissionNo, hit.className].filter(Boolean).join(" · ")}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The fields of a pickup request, shared by the office's dialog and the parent's form. */
export function PassFields({ form, canChangeTime = true }: { form: ReturnType<typeof useForm<PassValues>>; canChangeTime?: boolean }) {
  const { t } = useI18n();
  return (
    <>
      <TextField
        label={t("frontoffice.pass.pickupAt")}
        name="pickupAt"
        type="datetime-local"
        value={form.values.pickupAt}
        onChange={(e) => form.set("pickupAt", e.target.value)}
        error={form.errors.pickupAt}
        hint={t("frontoffice.pass.pickupHint")}
        disabled={!canChangeTime}
        required
      />
      <TextAreaField label={t("frontoffice.field.reason")} name="reason" value={form.values.reason} onChange={(e) => form.set("reason", e.target.value)} error={form.errors.reason} maxLength={500} rows={2} required />
      <SelectField
        label={t("frontoffice.pass.collectorKind")}
        name="collectorKind"
        value={form.values.collectorKind}
        onChange={(e) => form.set("collectorKind", e.target.value as CollectorKind)}
        options={[
          { value: "SELF", label: t("frontoffice.pass.collectorSelf") },
          { value: "OTHER", label: t("frontoffice.pass.collectorOther") },
        ]}
        error={form.errors.collectorKind}
      />
      {form.values.collectorKind === "OTHER" ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField label={t("frontoffice.pass.collectorName")} name="collectorName" value={form.values.collectorName} onChange={(e) => form.set("collectorName", e.target.value)} error={form.errors.collectorName} maxLength={200} required />
          <TextField
            label={t("frontoffice.pass.collectorPhone")}
            name="collectorPhone"
            type="tel"
            inputMode="numeric"
            value={form.values.collectorPhone}
            onChange={(e) => form.set("collectorPhone", e.target.value)}
            error={form.errors.collectorPhone}
            maxLength={20}
            required
          />
        </div>
      ) : null}
    </>
  );
}

export const EMPTY_PASS = (): PassValues => ({ pickupAt: localNow(30), reason: "", collectorKind: "SELF", collectorName: "", collectorPhone: "" });

/** The office raises a pass on a parent's phone call: pick the student, then the same fields as the family app. */
function RaisePassDialog({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const [student, setStudent] = useState<StudentRef | null>(null);
  const form = useForm<PassValues>(EMPTY_PASS());
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const close = () => {
    setStudent(null);
    form.reset(EMPTY_PASS());
    onClose();
  };
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!student) return;
    if (!form.check(passProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: OfficePassRequest = {
      studentId: student.id,
      pickupAt: localToInstant(v.pickupAt) ?? "",
      reason: v.reason.trim(),
      collectorKind: v.collectorKind,
      collectorName: v.collectorKind === "OTHER" ? v.collectorName.trim() : null,
      collectorPhone: v.collectorKind === "OTHER" ? v.collectorPhone.trim() : null,
    };
    const ok = await form.submit(t, async () => {
      await frontOfficeApi.raisePass(body);
    });
    if (ok) {
      setStudent(null);
      form.reset(EMPTY_PASS());
      onSaved();
    }
  };
  return (
    <Dialog open={open} onClose={close} title={t("frontoffice.passes.raise")} description={t("frontoffice.passes.raiseIntro")} closeLabel={t("common.close")}>
      {!student ? (
        <StudentPicker onPick={setStudent} />
      ) : (
        <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="raise-pass-form">
          <FormAlert message={form.formError} />
          <div className="person">
            <span className="avatar" aria-hidden="true">
              {initials(student.fullName)}
            </span>
            <span className="min-w-0 flex-1">
              <b className="block truncate">{student.fullName}</b>
              <span className="sub">{[student.admissionNo, student.className].filter(Boolean).join(" · ")}</span>
            </span>
            <button type="button" className="btn btn-sm" onClick={() => setStudent(null)}>
              {t("frontoffice.pass.changeStudent")}
            </button>
          </div>
          <PassFields form={form} />
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="raise-pass-save">
              {t("frontoffice.passes.raiseSave")}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
