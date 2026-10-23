"use client";

import { HeartPulse, LogOut, Pencil, Plus, Search, Thermometer, Trash2 } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { healthApi } from "@/lib/health-api";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import type { CloseVisitRequest, HealthPersonRef, InfirmaryVisit, Medicine, PersonKind, VisitOutcome, VisitRequest } from "@/lib/types";
import { VISIT_OUTCOMES } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { AllergyBadges, HealthNav, localNow, localToInstant, numberOrNull, outcomeLabel, OutcomePill, useCanManageHealth, when } from "./health-shared";

export type VisitValues = {
  personKind: PersonKind;
  person: HealthPersonRef | null;
  timeIn: string;
  complaint: string;
  temperatureC: string;
  treatment: string;
  medicines: { medicineId: string; quantity: string }[];
};

/** Client-side checks mirroring VisitForm on the API. Pure and exported for tests. */
export function visitProblems(values: VisitValues): Problems {
  const problems: Problems = {};
  if (!values.person) problems.person = values.personKind === "STAFF" ? "health.v.staff" : "health.v.student";
  if (!values.complaint.trim()) problems.complaint = "health.v.complaint";
  if (values.timeIn && localToInstant(values.timeIn) === null) problems.timeIn = "health.v.time";
  const temp = numberOrNull(values.temperatureC);
  if (temp !== null && (Number.isNaN(temp) || temp < 30 || temp > 45)) problems.temperatureC = "health.v.temperature";
  if (values.medicines.some((m) => !m.medicineId || !/^\d+$/.test(m.quantity) || Number(m.quantity) < 1)) problems.medicines = "health.v.medicines";
  return problems;
}

