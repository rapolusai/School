"use client";

import { Check, Flame, Pencil, Plus, Siren, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate } from "@/lib/format";
import { healthApi } from "@/lib/health-api";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import type { AuditArea, AuditRequest, AuditSeverity, DrillKind, DrillRequest, FireDrill, FirstAidKit, Incident, IncidentKind, IncidentRequest, IncidentSeverity, KitCheckRequest, SafetyAudit } from "@/lib/types";
import { AUDIT_AREAS, AUDIT_SEVERITIES, DRILL_KINDS, INCIDENT_KINDS, INCIDENT_SEVERITIES } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { DATE, DuePill, HealthNav, localNow, localToInstant, when } from "./health-shared";

type Tab = "drills" | "audits" | "incidents" | "kits";
const TABS: Tab[] = ["drills", "audits", "incidents", "kits"];

export type DrillValues = { drillDate: string; kind: DrillKind; block: string; timeToClearSeconds: string; attendance: string; issues: string; nextDue: string };

/** Client-side checks mirroring DrillForm on the API. Pure and exported for tests. */
export function drillProblems(values: DrillValues, today: string): Problems {
  const problems: Problems = {};
  if (!DATE.test(values.drillDate)) problems.drillDate = "health.v.date";
  else if (values.drillDate > today) problems.drillDate = "health.v.futureDate";
  if (!values.block.trim()) problems.block = "health.v.block";
  if (!/^\d+$/.test(values.timeToClearSeconds) || Number(values.timeToClearSeconds) < 1) problems.timeToClearSeconds = "health.v.seconds";
  if (!/^\d+$/.test(values.attendance)) problems.attendance = "health.v.attendance";
  if (values.nextDue && (!DATE.test(values.nextDue) || values.nextDue <= values.drillDate)) problems.nextDue = "health.v.nextDue";
  return problems;
}

