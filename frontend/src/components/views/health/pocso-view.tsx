"use client";

import { Lock, Plus, ShieldAlert, Trash2, Users } from "lucide-react";
import { useEffect, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { healthApi } from "@/lib/health-api";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { CommitteeMember, CommitteeRole, ComplaintCategory, ComplaintRequest, ComplaintStatus, PocsoComplaint } from "@/lib/types";
import { COMMITTEE_ROLES, COMPLAINT_CATEGORIES, COMPLAINT_STATUSES } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { DATE, HealthNav } from "./health-shared";

const STATUS_TONE = { OPEN: "warn", UNDER_ENQUIRY: "info", REFERRED: "accent", CLOSED: "good" } as const;

/** /app/health/pocso: the committee (every staff member may see who is on it) and the complaints register (committee only). */
export function PocsoView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { me } = useAuth();
  const { toast } = useToast();
  const [today] = useState(() => todayInIndia());
  const committee = useApiData("health:pocso:committee", healthApi.committee);
  const c = committee.data;
  const canManage = hasPermission(me, PERMISSIONS.safetyManage);
  const [addingMember, setAddingMember] = useState(false);
  const [addingMeeting, setAddingMeeting] = useState(false);
  const [removing, setRemoving] = useState<CommitteeMember | null>(null);
  const [formedOn, setFormedOn] = useState<string | null>(null);
  return (
    <>
      <PageHead eyebrow={t("nav.health")} title={t("health.pocso.title")} />
      <HealthNav />
      {committee.error && !c ? (
        <ErrorState error={committee.error} onRetry={committee.reload} />
      ) : !c ? (
        <LoadingRows rows={3} />
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="card" data-testid="pocso-committee">
            <div className="card-head">
              <h2>{t("health.pocso.committee")}</h2>
              {canManage ? (
                <button type="button" className="btn btn-sm" onClick={() => setAddingMember(true)} data-testid="member-add">
                  <Plus size={14} aria-hidden="true" />
                  {t("health.pocso.addMember")}
                </button>
              ) : null}
            </div>
            <p className="mb-2 text-[13px] text-ink-3">{t("health.pocso.intro")}</p>
            <div className="mb-2 flex flex-wrap items-center gap-2 text-[13.5px]">
              <Users size={16} aria-hidden="true" className="text-ink-3" />
              {c.formedOn ? t("health.pocso.formedOn", { date: formatPlainDate(c.formedOn, locale) }) : t("health.pocso.notFormed")}
              {canManage ? (
                <form
                  method="post"
                  className="flex items-end gap-2"
                  onSubmit={async (e) => {
                    e.preventDefault();
                    if (formedOn === null || !DATE.test(formedOn)) return;
                    await healthApi.saveCommittee(formedOn);
                    setFormedOn(null);
                    toast(t("health.pocso.saved"));
                    committee.reload();
                  }}
                >
                  <TextField label={t("health.pocso.formedOnField")} name="formedOn" type="date" max={today} value={formedOn ?? c.formedOn ?? ""} onChange={(e) => setFormedOn(e.target.value)} />
                  <button type="submit" className="btn btn-sm" disabled={formedOn === null}>
                    {t("common.save")}
                  </button>
                </form>
              ) : null}
            </div>
            {c.members.length === 0 ? (
              <p className="empty">{t("health.pocso.noMembers")}</p>
            ) : (
              <ul className="rowcards" data-testid="member-list">
                {c.members.map((m) => (
                  <li key={m.id} className="rowcard items-start" data-testid="member-row">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold">{m.name}</span>
                        <Pill tone={m.role === "CHAIRPERSON" ? "accent" : "neutral"}>{translateOr(t, `health.committeeRole.${m.role}`, m.role)}</Pill>
                      </div>
                      <p className="text-[12.5px] text-ink-3">
                        {m.designation ?? ""}
                        {m.appointedOn ? `${m.designation ? " · " : ""}${t("health.pocso.appointedOn", { date: formatPlainDate(m.appointedOn, locale) })}` : ""}
                      </p>
                    </div>
                    {canManage ? (
                      <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => setRemoving(m)}>
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section className="card" data-testid="pocso-meetings">
            <div className="card-head">
              <h2>{t("health.pocso.meetings")}</h2>
              {canManage ? (
                <button type="button" className="btn btn-sm" onClick={() => setAddingMeeting(true)} data-testid="meeting-add">
                  <Plus size={14} aria-hidden="true" />
                  {t("health.pocso.addMeeting")}
                </button>
              ) : null}
            </div>
            {c.meetings.length === 0 ? (
              <p className="empty">{t("health.pocso.noMeetings")}</p>
            ) : (
              <ul className="rowcards">
                {c.meetings.map((m) => (
                  <li key={m.id} className="rowcard items-start" data-testid="meeting-row">
                    <div className="min-w-0 flex-1">
                      <p className="font-semibold">{formatPlainDate(m.meetingDate, locale)}</p>
                      <p className="whitespace-pre-line text-[13px] text-ink-2">{m.minutes}</p>
                      {m.recordedByName ? <p className="text-[12.5px] text-ink-3">{t("health.item.by", { name: m.recordedByName })}</p> : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
          {c.canReadComplaints ? (
            <ComplaintsRegister today={today} />
          ) : (
            <section className="card lg:col-span-2" data-testid="pocso-complaints-locked">
              <div className="card-head">
                <h2>{t("health.pocso.complaints")}</h2>
                <Lock size={16} aria-hidden="true" className="text-ink-3" />
              </div>
              <p className="empty">{t("health.pocso.committeeOnly")}</p>
            </section>
          )}
        </div>
      )}
      <MemberDialog key={addingMember ? "member" : "no-member"} open={addingMember} today={today} onClose={() => setAddingMember(false)} onSaved={() => { setAddingMember(false); toast(t("health.pocso.saved")); committee.reload(); }} />
      <MeetingDialog key={addingMeeting ? "meeting" : "no-meeting"} open={addingMeeting} today={today} onClose={() => setAddingMeeting(false)} onSaved={() => { setAddingMeeting(false); toast(t("health.pocso.saved")); committee.reload(); }} />
      <ConfirmDialog
        open={removing !== null}
        title={t("health.pocso.removeTitle")}
        body={removing ? t("health.pocso.removeBody", { name: removing.name }) : ""}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await healthApi.removeMember(removing.id);
          setRemoving(null);
          committee.reload();
        }}
        onClose={() => setRemoving(null)}
      />
    </>
  );
}

function MemberDialog({ open, today, onClose, onSaved }: { open: boolean; today: string; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const staff = useApiData(open ? "health:staff:checks" : null, healthApi.staffChecks);
  const form = useForm<{ userId: string; role: CommitteeRole; appointedOn: string }>({ userId: "", role: "MEMBER", appointedOn: today });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!form.values.userId) problems.userId = "health.v.staff";
    if (form.values.appointedOn && !DATE.test(form.values.appointedOn)) problems.appointedOn = "health.v.date";
    if (!form.check(problems, t, formEl)) return;
    const ok = await form.submit(t, () => healthApi.addMember({ userId: form.values.userId, role: form.values.role, appointedOn: form.values.appointedOn || null }).then(() => undefined));
    if (ok) onSaved();
  };
  const options = (staff.data?.rows ?? []).filter((r) => r.active).map((r) => ({ value: r.userId, label: r.designation ? `${r.name} · ${r.designation}` : r.name }));
  return (
    <Dialog open={open} onClose={onClose} title={t("health.pocso.addMember")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="member-form">
        <FormAlert message={form.formError} />
        <SelectField label={t("health.pocso.member")} name="userId" value={form.values.userId} onChange={(e) => form.set("userId", e.target.value)} options={[{ value: "", label: t("health.pocso.pickMember") }, ...options]} error={form.errors.userId} required />
        <div className="grid grid-cols-2 gap-3">
          <SelectField label={t("health.pocso.role")} name="role" value={form.values.role} onChange={(e) => form.set("role", e.target.value as CommitteeRole)} options={COMMITTEE_ROLES.map((r) => ({ value: r, label: translateOr(t, `health.committeeRole.${r}`, r) }))} />
          <TextField label={t("health.pocso.appointedOnField")} name="appointedOn" type="date" max={today} value={form.values.appointedOn} onChange={(e) => form.set("appointedOn", e.target.value)} error={form.errors.appointedOn} />
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="member-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function MeetingDialog({ open, today, onClose, onSaved }: { open: boolean; today: string; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ meetingDate: string; minutes: string }>({ meetingDate: today, minutes: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!DATE.test(form.values.meetingDate)) problems.meetingDate = "health.v.date";
    if (!form.values.minutes.trim()) problems.minutes = "health.v.minutes";
    if (!form.check(problems, t, formEl)) return;
    const ok = await form.submit(t, () => healthApi.addMeeting({ meetingDate: form.values.meetingDate, minutes: form.values.minutes.trim() }).then(() => undefined));
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("health.pocso.addMeeting")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="meeting-form">
        <FormAlert message={form.formError} />
        <TextField label={t("health.pocso.meetingDate")} name="meetingDate" type="date" max={today} value={form.values.meetingDate} onChange={(e) => form.set("meetingDate", e.target.value)} error={form.errors.meetingDate} required />
        <TextAreaField label={t("health.pocso.minutes")} name="minutes" value={form.values.minutes} onChange={(e) => form.set("minutes", e.target.value)} error={form.errors.minutes} maxLength={8000} rows={5} required />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="meeting-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ complaints (committee only, every read audited) */

type ComplaintValues = { complaintDate: string; category: ComplaintCategory; description: string; status: ComplaintStatus; actions: string; complainantNote: string };

export function complaintProblems(values: ComplaintValues): Problems {
  const problems: Problems = {};
  if (!DATE.test(values.complaintDate)) problems.complaintDate = "health.v.date";
  if (!values.description.trim()) problems.description = "health.v.description";
  return problems;
}

function ComplaintsRegister({ today }: { today: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const list = useApiData("health:pocso:complaints", healthApi.complaints);
  const [opened, setOpened] = useState<string | null>(null);
  const [editing, setEditing] = useState<PocsoComplaint | "new" | null>(null);
  const detail = useApiData(opened ? `health:pocso:complaint:${opened}` : null, () => healthApi.complaint(opened as string));
  const rows = list.data ?? [];
  return (
    <section className="card lg:col-span-2" data-testid="pocso-complaints">
      <div className="card-head">
        <h2>{t("health.pocso.complaints")}</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")} data-testid="complaint-add">
          <Plus size={14} aria-hidden="true" />
          {t("health.pocso.addComplaint")}
        </button>
      </div>
      <p className="mb-2 flex items-center gap-1 text-[13px] text-ink-3">
        <ShieldAlert size={14} aria-hidden="true" />
        {t("health.pocso.audited")}
      </p>
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={2} />
      ) : rows.length === 0 ? (
        <p className="empty">{t("health.pocso.noComplaints")}</p>
      ) : (
        <ul className="rowcards" data-testid="complaint-list">
          {rows.map((c) => (
            <li key={c.id} className="rowcard items-start" data-testid="complaint-row">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">{formatPlainDate(c.complaintDate, locale)}</span>
                  <Pill tone="neutral">{translateOr(t, `health.complaintCategory.${c.category}`, c.category)}</Pill>
                  <Pill tone={STATUS_TONE[c.status]} dot>
                    {translateOr(t, `health.complaintStatus.${c.status}`, c.status)}
                  </Pill>
                </div>
                <p className="text-[13px] text-ink-2">{c.description}</p>
                {c.actions ? <p className="text-[12.5px] text-ink-3">{c.actions}</p> : null}
                {opened === c.id ? (
                  detail.data ? (
                    <p className="mt-1 text-[13px]" data-testid="complainant-note">
                      <b>{t("health.pocso.complainant")}:</b> {detail.data.complainantNote || t("health.pocso.noComplainant")}
                    </p>
                  ) : detail.error ? (
                    <ErrorState error={detail.error} onRetry={detail.reload} />
                  ) : (
                    <LoadingRows rows={1} />
                  )
                ) : null}
              </div>
              <div className="flex gap-1">
                <button type="button" className="btn btn-sm" onClick={() => setOpened(opened === c.id ? null : c.id)} data-testid="complaint-open">
                  {opened === c.id ? t("common.close") : t("health.pocso.openComplaint")}
                </button>
                <button type="button" className="btn btn-sm" onClick={() => setEditing(c)}>
                  {t("common.edit")}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      <ComplaintDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        complaint={editing === "new" ? null : editing}
        today={today}
        open={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          setOpened(null);
          toast(t("health.pocso.saved"));
          list.reload();
        }}
      />
    </section>
  );
}

function ComplaintDialog({ complaint, today, open, onClose, onSaved }: { complaint: PocsoComplaint | null; today: string; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  // Editing loads the detail (audited) so the complainant note can be kept.
  const detail = useApiData(open && complaint ? `health:pocso:complaint:edit:${complaint.id}` : null, () => healthApi.complaint(complaint!.id));
  const form = useForm<ComplaintValues>(
    complaint
      ? { complaintDate: complaint.complaintDate, category: complaint.category, description: complaint.description, status: complaint.status, actions: complaint.actions ?? "", complainantNote: "" }
      : { complaintDate: today, category: "OTHER", description: "", status: "OPEN", actions: "", complainantNote: "" },
  );
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const note = detail.data?.complainantNote ?? null;
  const set = form.set;
  useEffect(() => {
    if (note !== null) set("complainantNote", note);
  }, [note, set]);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(complaintProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: ComplaintRequest = { complaintDate: v.complaintDate, category: v.category, description: v.description.trim(), status: v.status, actions: v.actions.trim() || null, complainantNote: v.complainantNote.trim() || null };
    const ok = await form.submit(t, async () => {
      if (complaint) await healthApi.updateComplaint(complaint.id, body);
      else await healthApi.addComplaint(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={complaint ? t("health.pocso.editComplaint") : t("health.pocso.addComplaint")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="complaint-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("health.pocso.complaintDate")} name="complaintDate" type="date" max={today} value={form.values.complaintDate} onChange={(e) => form.set("complaintDate", e.target.value)} error={form.errors.complaintDate} required />
          <SelectField label={t("health.pocso.category")} name="category" value={form.values.category} onChange={(e) => form.set("category", e.target.value as ComplaintCategory)} options={COMPLAINT_CATEGORIES.map((c) => ({ value: c, label: translateOr(t, `health.complaintCategory.${c}`, c) }))} />
        </div>
        <TextAreaField label={t("health.pocso.description")} name="description" value={form.values.description} onChange={(e) => form.set("description", e.target.value)} error={form.errors.description} maxLength={4000} rows={3} required hint={t("health.pocso.descriptionHint")} />
        <TextAreaField label={t("health.pocso.complainant")} name="complainantNote" value={form.values.complainantNote} onChange={(e) => form.set("complainantNote", e.target.value)} error={form.errors.complainantNote} maxLength={1000} rows={2} hint={t("health.pocso.complainantHint")} />
        <div className="grid grid-cols-2 gap-3">
          <SelectField label={t("health.pocso.status")} name="status" value={form.values.status} onChange={(e) => form.set("status", e.target.value as ComplaintStatus)} options={COMPLAINT_STATUSES.map((s) => ({ value: s, label: translateOr(t, `health.complaintStatus.${s}`, s) }))} />
        </div>
        <TextAreaField label={t("health.pocso.actions")} name="actions" value={form.values.actions} onChange={(e) => form.set("actions", e.target.value)} error={form.errors.actions} maxLength={4000} rows={2} />
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
