"use client";

import { ArrowRightLeft, Check, PhoneCall, PhoneIncoming, PhoneOutgoing, Plus, RotateCcw } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import {
  ENQUIRY_SOURCES,
  ENQUIRY_TOPICS,
  type CallDirection,
  type CallRequest,
  type CallView,
  type ConvertRequest,
  type EnquiryRequest,
  type EnquirySource,
  type EnquiryTopic,
  type EnquiryView,
  type OfficeFollowUp,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  directionLabel,
  EnquiryStatusPill,
  FrontOfficeNav,
  phoneProblem,
  sourceLabel,
  topicLabel,
  useCanManageOffice,
  when,
} from "./frontoffice-shared";

type Tab = "followUps" | "enquiries" | "calls";

export type CallValues = { direction: CallDirection; callerName: string; phone: string; about: string; outcome: string; followUpOn: string; assignedToUserId: string };
export type EnquiryValues = { source: EnquirySource; topic: EnquiryTopic; name: string; phone: string; childName: string; className: string; details: string; followUpOn: string; assignedToUserId: string };

const EMPTY_CALL: CallValues = { direction: "INCOMING", callerName: "", phone: "", about: "", outcome: "", followUpOn: "", assignedToUserId: "" };
const EMPTY_ENQUIRY: EnquiryValues = { source: "WALK_IN", topic: "ADMISSION", name: "", phone: "", childName: "", className: "", details: "", followUpOn: "", assignedToUserId: "" };
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Client-side checks mirroring CallForm on the API. Pure and exported for tests. */
export function callProblems(values: CallValues): Problems {
  const problems: Problems = {};
  if (!values.callerName.trim()) problems.callerName = "frontoffice.v.name";
  const phone = phoneProblem(values.phone);
  if (phone) problems.phone = phone;
  if (!values.about.trim()) problems.about = "frontoffice.v.about";
  if (values.followUpOn && !DATE.test(values.followUpOn)) problems.followUpOn = "frontoffice.v.date";
  return problems;
}

/** Client-side checks mirroring EnquiryForm on the API. Pure and exported for tests. */
export function enquiryProblems(values: EnquiryValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "frontoffice.v.name";
  const phone = phoneProblem(values.phone);
  if (phone) problems.phone = phone;
  if (!values.details.trim()) problems.details = "frontoffice.v.details";
  if (values.followUpOn && !DATE.test(values.followUpOn)) problems.followUpOn = "frontoffice.v.date";
  return problems;
}

/** /app/frontoffice/enquiries: the follow-up queue, the enquiry log and the call log. */
export function EnquiriesView() {
  const { t } = useI18n();
  const [tab, setTab] = useState<Tab>("followUps");
  const [today] = useState(() => todayInIndia());
  const [date, setDate] = useState(today);
  const canManage = useCanManageOffice();
  const [logging, setLogging] = useState<"call" | "enquiry" | null>(null);
  const [tick, setTick] = useState(0);
  const changed = () => setTick((n) => n + 1);
  const tabs: { key: Tab; label: string }[] = [
    { key: "followUps", label: t("frontoffice.enquiries.tab.followUps") },
    { key: "enquiries", label: t("frontoffice.enquiries.tab.enquiries") },
    { key: "calls", label: t("frontoffice.enquiries.tab.calls") },
  ];
  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.enquiries.title")}
        actions={
          canManage ? (
            <>
              <button type="button" className="btn" onClick={() => setLogging("call")} data-testid="log-call">
                <PhoneCall size={18} aria-hidden="true" />
                {t("frontoffice.calls.log")}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => setLogging("enquiry")} data-testid="log-enquiry">
                <Plus size={18} aria-hidden="true" />
                {t("frontoffice.enquiries.log")}
              </button>
            </>
          ) : undefined
        }
      />
      <FrontOfficeNav />
      <div className="tabs" role="tablist" aria-label={t("frontoffice.enquiries.title")}>
        {tabs.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key} onClick={() => setTab(item.key)} data-testid={`tab-${item.key}`}>
            {item.label}
          </button>
        ))}
      </div>
      {tab !== "followUps" ? (
        <div className="toolbar no-print">
          <label className="field w-full sm:w-auto">
            <span className="field-label">{t("frontoffice.col.date")}</span>
            <input type="date" className="input" name="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} />
          </label>
          <button type="button" className="btn btn-sm" onClick={() => setDate("")}>
            {t("frontoffice.enquiries.latest")}
          </button>
        </div>
      ) : null}
      {tab === "followUps" ? <FollowUpsPanel tick={tick} onChange={changed} /> : null}
      {tab === "enquiries" ? <EnquiriesPanel date={DATE.test(date) ? date : undefined} tick={tick} onChange={changed} /> : null}
      {tab === "calls" ? <CallsPanel date={DATE.test(date) ? date : undefined} tick={tick} onChange={changed} /> : null}
      <CallDialog open={logging === "call"} onClose={() => setLogging(null)} onSaved={() => { setLogging(null); setTab("calls"); changed(); }} />
      <EnquiryDialog open={logging === "enquiry"} onClose={() => setLogging(null)} onSaved={() => { setLogging(null); setTab("enquiries"); changed(); }} />
    </>
  );
}