/** /app/health/safety: drills, the safety audit checklist, the incident register and first-aid kits. */
export function SafetyView() {
  const { t } = useI18n();
  const [tab, setTab] = useState<Tab>("drills");
  const overview = useApiData("health:safety", healthApi.safety);
  const data = overview.data;
  return (
    <>
      <PageHead eyebrow={t("nav.health")} title={t("health.safety.title")} />
      <HealthNav />
      <div className="seg no-print self-start" role="tablist" aria-label={t("health.safety.title")}>
        {TABS.map((k) => (
          <label key={k}>
            <input type="radio" name="safety-tab" value={k} checked={tab === k} onChange={() => setTab(k)} />
            {translateOr(t, `health.safety.tab.${k}`, k)}
          </label>
        ))}
      </div>
      {overview.error && !data ? (
        <ErrorState error={overview.error} onRetry={overview.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : tab === "drills" ? (
        <DrillsTab drills={data.drills} today={data.today} reload={overview.reload} />
      ) : tab === "audits" ? (
        <AuditsTab audits={data.audits} today={data.today} reload={overview.reload} />
      ) : tab === "incidents" ? (
        <IncidentsTab incidents={data.incidents} reload={overview.reload} />
      ) : (
        <KitsTab kits={data.kits} today={data.today} reload={overview.reload} />
      )}
    </>
  );
}

/* ------------------------------------------------------------------ drills */

function DrillsTab({ drills, today, reload }: { drills: FireDrill[]; today: string; reload: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [editing, setEditing] = useState<FireDrill | "new" | null>(null);
  const [removing, setRemoving] = useState<FireDrill | null>(null);
  return (
    <section className="card" data-testid="drills">
      <div className="card-head">
        <h2>{t("health.drill.title")}</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")} data-testid="drill-add">
          <Plus size={14} aria-hidden="true" />
          {t("health.drill.add")}
        </button>
      </div>
      {drills.length === 0 ? (
        <p className="empty">{t("health.drill.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="drill-list">
          {drills.map((d) => (
            <li key={d.id} className="rowcard items-start" data-testid="drill-row">
              <span className="badge-ic" aria-hidden="true">
                <Flame size={18} />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">{formatPlainDate(d.drillDate, locale)}</span>
                  <Pill tone="info">{translateOr(t, `health.drillKind.${d.kind}`, d.kind)}</Pill>
                  <span className="text-[13px] text-ink-3">{d.block}</span>
                  <DuePill due={d.due} />
                </div>
                <p className="text-[13px] text-ink-2">
                  {t("health.drill.cleared", { seconds: d.timeToClearSeconds, count: d.attendance })}
                  {d.nextDue ? ` · ${t("health.drill.nextDue", { date: formatPlainDate(d.nextDue, locale) })}` : ""}
                </p>
                {d.issues ? <p className="text-[12.5px] text-ink-3">{d.issues}</p> : null}
              </div>
              <div className="flex gap-1">
                <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => setEditing(d)}>
                  <Pencil size={16} aria-hidden="true" />
                </button>
                <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => setRemoving(d)}>
                  <Trash2 size={16} aria-hidden="true" />
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      <DrillDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        drill={editing === "new" ? null : editing}
        today={today}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("health.drill.saved"));
          reload();
        }}
      />
      <ConfirmDialog
        open={removing !== null}
        title={t("health.drill.removeTitle")}
        body={removing ? t("health.drill.removeBody", { date: formatPlainDate(removing.drillDate, locale) }) : ""}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await healthApi.removeDrill(removing.id);
          setRemoving(null);
          reload();
        }}
        onClose={() => setRemoving(null)}
      />
    </section>
  );
}

function DrillDialog({ drill, today, open, onClose, onSaved }: { drill: FireDrill | null; today: string; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<DrillValues>(
    drill
      ? { drillDate: drill.drillDate, kind: drill.kind, block: drill.block, timeToClearSeconds: String(drill.timeToClearSeconds), attendance: String(drill.attendance), issues: drill.issues ?? "", nextDue: drill.nextDue ?? "" }
      : { drillDate: today, kind: "FIRE", block: "", timeToClearSeconds: "", attendance: "", issues: "", nextDue: "" },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(drillProblems(form.values, today), t, formEl)) return;
    const v = form.values;
    const body: DrillRequest = { drillDate: v.drillDate, kind: v.kind, block: v.block.trim(), timeToClearSeconds: Number(v.timeToClearSeconds), attendance: Number(v.attendance), issues: v.issues.trim() || null, nextDue: v.nextDue || null };
    const ok = await form.submit(t, async () => {
      if (drill) await healthApi.updateDrill(drill.id, body);
      else await healthApi.addDrill(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={drill ? t("health.drill.edit") : t("health.drill.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="drill-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("health.drill.date")} name="drillDate" type="date" max={today} value={form.values.drillDate} onChange={(e) => form.set("drillDate", e.target.value)} error={form.errors.drillDate} required />
          <SelectField label={t("health.drill.kind")} name="kind" value={form.values.kind} onChange={(e) => form.set("kind", e.target.value as DrillKind)} options={DRILL_KINDS.map((k) => ({ value: k, label: translateOr(t, `health.drillKind.${k}`, k) }))} />
        </div>
        <TextField label={t("health.drill.block")} name="block" value={form.values.block} onChange={(e) => form.set("block", e.target.value)} error={form.errors.block} maxLength={100} required />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("health.drill.seconds")} name="timeToClearSeconds" type="number" inputMode="numeric" min={1} max={7200} value={form.values.timeToClearSeconds} onChange={(e) => form.set("timeToClearSeconds", e.target.value)} error={form.errors.timeToClearSeconds} required />
          <TextField label={t("health.drill.attendance")} name="attendance" type="number" inputMode="numeric" min={0} value={form.values.attendance} onChange={(e) => form.set("attendance", e.target.value)} error={form.errors.attendance} required />
        </div>
        <TextField label={t("health.drill.nextDueField")} name="nextDue" type="date" value={form.values.nextDue} onChange={(e) => form.set("nextDue", e.target.value)} error={form.errors.nextDue} hint={t("health.drill.nextDueHint")} />
        <TextAreaField label={t("health.drill.issues")} name="issues" value={form.values.issues} onChange={(e) => form.set("issues", e.target.value)} error={form.errors.issues} maxLength={1000} rows={2} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="drill-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ audits */

type AuditValues = { area: AuditArea; auditDate: string; finding: string; severity: AuditSeverity; dueOn: string };

export function auditProblems(values: AuditValues): Problems {
  const problems: Problems = {};
  if (!DATE.test(values.auditDate)) problems.auditDate = "health.v.date";
  if (!values.finding.trim()) problems.finding = "health.v.finding";
  if (values.dueOn && !DATE.test(values.dueOn)) problems.dueOn = "health.v.date";
  return problems;
}

const AUDIT_TONE = { LOW: "info", MEDIUM: "warn", HIGH: "bad" } as const;

function AuditsTab({ audits, today, reload }: { audits: SafetyAudit[]; today: string; reload: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [editing, setEditing] = useState<SafetyAudit | "new" | null>(null);
  const [closing, setClosing] = useState<SafetyAudit | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const rows = showClosed ? audits : audits.filter((a) => a.status === "OPEN");
  return (
    <section className="card" data-testid="audits">
      <div className="card-head">
        <h2>{t("health.audit.title")}</h2>
        <div className="flex flex-wrap gap-2">
          <label className="check">
            <input type="checkbox" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
            <span>{t("health.audit.showClosed")}</span>
          </label>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")} data-testid="audit-add">
            <Plus size={14} aria-hidden="true" />
            {t("health.audit.add")}
          </button>
        </div>
      </div>
      <p className="mb-2 text-[13px] text-ink-3">{t("health.audit.intro")}</p>
      {rows.length === 0 ? (
        <p className="empty">{t("health.audit.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="audit-list">
          {rows.map((a) => (
            <li key={a.id} className="rowcard items-start" data-testid="audit-row" data-status={a.status}>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">{translateOr(t, `health.area.${a.area}`, a.area)}</span>
                  <Pill tone={AUDIT_TONE[a.severity]}>{translateOr(t, `health.auditSeverity.${a.severity}`, a.severity)}</Pill>
                  {a.status === "CLOSED" ? <Pill tone="good">{t("health.audit.closed")}</Pill> : a.overdue ? <Pill tone="bad">{t("health.due.OVERDUE")}</Pill> : <Pill tone="info">{t("health.audit.open")}</Pill>}
                </div>
                <p className="text-[13px] text-ink-2">{a.finding}</p>
                <p className="text-[12.5px] text-ink-3">
                  {formatPlainDate(a.auditDate, locale)}
                  {a.dueOn ? ` · ${t("health.audit.dueOn", { date: formatPlainDate(a.dueOn, locale) })}` : ""}
                  {a.closedOn ? ` · ${t("health.audit.closedOn", { date: formatPlainDate(a.closedOn, locale) })}` : ""}
                  {a.closureNote ? ` · ${a.closureNote}` : ""}
                </p>
              </div>
              <div className="flex gap-1">
                {a.status === "OPEN" ? (
                  <>
                    <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => setEditing(a)}>
                      <Pencil size={16} aria-hidden="true" />
                    </button>
                    <button type="button" className="btn btn-sm" onClick={() => setClosing(a)} data-testid="audit-close">
                      <Check size={14} aria-hidden="true" />
                      {t("health.audit.closeButton")}
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={async () => {
                      await healthApi.reopenAudit(a.id);
                      reload();
                    }}
                  >
                    {t("health.audit.reopen")}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      <AuditDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        audit={editing === "new" ? null : editing}
        today={today}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("health.audit.saved"));
          reload();
        }}
      />
      <CloseAuditDialog
        key={closing?.id ?? "no-close"}
        audit={closing}
        today={today}
        onClose={() => setClosing(null)}
        onSaved={() => {
          setClosing(null);
          toast(t("health.audit.saved"));
          reload();
        }}
      />
    </section>
  );
}

function AuditDialog({ audit, today, open, onClose, onSaved }: { audit: SafetyAudit | null; today: string; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<AuditValues>(audit ? { area: audit.area, auditDate: audit.auditDate, finding: audit.finding, severity: audit.severity, dueOn: audit.dueOn ?? "" } : { area: "ELECTRICAL", auditDate: today, finding: "", severity: "MEDIUM", dueOn: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(auditProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: AuditRequest = { area: v.area, auditDate: v.auditDate, finding: v.finding.trim(), severity: v.severity, dueOn: v.dueOn || null };
    const ok = await form.submit(t, async () => {
      if (audit) await healthApi.updateAudit(audit.id, body);
      else await healthApi.addAudit(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={audit ? t("health.audit.edit") : t("health.audit.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="audit-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <SelectField label={t("health.audit.area")} name="area" value={form.values.area} onChange={(e) => form.set("area", e.target.value as AuditArea)} options={AUDIT_AREAS.map((a) => ({ value: a, label: translateOr(t, `health.area.${a}`, a) }))} />
          <TextField label={t("health.audit.date")} name="auditDate" type="date" max={today} value={form.values.auditDate} onChange={(e) => form.set("auditDate", e.target.value)} error={form.errors.auditDate} required />
        </div>
        <TextAreaField label={t("health.audit.finding")} name="finding" value={form.values.finding} onChange={(e) => form.set("finding", e.target.value)} error={form.errors.finding} maxLength={1000} rows={2} required />
        <div className="grid grid-cols-2 gap-3">
          <SelectField label={t("health.audit.severity")} name="severity" value={form.values.severity} onChange={(e) => form.set("severity", e.target.value as AuditSeverity)} options={AUDIT_SEVERITIES.map((s) => ({ value: s, label: translateOr(t, `health.auditSeverity.${s}`, s) }))} />
          <TextField label={t("health.audit.dueOnField")} name="dueOn" type="date" value={form.values.dueOn} onChange={(e) => form.set("dueOn", e.target.value)} error={form.errors.dueOn} />
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="audit-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function CloseAuditDialog({ audit, today, onClose, onSaved }: { audit: SafetyAudit | null; today: string; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ closedOn: string; closureNote: string }>({ closedOn: today, closureNote: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!audit) return;
    if (!form.check(DATE.test(form.values.closedOn) ? {} : { closedOn: "health.v.date" }, t, formEl)) return;
    const ok = await form.submit(t, () => healthApi.closeAudit(audit.id, { closedOn: form.values.closedOn, closureNote: form.values.closureNote.trim() || null }).then(() => undefined));
    if (ok) onSaved();
  };
  return (
    <Dialog open={audit !== null} onClose={onClose} title={t("health.audit.closeTitle")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="audit-close-form">
        <FormAlert message={form.formError} />
        <TextField label={t("health.audit.closedOnField")} name="closedOn" type="date" max={today} value={form.values.closedOn} onChange={(e) => form.set("closedOn", e.target.value)} error={form.errors.closedOn} required />
        <TextAreaField label={t("health.audit.closureNote")} name="closureNote" value={form.values.closureNote} onChange={(e) => form.set("closureNote", e.target.value)} error={form.errors.closureNote} maxLength={1000} rows={2} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {t("health.audit.closeButton")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ incidents */

type IncidentValues = { incidentAt: string; kind: IncidentKind; severity: IncidentSeverity; personName: string; place: string; description: string; actions: string; parentInformed: boolean };

export function incidentProblems(values: IncidentValues): Problems {
  const problems: Problems = {};
  if (!values.description.trim()) problems.description = "health.v.description";
  if (values.incidentAt && localToInstant(values.incidentAt) === null) problems.incidentAt = "health.v.time";
  return problems;
}

const INCIDENT_TONE = { MINOR: "info", MODERATE: "warn", SERIOUS: "bad" } as const;

function IncidentsTab({ incidents, reload }: { incidents: Incident[]; reload: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [editing, setEditing] = useState<Incident | "new" | null>(null);
  return (
    <section className="card" data-testid="incidents">
      <div className="card-head">
        <h2>{t("health.incident.title")}</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")} data-testid="incident-add">
          <Plus size={14} aria-hidden="true" />
          {t("health.incident.add")}
        </button>
      </div>
      {incidents.length === 0 ? (
        <p className="empty">{t("health.incident.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="incident-list">
          {incidents.map((i) => (
            <li key={i.id} className="rowcard items-start" data-testid="incident-row">
              <span className="badge-ic" aria-hidden="true">
                <Siren size={18} />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">{translateOr(t, `health.incidentKind.${i.kind}`, i.kind)}</span>
                  <Pill tone={INCIDENT_TONE[i.severity]}>{translateOr(t, `health.incidentSeverity.${i.severity}`, i.severity)}</Pill>
                  {i.personName ? <span className="text-[13px] text-ink-3">{i.personName}{i.className ? ` · ${i.className}` : ""}</span> : null}
                </div>
                <p className="text-[13px] text-ink-2">{i.description}</p>
                <p className="text-[12.5px] text-ink-3">
                  {when(i.incidentAt, locale)}
                  {i.place ? ` · ${i.place}` : ""}
                  {i.actions ? ` · ${i.actions}` : ""}
                  {i.parentInformed ? ` · ${t("health.visit.parentInformed")}` : ""}
                </p>
              </div>
              <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => setEditing(i)}>
                <Pencil size={16} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <IncidentDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        incident={editing === "new" ? null : editing}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("health.incident.saved"));
          reload();
        }}
      />
    </section>
  );
}

function IncidentDialog({ incident, open, onClose, onSaved }: { incident: Incident | null; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<IncidentValues>(
    incident
      ? { incidentAt: "", kind: incident.kind, severity: incident.severity, personName: incident.personName ?? "", place: incident.place ?? "", description: incident.description, actions: incident.actions ?? "", parentInformed: incident.parentInformed }
      : { incidentAt: localNow(), kind: "INJURY", severity: "MINOR", personName: "", place: "", description: "", actions: "", parentInformed: false },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(incidentProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: IncidentRequest = {
      incidentAt: incident ? incident.incidentAt : localToInstant(v.incidentAt),
      kind: v.kind,
      severity: v.severity,
      studentId: incident?.studentId ?? null,
      personName: v.personName.trim() || null,
      place: v.place.trim() || null,
      description: v.description.trim(),
      actions: v.actions.trim() || null,
      parentInformed: v.parentInformed,
    };
    const ok = await form.submit(t, async () => {
      if (incident) await healthApi.updateIncident(incident.id, body);
      else await healthApi.addIncident(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={incident ? t("health.incident.edit") : t("health.incident.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="incident-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <SelectField label={t("health.incident.kind")} name="kind" value={form.values.kind} onChange={(e) => form.set("kind", e.target.value as IncidentKind)} options={INCIDENT_KINDS.map((k) => ({ value: k, label: translateOr(t, `health.incidentKind.${k}`, k) }))} />
          <SelectField label={t("health.incident.severity")} name="severity" value={form.values.severity} onChange={(e) => form.set("severity", e.target.value as IncidentSeverity)} options={INCIDENT_SEVERITIES.map((s) => ({ value: s, label: translateOr(t, `health.incidentSeverity.${s}`, s) }))} />
        </div>
        {incident ? null : <TextField label={t("health.incident.when")} name="incidentAt" type="datetime-local" value={form.values.incidentAt} onChange={(e) => form.set("incidentAt", e.target.value)} error={form.errors.incidentAt} />}
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("health.incident.person")} name="personName" value={form.values.personName} onChange={(e) => form.set("personName", e.target.value)} error={form.errors.personName} maxLength={200} />
          <TextField label={t("health.incident.place")} name="place" value={form.values.place} onChange={(e) => form.set("place", e.target.value)} error={form.errors.place} maxLength={200} />
        </div>
        <TextAreaField label={t("health.incident.description")} name="description" value={form.values.description} onChange={(e) => form.set("description", e.target.value)} error={form.errors.description} maxLength={2000} rows={3} required />
        <TextAreaField label={t("health.incident.actions")} name="actions" value={form.values.actions} onChange={(e) => form.set("actions", e.target.value)} error={form.errors.actions} maxLength={2000} rows={2} />
        <label className="check">
          <input type="checkbox" name="parentInformed" checked={form.values.parentInformed} onChange={(e) => form.set("parentInformed", e.target.checked)} />
          <span>{t("health.visit.informParent")}</span>
        </label>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="incident-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ first-aid kits */

function KitsTab({ kits, today, reload }: { kits: FirstAidKit[]; today: string; reload: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [location, setLocation] = useState("");
  const [checking, setChecking] = useState<FirstAidKit | null>(null);
  const [removing, setRemoving] = useState<FirstAidKit | null>(null);
  const [error, setError] = useState<string | null>(null);
  const add = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!location.trim()) return;
    try {
      await healthApi.addKit(location.trim());
      setLocation("");
      setError(null);
      reload();
    } catch {
      setError(t("health.kit.addFailed"));
    }
  };
  return (
    <section className="card" data-testid="kits">
      <div className="card-head">
        <h2>{t("health.kit.title")}</h2>
      </div>
      <form method="post" className="toolbar" onSubmit={add} data-testid="kit-form">
        <TextField className="flex-1" label={t("health.kit.location")} name="location" value={location} onChange={(e) => setLocation(e.target.value)} maxLength={120} error={error ?? undefined} />
        <button type="submit" className="btn btn-primary self-end" disabled={!location.trim()} data-testid="kit-add">
          <Plus size={16} aria-hidden="true" />
          {t("health.kit.add")}
        </button>
      </form>
      {kits.length === 0 ? (
        <p className="empty">{t("health.kit.empty")}</p>
      ) : (
        <ul className="rowcards" data-testid="kit-list">
          {kits.map((k) => (
            <li key={k.id} className="rowcard items-start" data-testid="kit-row">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">{k.location}</span>
                  {k.checkedOn ? k.complete ? <Pill tone="good">{t("health.kit.complete")}</Pill> : <Pill tone="warn">{t("health.kit.incomplete")}</Pill> : <Pill tone="neutral">{t("health.kit.neverChecked")}</Pill>}
                  {k.checkDue ? <Pill tone="bad">{t("health.kit.checkDue")}</Pill> : null}
                  {k.earliestExpiry ? <DuePill due={k.expiry} /> : null}
                </div>
                <p className="text-[12.5px] text-ink-3">
                  {k.checkedOn ? t("health.kit.checkedOn", { date: formatPlainDate(k.checkedOn, locale), name: k.checkedByName ?? "" }) : ""}
                  {k.earliestExpiry ? ` · ${t("health.kit.expiry", { date: formatPlainDate(k.earliestExpiry, locale) })}` : ""}
                  {k.missingItems ? ` · ${t("health.kit.missing", { items: k.missingItems })}` : ""}
                </p>
              </div>
              <div className="flex gap-1">
                <button type="button" className="btn btn-sm" onClick={() => setChecking(k)} data-testid="kit-check">
                  <Check size={14} aria-hidden="true" />
                  {t("health.kit.check")}
                </button>
                <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => setRemoving(k)}>
                  <Trash2 size={16} aria-hidden="true" />
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      <KitCheckDialog
        key={checking?.id ?? "no-check"}
        kit={checking}
        today={today}
        onClose={() => setChecking(null)}
        onSaved={() => {
          setChecking(null);
          toast(t("health.kit.saved"));
          reload();
        }}
      />
      <ConfirmDialog
        open={removing !== null}
        title={t("health.kit.removeTitle")}
        body={removing ? t("health.kit.removeBody", { location: removing.location }) : ""}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await healthApi.removeKit(removing.id);
          setRemoving(null);
          reload();
        }}
        onClose={() => setRemoving(null)}
      />
    </section>
  );
}

function KitCheckDialog({ kit, today, onClose, onSaved }: { kit: FirstAidKit | null; today: string; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ checkedOn: string; complete: boolean; earliestExpiry: string; missingItems: string }>({ checkedOn: today, complete: true, earliestExpiry: kit?.earliestExpiry ?? "", missingItems: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!kit) return;
    const problems: Problems = {};
    if (!DATE.test(form.values.checkedOn)) problems.checkedOn = "health.v.date";
    if (form.values.earliestExpiry && !DATE.test(form.values.earliestExpiry)) problems.earliestExpiry = "health.v.date";
    if (!form.check(problems, t, formEl)) return;
    const body: KitCheckRequest = { checkedOn: form.values.checkedOn, complete: form.values.complete, earliestExpiry: form.values.earliestExpiry || null, missingItems: form.values.missingItems.trim() || null };
    const ok = await form.submit(t, () => healthApi.checkKit(kit.id, body).then(() => undefined));
    if (ok) onSaved();
  };
  return (
    <Dialog open={kit !== null} onClose={onClose} title={t("health.kit.checkTitle", { location: kit?.location ?? "" })} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="kit-check-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("health.kit.checkedOnField")} name="checkedOn" type="date" max={today} value={form.values.checkedOn} onChange={(e) => form.set("checkedOn", e.target.value)} error={form.errors.checkedOn} required />
          <TextField label={t("health.kit.expiryField")} name="earliestExpiry" type="date" value={form.values.earliestExpiry} onChange={(e) => form.set("earliestExpiry", e.target.value)} error={form.errors.earliestExpiry} hint={t("health.kit.expiryHint")} />
        </div>
        <label className="check">
          <input type="checkbox" name="complete" checked={form.values.complete} onChange={(e) => form.set("complete", e.target.checked)} />
          <span>{t("health.kit.completeField")}</span>
        </label>
        <TextAreaField label={t("health.kit.missingField")} name="missingItems" value={form.values.missingItems} onChange={(e) => form.set("missingItems", e.target.value)} error={form.errors.missingItems} maxLength={500} rows={2} />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="kit-check-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