/** /app/health: the infirmary log for a day, with visits recorded, treated and closed. */
export function InfirmaryView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageHealth();
  const [today] = useState(() => todayInIndia());
  const [date, setDate] = useState(today);
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(date);
  const day = useApiData(valid ? `health:infirmary:${date}` : null, () => healthApi.day(date));
  const [editing, setEditing] = useState<InfirmaryVisit | "new" | null>(null);
  const [closing, setClosing] = useState<InfirmaryVisit | null>(null);
  const visits = day.data?.visits ?? [];
  return (
    <>
      <PageHead
        eyebrow={t("nav.health")}
        title={t("health.infirmary.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="visit-add">
              <Plus size={18} aria-hidden="true" />
              {t("health.visit.add")}
            </button>
          ) : undefined
        }
      />
      <HealthNav />
      <div className="toolbar no-print">
        <label className="field w-full sm:w-auto">
          <span className="field-label">{t("health.infirmary.date")}</span>
          <input type="date" className="input" name="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} required />
        </label>
        {day.data ? (
          <p className="text-[13px] text-ink-3" data-testid="infirmary-counts">
            {plural(t, "health.visit.count", visits.length)} · {t("health.infirmary.open", { count: day.data.open })}
            {day.data.medicinesLow > 0 ? (
              <>
                {" · "}
                <Link href="/app/health/medicines" className="link">
                  {plural(t, "health.medicine.low", day.data.medicinesLow)}
                </Link>
              </>
            ) : null}
          </p>
        ) : null}
      </div>
      {day.error && !day.data ? (
        <ErrorState error={day.error} onRetry={day.reload} />
      ) : !day.data ? (
        <LoadingRows rows={3} />
      ) : visits.length === 0 ? (
        <p className="empty">{t("health.infirmary.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="visit-list">
          {visits.map((v) => (
            <li key={v.id} className="rowcard items-start" data-testid="visit-row" data-outcome={v.outcome ?? "OPEN"}>
              <span className="badge-ic" aria-hidden="true">
                <HeartPulse size={18} />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  {v.studentId ? (
                    <Link href={`/app/health/students/${v.studentId}`} className="link font-semibold">
                      {v.personName}
                    </Link>
                  ) : (
                    <span className="font-semibold text-ink">{v.personName}</span>
                  )}
                  {v.className ? <span className="text-[13px] text-ink-3">{v.className}</span> : null}
                  {v.personKind === "STAFF" ? <Pill tone="neutral">{t("health.person.STAFF")}</Pill> : null}
                  <OutcomePill outcome={v.outcome} />
                </div>
                <AllergyBadges badges={v.allergies} />
                <p className="text-[13px] text-ink-2">
                  {v.complaint}
                  {v.temperatureC !== null ? ` · ${t("health.visit.temperature", { value: v.temperatureC })}` : ""}
                </p>
                <p className="text-[12.5px] text-ink-3">
                  {t("health.visit.in", { time: when(v.timeIn, locale) })}
                  {v.timeOut ? ` · ${t("health.visit.out", { time: when(v.timeOut, locale) })}` : ""}
                  {v.medicines.length > 0 ? ` · ${v.medicines.map((m) => `${m.name} ×${m.quantity}`).join(", ")}` : ""}
                  {v.treatment ? ` · ${v.treatment}` : ""}
                  {v.outcome && v.outcome !== "BACK_TO_CLASS" && v.personKind === "STUDENT"
                    ? ` · ${v.parentInformed ? t("health.visit.parentInformed") : t("health.visit.parentNotInformed")}`
                    : ""}
                </p>
              </div>
              {canManage && !v.outcome ? (
                <div className="flex gap-1">
                  <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => setEditing(v)}>
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button type="button" className="btn" onClick={() => setClosing(v)} data-testid="visit-close">
                    <LogOut size={16} aria-hidden="true" />
                    {t("health.visit.close")}
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      <VisitDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        visit={editing === "new" ? null : editing}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("health.visit.saved"));
          day.reload();
        }}
      />
      <CloseVisitDialog
        key={closing?.id ?? "no-close"}
        visit={closing}
        onClose={() => setClosing(null)}
        onSaved={(outcome) => {
          setClosing(null);
          toast(outcome === "BACK_TO_CLASS" ? t("health.visit.closed") : t("health.visit.closedInformed"));
          day.reload();
        }}
      />
    </>
  );
}

/** Finds a student or staff member by name for the visit form. */
function PersonPicker({
  kind,
  value,
  onChange,
  error,
}: {
  kind: PersonKind;
  value: HealthPersonRef | null;
  onChange: (person: HealthPersonRef | null) => void;
  error?: string;
}) {
  const { t } = useI18n();
  const [q, setQ] = useState("");
  const [results, setResults] = useState<HealthPersonRef[]>([]);
  useEffect(() => {
    const text = q.trim();
    let cancelled = false;
    const timer = setTimeout(() => {
      if (text.length < 2) {
        setResults([]);
        return;
      }
      healthApi.people(text).then(
        (list) => {
          if (!cancelled) setResults(list.filter((p) => p.kind === kind));
        },
        () => {
          if (!cancelled) setResults([]);
        },
      );
    }, 200);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [q, kind]);
  if (value) {
    return (
      <div className="field">
        <span className="field-label">{kind === "STAFF" ? t("health.visit.staffMember") : t("health.visit.student")}</span>
        <div className="flex flex-wrap items-center gap-2" data-testid="visit-person">
          <span className="font-semibold">{value.name}</span>
          {value.label ? <span className="text-[13px] text-ink-3">{value.label}</span> : null}
          <button type="button" className="btn btn-sm" onClick={() => onChange(null)}>
            {t("health.change")}
          </button>
        </div>
      </div>
    );
  }
  return (
    <div className="field">
      <TextField
        label={kind === "STAFF" ? t("health.visit.staffMember") : t("health.visit.student")}
        name="person"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        placeholder={t("health.visit.searchHint")}
        error={error}
        autoComplete="off"
      />
      {results.length > 0 ? (
        <ul className="pick-list mt-1" role="listbox" data-testid="visit-person-results">
          {results.map((p) => (
            <li key={p.id}>
              <button type="button" className="pick" onClick={() => onChange(p)}>
                <Search size={14} aria-hidden="true" />
                <span className="min-w-0 flex-1 text-left">
                  {p.name}
                  {p.label ? <span className="text-ink-3"> · {p.label}</span> : null}
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function VisitDialog({ visit, open, onClose, onSaved }: { visit: InfirmaryVisit | null; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const stock = useApiData(open ? "health:medicines" : null, healthApi.medicines);
  const form = useForm<VisitValues>(
    visit
      ? {
          personKind: visit.personKind,
          person: { id: (visit.studentId ?? visit.staffUserId) as string, kind: visit.personKind, name: visit.personName, admissionNo: null, label: visit.className },
          timeIn: "",
          complaint: visit.complaint,
          temperatureC: visit.temperatureC === null ? "" : String(visit.temperatureC),
          treatment: visit.treatment ?? "",
          medicines: visit.medicines.map((m) => ({ medicineId: m.medicineId, quantity: String(m.quantity) })),
        }
      : { personKind: "STUDENT", person: null, timeIn: localNow(), complaint: "", temperatureC: "", treatment: "", medicines: [] },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const medicines: Medicine[] = stock.data ?? [];
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(visitProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: VisitRequest = {
      personKind: v.personKind,
      studentId: v.personKind === "STUDENT" ? (v.person?.id ?? null) : null,
      staffUserId: v.personKind === "STAFF" ? (v.person?.id ?? null) : null,
      timeIn: visit ? null : localToInstant(v.timeIn),
      complaint: v.complaint.trim(),
      temperatureC: numberOrNull(v.temperatureC),
      treatment: v.treatment.trim() || null,
      medicines: v.medicines.map((m) => ({ medicineId: m.medicineId, quantity: Number(m.quantity) })),
    };
    const ok = await form.submit(t, async () => {
      if (visit) await healthApi.updateVisit(visit.id, body);
      else await healthApi.addVisit(body);
    });
    if (ok) onSaved();
  };
  const setMedicine = (index: number, patch: Partial<{ medicineId: string; quantity: string }>) =>
    form.set(
      "medicines",
      form.values.medicines.map((m, i) => (i === index ? { ...m, ...patch } : m)),
    );
  return (
    <Dialog open={open} onClose={onClose} title={visit ? t("health.visit.edit") : t("health.visit.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="visit-form">
        <FormAlert message={form.formError} />
        {visit ? null : (
          <div className="seg self-start" role="radiogroup" aria-label={t("health.visit.who")}>
            {(["STUDENT", "STAFF"] as const).map((kind) => (
              <label key={kind}>
                <input
                  type="radio"
                  name="personKind"
                  value={kind}
                  checked={form.values.personKind === kind}
                  onChange={() => {
                    form.set("personKind", kind);
                    form.set("person", null);
                  }}
                />
                {translateOr(t, `health.person.${kind}`, kind)}
              </label>
            ))}
          </div>
        )}
        {visit ? (
          <p className="text-[13.5px]">
            <b>{visit.personName}</b>
            {visit.className ? <span className="text-ink-3"> · {visit.className}</span> : null}
          </p>
        ) : (
          <PersonPicker kind={form.values.personKind} value={form.values.person} onChange={(p) => form.set("person", p)} error={form.errors.person ?? form.errors.studentId ?? form.errors.staffUserId} />
        )}
        {form.values.person ? null : null}
        <TextAreaField label={t("health.visit.complaint")} name="complaint" value={form.values.complaint} onChange={(e) => form.set("complaint", e.target.value)} error={form.errors.complaint} maxLength={500} rows={2} required />
        <div className="grid grid-cols-2 gap-3">
          <TextField
            label={t("health.visit.temperatureField")}
            name="temperatureC"
            type="number"
            inputMode="decimal"
            step="0.1"
            min={30}
            max={45}
            value={form.values.temperatureC}
            onChange={(e) => form.set("temperatureC", e.target.value)}
            error={form.errors.temperatureC}
            labelAction={<Thermometer size={14} aria-hidden="true" />}
          />
          {visit ? null : (
            <TextField label={t("health.visit.timeIn")} name="timeIn" type="datetime-local" value={form.values.timeIn} onChange={(e) => form.set("timeIn", e.target.value)} error={form.errors.timeIn} />
          )}
        </div>
        <TextAreaField label={t("health.visit.treatment")} name="treatment" value={form.values.treatment} onChange={(e) => form.set("treatment", e.target.value)} error={form.errors.treatment} maxLength={1000} rows={2} />
        <fieldset className="flex flex-col gap-2">
          <legend className="field-label">{t("health.visit.medicines")}</legend>
          {form.values.medicines.map((m, i) => (
            <div key={i} className="flex items-end gap-2">
              <SelectField
                className="flex-1"
                label={t("health.medicine.name")}
                name={`medicine-${i}`}
                value={m.medicineId}
                onChange={(e) => setMedicine(i, { medicineId: e.target.value })}
                options={[{ value: "", label: t("health.visit.pickMedicine") }, ...medicines.map((s) => ({ value: s.id, label: `${s.name} (${s.quantity} ${s.unit})` }))]}
              />
              <TextField className="w-24" label={t("health.medicine.quantity")} name={`quantity-${i}`} type="number" inputMode="numeric" min={1} max={1000} value={m.quantity} onChange={(e) => setMedicine(i, { quantity: e.target.value })} />
              <button type="button" className="iconbtn mb-1" aria-label={t("common.delete")} onClick={() => form.set("medicines", form.values.medicines.filter((_, j) => j !== i))}>
                <Trash2 size={16} aria-hidden="true" />
              </button>
            </div>
          ))}
          {form.errors.medicines ? (
            <p className="field-error" role="alert">
              {form.errors.medicines}
            </p>
          ) : null}
          {form.values.medicines.length < 10 ? (
            <button type="button" className="btn btn-sm self-start" onClick={() => form.set("medicines", [...form.values.medicines, { medicineId: "", quantity: "1" }])} disabled={medicines.length === 0} data-testid="visit-add-medicine">
              <Plus size={14} aria-hidden="true" />
              {medicines.length === 0 ? t("health.visit.noStock") : t("health.visit.addMedicine")}
            </button>
          ) : null}
        </fieldset>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="visit-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type CloseValues = { outcome: VisitOutcome; timeOut: string; treatment: string; parentInformed: boolean };

/** Closing a visit: the outcome, and for a child sent home or to hospital, whether the parent was told. */
function CloseVisitDialog({ visit, onClose, onSaved }: { visit: InfirmaryVisit | null; onClose: () => void; onSaved: (outcome: VisitOutcome) => void }) {
  const { t } = useI18n();
  const form = useForm<CloseValues>({ outcome: "BACK_TO_CLASS", timeOut: "", treatment: visit?.treatment ?? "", parentInformed: true });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const student = visit?.personKind === "STUDENT";
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!visit) return;
    const problems: Problems = {};
    if (form.values.timeOut && localToInstant(form.values.timeOut) === null) problems.timeOut = "health.v.time";
    if (!form.check(problems, t, formEl)) return;
    const body: CloseVisitRequest = {
      outcome: form.values.outcome,
      timeOut: localToInstant(form.values.timeOut),
      treatment: form.values.treatment.trim() || null,
      parentInformed: student && form.values.outcome !== "BACK_TO_CLASS" ? form.values.parentInformed : null,
    };
    const ok = await form.submit(t, () => healthApi.closeVisit(visit.id, body).then(() => undefined));
    if (ok) onSaved(form.values.outcome);
  };
  return (
    <Dialog open={visit !== null} onClose={onClose} title={t("health.visit.closeTitle", { name: visit?.personName ?? "" })} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="close-form">
        <FormAlert message={form.formError} />
        <div className="seg self-start" role="radiogroup" aria-label={t("health.visit.outcome")}>
          {VISIT_OUTCOMES.map((o) => (
            <label key={o}>
              <input type="radio" name="outcome" value={o} checked={form.values.outcome === o} onChange={() => form.set("outcome", o)} />
              {outcomeLabel(t, o)}
            </label>
          ))}
        </div>
        <TextField label={t("health.visit.timeOut")} name="timeOut" type="datetime-local" value={form.values.timeOut} onChange={(e) => form.set("timeOut", e.target.value)} error={form.errors.timeOut} hint={t("health.visit.timeOutHint")} />
        <TextAreaField label={t("health.visit.treatment")} name="treatment" value={form.values.treatment} onChange={(e) => form.set("treatment", e.target.value)} error={form.errors.treatment} maxLength={1000} rows={2} />
        {student && form.values.outcome !== "BACK_TO_CLASS" ? (
          <label className="check">
            <input type="checkbox" name="parentInformed" checked={form.values.parentInformed} onChange={(e) => form.set("parentInformed", e.target.checked)} />
            <span>{t("health.visit.informParent")}</span>
          </label>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="close-save">
            {t("health.visit.close")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** A visit line for the record pages (the student's record and the family app). */
export function VisitLine({ visit, locale }: { visit: InfirmaryVisit; locale: string }) {
  const { t } = useI18n();
  return (
    <li className="rowcard items-start" data-testid="record-visit">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold">{formatPlainDate(visit.timeIn.slice(0, 10), locale)}</span>
          <OutcomePill outcome={visit.outcome} />
        </div>
        <p className="text-[13px] text-ink-2">
          {visit.complaint}
          {visit.temperatureC !== null ? ` · ${t("health.visit.temperature", { value: visit.temperatureC })}` : ""}
          {visit.treatment ? ` · ${visit.treatment}` : ""}
        </p>
        <p className="text-[12.5px] text-ink-3">
          {t("health.visit.in", { time: when(visit.timeIn, locale) })}
          {visit.timeOut ? ` · ${t("health.visit.out", { time: when(visit.timeOut, locale) })}` : ""}
          {visit.medicines.length > 0 ? ` · ${visit.medicines.map((m) => `${m.name} ×${m.quantity}`).join(", ")}` : ""}
        </p>
      </div>
    </li>
  );
}