/* ------------------------------------------------------------------ follow-ups */

function FollowUpsPanel({ tick, onChange }: { tick: number; onChange: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const queue = useApiData(`frontoffice:follow-ups:${tick}`, frontOfficeApi.followUps);
  const data = queue.data;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="follow-ups" data-testid="follow-ups">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="follow-ups">{t("frontoffice.followUps.title")}</h2>
        {data ? (
          <span className="flex gap-1.5">
            <Pill tone={data.dueToday > 0 ? "warn" : "neutral"}>{plural(t, "frontoffice.followUps.dueToday", data.dueToday)}</Pill>
            <Pill tone={data.overdue > 0 ? "bad" : "neutral"}>{plural(t, "frontoffice.followUps.overdue", data.overdue)}</Pill>
          </span>
        ) : null}
      </div>
      {queue.error && !data ? (
        <ErrorState error={queue.error} onRetry={queue.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : data.items.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("frontoffice.followUps.empty")}</p>
      ) : (
        <ul className="rowcards">
          {data.items.map((item) => (
            <FollowUpRow key={`${item.kind}:${item.id}`} item={item} locale={locale} onChange={onChange} />
          ))}
        </ul>
      )}
    </section>
  );
}

function FollowUpRow({ item, locale, onChange }: { item: OfficeFollowUp; locale: string; onChange: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManageOffice();
  const [closing, setClosing] = useState(false);
  const [outcome, setOutcome] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const tone = item.daysOverdue > 0 ? "bad" : item.daysOverdue === 0 ? "warn" : "info";
  const dueLabel = item.daysOverdue > 0 ? plural(t, "frontoffice.followUps.daysOverdue", item.daysOverdue) : item.daysOverdue === 0 ? t("frontoffice.followUps.today") : formatPlainDate(item.followUpOn, locale);
  const close = async () => {
    if (!outcome.trim()) {
      setError(t("frontoffice.v.outcome"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (item.kind === "CALL") await frontOfficeApi.closeCall(item.id, { outcome: outcome.trim() });
      else await frontOfficeApi.closeEnquiry(item.id, { outcome: outcome.trim() });
      toast(t("frontoffice.followUps.done", { name: item.name }));
      onChange();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className="rowcard items-start" data-testid="follow-up" data-kind={item.kind}>
      <span className="badge-ic" aria-hidden="true">
        {item.kind === "CALL" ? <PhoneCall size={18} /> : <ArrowRightLeft size={18} />}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold text-ink">{item.name}</span>
          <Pill tone="neutral">{item.kind === "CALL" ? t("frontoffice.followUps.kindCall") : t("frontoffice.followUps.kindEnquiry")}</Pill>
          <Pill tone={tone}>{dueLabel}</Pill>
        </div>
        <p className="text-[13px] text-ink-2">{item.about}</p>
        <p className="text-[12.5px] text-ink-3">
          {item.phoneMasked ? `${item.phoneMasked} · ` : ""}
          {item.assignedToName ? t("frontoffice.enquiries.assigned", { name: item.assignedToName }) : t("frontoffice.enquiries.unassigned")}
        </p>
        {error ? (
          <p className="alert alert-warn mt-2 text-[13px]" role="alert">
            {error}
          </p>
        ) : null}
        {canManage ? (
          closing ? (
            <div className="mt-2 flex flex-col gap-2">
              <TextField label={t("frontoffice.field.outcome")} name="outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)} maxLength={500} />
              <div className="flex gap-2">
                <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={close} data-testid="follow-up-done">
                  <Check size={16} aria-hidden="true" />
                  {t("frontoffice.followUps.markDone")}
                </button>
                <button type="button" className="btn btn-sm" onClick={() => setClosing(false)}>
                  {t("common.cancel")}
                </button>
              </div>
            </div>
          ) : (
            <button type="button" className="btn btn-sm mt-2" onClick={() => setClosing(true)}>
              <Check size={16} aria-hidden="true" />
              {t("frontoffice.followUps.markDone")}
            </button>
          )
        ) : null}
      </div>
    </li>
  );
}

/* ------------------------------------------------------------------ enquiries */

function EnquiriesPanel({ date, tick, onChange }: { date: string | undefined; tick: number; onChange: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const list = useApiData(`frontoffice:enquiries:${date ?? "latest"}:${tick}`, () => frontOfficeApi.enquiries(date));
  const rows = list.data ?? [];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="enquiries-list" data-testid="enquiries-list">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="enquiries-list">{date ? t("frontoffice.enquiries.onDay", { date: formatPlainDate(date, locale) }) : t("frontoffice.enquiries.latestTitle")}</h2>
      </div>
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={3} />
      ) : rows.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("frontoffice.enquiries.empty")}</p>
      ) : (
        <ul className="rowcards">
          {rows.map((row) => (
            <EnquiryRow key={row.id} enquiry={row} locale={locale} onChange={onChange} />
          ))}
        </ul>
      )}
    </section>
  );
}

function EnquiryRow({ enquiry, locale, onChange }: { enquiry: EnquiryView; locale: string; onChange: () => void }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = useCanManageOffice();
  const canConvert = canManage && hasPermission(me, PERMISSIONS.admissionsManage);
  const [closing, setClosing] = useState(false);
  const [outcome, setOutcome] = useState("");
  const [converting, setConverting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      toast(done);
      onChange();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className="rowcard items-start" data-testid="enquiry-row" data-status={enquiry.status}>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold text-ink">{enquiry.name}</span>
          <Pill tone="neutral">{sourceLabel(t, enquiry.source)}</Pill>
          <Pill tone="info">{topicLabel(t, enquiry.topic)}</Pill>
          <EnquiryStatusPill status={enquiry.status} />
        </div>
        {enquiry.childName ? (
          <p className="text-[13px] text-ink-2">{t("frontoffice.enquiries.child", { name: enquiry.childName, className: enquiry.className ?? "—" })}</p>
        ) : null}
        <p className="text-[13px] text-ink-2">{enquiry.details}</p>
        {enquiry.outcome ? <p className="text-[13px] text-ink-2">{t("frontoffice.enquiries.outcome", { text: enquiry.outcome })}</p> : null}
        <p className="text-[12.5px] text-ink-3">
          {enquiry.phoneMasked ? `${enquiry.phoneMasked} · ` : ""}
          {when(enquiry.enquiredAt, locale)}
          {enquiry.followUpOn && enquiry.status === "FOLLOW_UP" ? ` · ${t("frontoffice.enquiries.followUpOn", { date: formatPlainDate(enquiry.followUpOn, locale) })}` : ""}
          {enquiry.assignedToName ? ` · ${t("frontoffice.enquiries.assigned", { name: enquiry.assignedToName })}` : ""}
          {enquiry.applicationId ? (
            <>
              {" · "}
              <Link href={`/app/admissions/${encodeURIComponent(enquiry.applicationId)}`} className="link">
                {t("frontoffice.enquiries.openApplication")}
              </Link>
            </>
          ) : null}
        </p>
        {error ? (
          <p className="alert alert-warn mt-2 text-[13px]" role="alert">
            {error}
          </p>
        ) : null}
        {canManage ? (
          <div className="mt-2 flex flex-col gap-2">
            {closing ? (
              <>
                <TextField label={t("frontoffice.field.outcome")} name="outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)} maxLength={500} />
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="btn btn-sm btn-primary"
                    disabled={busy}
                    onClick={() => {
                      if (!outcome.trim()) {
                        setError(t("frontoffice.v.outcome"));
                        return;
                      }
                      void run(() => frontOfficeApi.closeEnquiry(enquiry.id, { outcome: outcome.trim() }), t("frontoffice.enquiries.closed"));
                    }}
                  >
                    {t("frontoffice.enquiries.close")}
                  </button>
                  <button type="button" className="btn btn-sm" onClick={() => setClosing(false)}>
                    {t("common.cancel")}
                  </button>
                </div>
              </>
            ) : (
              <div className="flex flex-wrap gap-2">
                {enquiry.status === "OPEN" || enquiry.status === "FOLLOW_UP" ? (
                  <button type="button" className="btn btn-sm" onClick={() => setClosing(true)}>
                    <Check size={16} aria-hidden="true" />
                    {t("frontoffice.enquiries.close")}
                  </button>
                ) : null}
                {enquiry.status === "CLOSED" ? (
                  <button type="button" className="btn btn-sm" disabled={busy} onClick={() => run(() => frontOfficeApi.reopenEnquiry(enquiry.id, null), t("frontoffice.enquiries.reopened"))}>
                    <RotateCcw size={16} aria-hidden="true" />
                    {t("frontoffice.enquiries.reopen")}
                  </button>
                ) : null}
                {canConvert && enquiry.topic === "ADMISSION" && enquiry.status !== "CONVERTED" ? (
                  <button type="button" className="btn btn-sm btn-primary" onClick={() => setConverting(true)} data-testid="enquiry-convert">
                    <ArrowRightLeft size={16} aria-hidden="true" />
                    {t("frontoffice.enquiries.convert")}
                  </button>
                ) : null}
              </div>
            )}
          </div>
        ) : null}
      </div>
      <ConvertDialog
        enquiry={enquiry}
        open={converting}
        onClose={() => setConverting(false)}
        onSaved={() => {
          setConverting(false);
          toast(t("frontoffice.enquiries.converted"));
          onChange();
        }}
      />
    </li>
  );
}

type ConvertValues = { classId: string; academicYearId: string; dateOfBirth: string; firstName: string; lastName: string; relation: "" | "FATHER" | "MOTHER" | "GUARDIAN"; phone: string };

/** Turns an admission enquiry into an application: the class, the year, the child's date of birth. */
function ConvertDialog({ enquiry, open, onClose, onSaved }: { enquiry: EnquiryView; open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const classes = useApiData(open ? "frontoffice:classes" : null, api.listClasses);
  const years = useApiData(open ? "frontoffice:years" : null, api.listYears);
  const [firstName, ...rest] = (enquiry.childName ?? "").trim().split(/\s+/);
  const form = useForm<ConvertValues>({ classId: "", academicYearId: "", dateOfBirth: "", firstName: firstName ?? "", lastName: rest.join(" "), relation: "", phone: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const yearId = form.values.academicYearId || years.data?.find((y) => y.current)?.id || "";
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const v = form.values;
    const problems: Problems = {};
    if (!v.classId) problems.classId = "frontoffice.v.class";
    if (!yearId) problems.academicYearId = "frontoffice.v.year";
    if (!DATE.test(v.dateOfBirth)) problems.dateOfBirth = "frontoffice.v.dob";
    if (!v.firstName.trim()) problems.firstName = "frontoffice.v.firstName";
    const phone = phoneProblem(v.phone, !enquiry.phoneMasked);
    if (phone) problems.phone = phone;
    if (!form.check(problems, t, formEl)) return;
    const body: ConvertRequest = {
      classId: v.classId,
      academicYearId: yearId,
      dateOfBirth: v.dateOfBirth,
      firstName: v.firstName.trim(),
      lastName: v.lastName.trim() || null,
      relation: v.relation || null,
      phone: v.phone.trim() || null,
    };
    const ok = await form.submit(t, async () => {
      await frontOfficeApi.convert(enquiry.id, body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={t("frontoffice.enquiries.convert")} description={t("frontoffice.enquiries.convertIntro", { name: enquiry.name })} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="convert-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField label={t("frontoffice.convert.firstName")} name="firstName" value={form.values.firstName} onChange={(e) => form.set("firstName", e.target.value)} error={form.errors.firstName} maxLength={100} required />
          <TextField label={t("frontoffice.convert.lastName")} name="lastName" value={form.values.lastName} onChange={(e) => form.set("lastName", e.target.value)} error={form.errors.lastName} maxLength={100} />
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <SelectField
            label={t("frontoffice.convert.class")}
            name="classId"
            value={form.values.classId}
            onChange={(e) => form.set("classId", e.target.value)}
            options={[{ value: "", label: t("frontoffice.convert.pickClass") }, ...(classes.data ?? []).map((c) => ({ value: c.id, label: c.name }))]}
            error={form.errors.classId}
            required
          />
          <SelectField
            label={t("frontoffice.convert.year")}
            name="academicYearId"
            value={yearId}
            onChange={(e) => form.set("academicYearId", e.target.value)}
            options={(years.data ?? []).map((y) => ({ value: y.id, label: y.name }))}
            error={form.errors.academicYearId}
            required
          />
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <TextField label={t("frontoffice.convert.dob")} name="dateOfBirth" type="date" value={form.values.dateOfBirth} onChange={(e) => form.set("dateOfBirth", e.target.value)} error={form.errors.dateOfBirth} required />
          <SelectField
            label={t("frontoffice.convert.relation")}
            name="relation"
            value={form.values.relation}
            onChange={(e) => form.set("relation", e.target.value as ConvertValues["relation"])}
            options={[
              { value: "", label: t("frontoffice.convert.relationGuardian") },
              { value: "MOTHER", label: t("frontoffice.convert.relationMother") },
              { value: "FATHER", label: t("frontoffice.convert.relationFather") },
            ]}
          />
          <TextField label={t("frontoffice.field.phone")} name="phone" type="tel" inputMode="numeric" value={form.values.phone} onChange={(e) => form.set("phone", e.target.value)} error={form.errors.phone} hint={enquiry.phoneMasked ? t("frontoffice.convert.phoneKept", { phone: enquiry.phoneMasked }) : undefined} maxLength={20} />
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="convert-save">
            {t("frontoffice.enquiries.convertSave")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ calls */

function CallsPanel({ date, tick, onChange }: { date: string | undefined; tick: number; onChange: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageOffice();
  const list = useApiData(`frontoffice:calls:${date ?? "latest"}:${tick}`, () => frontOfficeApi.calls(date));
  const rows = list.data ?? [];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="calls-list" data-testid="calls-list">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="calls-list">{date ? t("frontoffice.calls.onDay", { date: formatPlainDate(date, locale) }) : t("frontoffice.calls.latestTitle")}</h2>
      </div>
      {list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <LoadingRows rows={3} />
      ) : rows.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("frontoffice.calls.empty")}</p>
      ) : (
        <ul className="rowcards">
          {rows.map((call: CallView) => (
            <li key={call.id} className="rowcard items-start" data-testid="call-row">
              <span className="badge-ic" aria-hidden="true">
                {call.direction === "INCOMING" ? <PhoneIncoming size={18} /> : <PhoneOutgoing size={18} />}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold text-ink">{call.callerName}</span>
                  <Pill tone="neutral">{directionLabel(t, call.direction)}</Pill>
                  {call.followUpOn ? <Pill tone={call.status === "DONE" ? "good" : "warn"}>{call.status === "DONE" ? t("frontoffice.calls.done") : t("frontoffice.enquiries.followUpOn", { date: formatPlainDate(call.followUpOn, locale) })}</Pill> : null}
                </div>
                <p className="text-[13px] text-ink-2">{call.about}</p>
                {call.outcome ? <p className="text-[13px] text-ink-2">{t("frontoffice.enquiries.outcome", { text: call.outcome })}</p> : null}
                <p className="text-[12.5px] text-ink-3">
                  {call.phoneMasked ? `${call.phoneMasked} · ` : ""}
                  {when(call.calledAt, locale)}
                  {call.assignedToName ? ` · ${t("frontoffice.enquiries.assigned", { name: call.assignedToName })}` : ""}
                  {call.loggedByName ? ` · ${t("frontoffice.loggedBy", { name: call.loggedByName })}` : ""}
                </p>
                {canManage && call.followUpOn && call.status === "OPEN" ? (
                  <button
                    type="button"
                    className="btn btn-sm mt-2"
                    onClick={async () => {
                      try {
                        await frontOfficeApi.closeCall(call.id, { outcome: null });
                        toast(t("frontoffice.followUps.done", { name: call.callerName }));
                        onChange();
                      } catch (caught) {
                        toast(errorMessage(toApiError(caught), t));
                      }
                    }}
                  >
                    <Check size={16} aria-hidden="true" />
                    {t("frontoffice.followUps.markDone")}
                  </button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The staff a follow-up can be assigned to. */
function AssigneeField({ value, onChange, error }: { value: string; onChange: (value: string) => void; error?: string }) {
  const { t } = useI18n();
  const staff = useApiData("frontoffice:staff", frontOfficeApi.staff);
  return (
    <SelectField
      label={t("frontoffice.field.assignedTo")}
      name="assignedToUserId"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      options={[{ value: "", label: t("frontoffice.enquiries.unassigned") }, ...(staff.data ?? []).map((s) => ({ value: s.id, label: s.designation ? `${s.name} · ${s.designation}` : s.name }))]}
      error={error}
    />
  );
}

function CallDialog({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<CallValues>(EMPTY_CALL);
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const close = () => {
    form.reset(EMPTY_CALL);
    onClose();
  };
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(callProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: CallRequest = {
      direction: v.direction,
      callerName: v.callerName.trim(),
      phone: v.phone.trim() || null,
      about: v.about.trim(),
      outcome: v.outcome.trim() || null,
      followUpOn: v.followUpOn || null,
      assignedToUserId: v.assignedToUserId || null,
      calledAt: null,
    };
    const ok = await form.submit(t, async () => {
      await frontOfficeApi.logCall(body);
    });
    if (ok) {
      form.reset(EMPTY_CALL);
      onSaved();
    }
  };
  return (
    <Dialog open={open} onClose={close} title={t("frontoffice.calls.log")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="call-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <SelectField
            label={t("frontoffice.calls.direction")}
            name="direction"
            value={form.values.direction}
            onChange={(e) => form.set("direction", e.target.value as CallDirection)}
            options={[
              { value: "INCOMING", label: directionLabel(t, "INCOMING") },
              { value: "OUTGOING", label: directionLabel(t, "OUTGOING") },
            ]}
          />
          <TextField label={t("frontoffice.calls.caller")} name="callerName" value={form.values.callerName} onChange={(e) => form.set("callerName", e.target.value)} error={form.errors.callerName} maxLength={200} required />
        </div>
        <TextField label={t("frontoffice.field.phone")} name="phone" type="tel" inputMode="numeric" value={form.values.phone} onChange={(e) => form.set("phone", e.target.value)} error={form.errors.phone} maxLength={20} />
        <TextAreaField label={t("frontoffice.calls.about")} name="about" value={form.values.about} onChange={(e) => form.set("about", e.target.value)} error={form.errors.about} maxLength={500} rows={2} required />
        <TextField label={t("frontoffice.field.outcome")} name="outcome" value={form.values.outcome} onChange={(e) => form.set("outcome", e.target.value)} error={form.errors.outcome} maxLength={500} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField label={t("frontoffice.field.followUpOn")} name="followUpOn" type="date" value={form.values.followUpOn} onChange={(e) => form.set("followUpOn", e.target.value)} error={form.errors.followUpOn} />
          <AssigneeField value={form.values.assignedToUserId} onChange={(v) => form.set("assignedToUserId", v)} error={form.errors.assignedToUserId} />
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="call-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function EnquiryDialog({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<EnquiryValues>(EMPTY_ENQUIRY);
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const close = () => {
    form.reset(EMPTY_ENQUIRY);
    onClose();
  };
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(enquiryProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: EnquiryRequest = {
      source: v.source,
      topic: v.topic,
      name: v.name.trim(),
      phone: v.phone.trim() || null,
      childName: v.childName.trim() || null,
      className: v.className.trim() || null,
      details: v.details.trim(),
      followUpOn: v.followUpOn || null,
      assignedToUserId: v.assignedToUserId || null,
      enquiredAt: null,
    };
    const ok = await form.submit(t, async () => {
      await frontOfficeApi.logEnquiry(body);
    });
    if (ok) {
      form.reset(EMPTY_ENQUIRY);
      onSaved();
    }
  };
  return (
    <Dialog open={open} onClose={close} title={t("frontoffice.enquiries.log")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="enquiry-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <SelectField label={t("frontoffice.enquiries.source")} name="source" value={form.values.source} onChange={(e) => form.set("source", e.target.value as EnquirySource)} options={ENQUIRY_SOURCES.map((s) => ({ value: s, label: sourceLabel(t, s) }))} />
          <SelectField label={t("frontoffice.enquiries.topic")} name="topic" value={form.values.topic} onChange={(e) => form.set("topic", e.target.value as EnquiryTopic)} options={ENQUIRY_TOPICS.map((s) => ({ value: s, label: topicLabel(t, s) }))} />
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField label={t("frontoffice.enquiries.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={200} required />
          <TextField label={t("frontoffice.field.phone")} name="phone" type="tel" inputMode="numeric" value={form.values.phone} onChange={(e) => form.set("phone", e.target.value)} error={form.errors.phone} maxLength={20} />
        </div>
        {form.values.topic === "ADMISSION" ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <TextField label={t("frontoffice.enquiries.childName")} name="childName" value={form.values.childName} onChange={(e) => form.set("childName", e.target.value)} error={form.errors.childName} maxLength={200} />
            <TextField label={t("frontoffice.enquiries.className")} name="className" value={form.values.className} onChange={(e) => form.set("className", e.target.value)} error={form.errors.className} maxLength={40} />
          </div>
        ) : null}
        <TextAreaField label={t("frontoffice.enquiries.details")} name="details" value={form.values.details} onChange={(e) => form.set("details", e.target.value)} error={form.errors.details} maxLength={2000} rows={3} required />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField label={t("frontoffice.field.followUpOn")} name="followUpOn" type="date" value={form.values.followUpOn} onChange={(e) => form.set("followUpOn", e.target.value)} error={form.errors.followUpOn} />
          <AssigneeField value={form.values.assignedToUserId} onChange={(v) => form.set("assignedToUserId", v)} error={form.errors.assignedToUserId} />
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="enquiry-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
