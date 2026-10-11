"use client";

import { Plus, Trash2 } from "lucide-react";
import { useId, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { api, toApiError } from "@/lib/api";
import { ptmApi } from "@/lib/chat-api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import type { MeetingMode, PtmBreak, PtmEventDetail, PtmEventRequest, PtmEventStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { hhmm, MAX_NOTE_LENGTH, timeRange } from "./chat-shared";

/* Parent-teacher meetings: shared labels and the form to set one up (ptm.manage). */

export const SLOT_MINUTES = [5, 10, 15, 20, 30, 45, 60] as const;
export const MAX_BREAKS = 5;
export const MAX_CUTOFF_HOURS = 72;
export const DEFAULT_CUTOFF_HOURS = 12;

/** "Sat, 24 Oct 2026 · 09:00–12:30". Pure. */
export function eventWhen(event: { date: string; startsAt: string; endsAt: string }, locale: string): string {
  return `${formatPlainDate(event.date, locale)} · ${timeRange(event.startsAt, event.endsAt)}`;
}

/** "At school: Class 5 classrooms" or "Video call". Pure. */
export function placeText(t: Translate, event: { mode: MeetingMode; location: string | null }): string {
  if (event.mode === "VIDEO") return t("ptm.mode.VIDEO");
  return event.location ? t("ptm.place", { place: event.location }) : t("ptm.mode.IN_PERSON");
}

export function EventStatusPill({ status, past }: { status: PtmEventStatus; past: boolean }) {
  const { t } = useI18n();
  if (status === "CANCELLED") return <Pill tone="bad">{t("ptm.status.cancelled")}</Pill>;
  if (past) return <Pill>{t("ptm.status.past")}</Pill>;
  return (
    <Pill tone="good" dot>
      {t("ptm.status.upcoming")}
    </Pill>
  );
}

/* ------------------------------------------------------------------ the meeting form */

export type EventFormValues = {
  title: string;
  date: string;
  startsAt: string;
  endsAt: string;
  slotMinutes: string;
  breaks: PtmBreak[];
  mode: MeetingMode;
  location: string;
  videoLink: string;
  sectionIds: string[];
  includeSubjectTeachers: boolean;
  cutoffHours: string;
  note: string;
};

export function emptyEventForm(): EventFormValues {
  return {
    title: "",
    date: "",
    startsAt: "09:00",
    endsAt: "12:00",
    slotMinutes: "10",
    breaks: [],
    mode: "IN_PERSON",
    location: "",
    videoLink: "",
    sectionIds: [],
    includeSubjectTeachers: false,
    cutoffHours: String(DEFAULT_CUTOFF_HOURS),
    note: "",
  };
}

export function eventFormFrom(event: PtmEventDetail): EventFormValues {
  return {
    title: event.title,
    date: event.date,
    startsAt: hhmm(event.startsAt),
    endsAt: hhmm(event.endsAt),
    slotMinutes: String(event.slotMinutes),
    breaks: event.breaks.map((b) => ({ from: hhmm(b.from), to: hhmm(b.to) })),
    mode: event.mode,
    location: event.location ?? "",
    videoLink: event.videoLink ?? "",
    sectionIds: event.sections.map((s) => s.id),
    includeSubjectTeachers: event.includeSubjectTeachers,
    cutoffHours: String(event.cutoffHours),
    note: event.note ?? "",
  };
}

const TIME = /^\d{2}:\d{2}$/;

/** What is wrong with the form, as message keys by field, mirroring the API. Pure, exported for tests. */
export function eventProblems(
  v: EventFormValues,
  today: string,
  originalDate?: string,
): Partial<Record<keyof EventFormValues, MessageKey>> {
  const problems: Partial<Record<keyof EventFormValues, MessageKey>> = {};
  if (!v.title.trim()) problems.title = "ptm.v.title";
  else if (v.title.trim().length > 150) problems.title = "ptm.v.titleLong";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v.date)) problems.date = "ptm.v.date";
  else if (v.date < today && v.date !== originalDate) problems.date = "ptm.v.datePast";
  if (!TIME.test(v.startsAt)) problems.startsAt = "ptm.v.time";
  if (!TIME.test(v.endsAt)) problems.endsAt = "ptm.v.time";
  else if (TIME.test(v.startsAt) && v.endsAt <= v.startsAt) problems.endsAt = "ptm.v.timeOrder";
  const minutes = Number(v.slotMinutes);
  if (!Number.isInteger(minutes) || minutes < 5 || minutes > 60) problems.slotMinutes = "ptm.v.slotMinutes";
  const sorted = [...v.breaks].sort((a, b) => a.from.localeCompare(b.from));
  if (v.breaks.length > MAX_BREAKS) problems.breaks = "ptm.v.breaks";
  else if (v.breaks.some((b) => !TIME.test(b.from) || !TIME.test(b.to) || b.to <= b.from)) problems.breaks = "ptm.v.breakOrder";
  else if (v.breaks.some((b) => b.from < v.startsAt || b.to > v.endsAt)) problems.breaks = "ptm.v.breakInside";
  else if (sorted.some((b, i) => i > 0 && b.from < sorted[i - 1].to)) problems.breaks = "ptm.v.breakOverlap";
  if (v.mode === "VIDEO" && (!v.videoLink.trim().startsWith("https://") || /\s/.test(v.videoLink.trim()))) {
    problems.videoLink = "ptm.v.videoLink";
  } else if (v.videoLink.length > 500) problems.videoLink = "ptm.v.videoLinkLong";
  if (v.location.length > 200) problems.location = "ptm.v.locationLong";
  if (v.sectionIds.length === 0) problems.sectionIds = "ptm.v.sections";
  const cutoff = Number(v.cutoffHours);
  if (v.cutoffHours.trim() === "" || !Number.isInteger(cutoff) || cutoff < 0 || cutoff > MAX_CUTOFF_HOURS) {
    problems.cutoffHours = "ptm.v.cutoff";
  }
  if (v.note.length > MAX_NOTE_LENGTH) problems.note = "ptm.v.noteLong";
  return problems;
}

export function eventRequest(v: EventFormValues): PtmEventRequest {
  return {
    title: v.title.trim(),
    date: v.date,
    startsAt: v.startsAt,
    endsAt: v.endsAt,
    slotMinutes: Number(v.slotMinutes),
    breaks: v.breaks,
    mode: v.mode,
    location: v.mode === "IN_PERSON" ? v.location.trim() || null : null,
    videoLink: v.mode === "VIDEO" ? v.videoLink.trim() : null,
    sectionIds: v.sectionIds,
    includeSubjectTeachers: v.includeSubjectTeachers,
    cutoffHours: Number(v.cutoffHours),
    note: v.note.trim() || null,
  };
}

/** Sets up a meeting, or changes one (`event`). The classes come from school setup. */
export function EventFormDialog({
  open,
  event,
  onClose,
  onSaved,
}: {
  open: boolean;
  event?: PtmEventDetail;
  onClose: () => void;
  onSaved: (event: PtmEventDetail) => void;
}) {
  const { t } = useI18n();
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={event ? t("ptm.form.editTitle") : t("ptm.form.newTitle")}
      description={t("ptm.form.intro")}
      closeLabel={t("common.close")}
    >
      {open ? <EventForm key={event?.id ?? "new"} event={event} onClose={onClose} onSaved={onSaved} /> : null}
    </Dialog>
  );
}

function EventForm({
  event,
  onClose,
  onSaved,
}: {
  event?: PtmEventDetail;
  onClose: () => void;
  onSaved: (event: PtmEventDetail) => void;
}) {
  const { t } = useI18n();
  const modeName = useId();
  const [today] = useState(() => todayInIndia());
  const classes = useApiData("ptm:classes", api.listClasses);
  const [values, setValues] = useState<EventFormValues>(() => (event ? eventFormFrom(event) : emptyEventForm()));
  const [errors, setErrors] = useState<Partial<Record<string, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const set = <K extends keyof EventFormValues>(field: K, value: EventFormValues[K]) => {
    setValues((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
  };

  const submit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const problems = eventProblems(values, today, event?.date);
    if (Object.keys(problems).length) {
      setErrors(
        Object.fromEntries(
          Object.entries(problems).map(([field, key]) => [field, t(key as MessageKey, { max: MAX_BREAKS, hours: MAX_CUTOFF_HOURS })]),
        ),
      );
      setFormError(t("validation.fixErrors"));
      const form = e.currentTarget;
      const first = Object.keys(problems)[0];
      form.querySelector<HTMLElement>(`[name="${first}"]`)?.focus();
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      const body = eventRequest(values);
      const saved = event ? await ptmApi.update(event.id, body) : await ptmApi.create(body);
      onSaved(saved);
    } catch (caught) {
      const failure = toApiError(caught);
      if (failure.errors) setErrors(failure.errors);
      setFormError(errorMessage(failure, t));
    } finally {
      setBusy(false);
    }
  };

  const toggleSection = (sectionId: string, on: boolean) =>
    set("sectionIds", on ? [...values.sectionIds, sectionId] : values.sectionIds.filter((s) => s !== sectionId));

  return (
    <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate data-testid="ptm-event-form">
      <FormAlert message={formError} />
      <TextField
        label={t("ptm.form.title")}
        name="title"
        value={values.title}
        maxLength={150}
        required
        error={errors.title}
        placeholder={t("ptm.form.titlePlaceholder")}
        onChange={(e) => set("title", e.target.value)}
        data-autofocus
      />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <TextField
          label={t("ptm.form.date")}
          type="date"
          name="date"
          value={values.date}
          min={event?.date && event.date < today ? event.date : today}
          required
          error={errors.date}
          onChange={(e) => set("date", e.target.value)}
        />
        <TextField
          label={t("ptm.form.startsAt")}
          type="time"
          name="startsAt"
          value={values.startsAt}
          required
          error={errors.startsAt}
          onChange={(e) => set("startsAt", e.target.value)}
        />
        <TextField
          label={t("ptm.form.endsAt")}
          type="time"
          name="endsAt"
          value={values.endsAt}
          required
          error={errors.endsAt}
          onChange={(e) => set("endsAt", e.target.value)}
        />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <label className="field">
          <span className="field-label">{t("ptm.form.slotMinutes")}</span>
          <select
            className="input"
            name="slotMinutes"
            value={values.slotMinutes}
            aria-invalid={errors.slotMinutes ? true : undefined}
            onChange={(e) => set("slotMinutes", e.target.value)}
          >
            {SLOT_MINUTES.map((m) => (
              <option key={m} value={String(m)}>
                {t("ptm.form.minutes", { count: m })}
              </option>
            ))}
          </select>
          {errors.slotMinutes ? <span className="field-error">{errors.slotMinutes}</span> : null}
        </label>
        <TextField
          label={t("ptm.form.cutoff")}
          hint={t("ptm.form.cutoffHint")}
          type="number"
          inputMode="numeric"
          min={0}
          max={MAX_CUTOFF_HOURS}
          name="cutoffHours"
          value={values.cutoffHours}
          error={errors.cutoffHours}
          onChange={(e) => set("cutoffHours", e.target.value)}
        />
      </div>

      <fieldset className="field">
        <legend className="field-label">{t("ptm.form.breaks")}</legend>
        {values.breaks.map((b, i) => (
          <div key={i} className="flex items-end gap-2">
            <TextField
              className="flex-1"
              label={t("ptm.form.breakFrom")}
              type="time"
              name="breaks"
              value={b.from}
              onChange={(e) => set("breaks", values.breaks.map((x, j) => (j === i ? { ...x, from: e.target.value } : x)))}
            />
            <TextField
              className="flex-1"
              label={t("ptm.form.breakTo")}
              type="time"
              name={`breakTo${i}`}
              value={b.to}
              onChange={(e) => set("breaks", values.breaks.map((x, j) => (j === i ? { ...x, to: e.target.value } : x)))}
            />
            <button
              type="button"
              className="iconbtn mb-1"
              aria-label={t("ptm.form.removeBreak")}
              onClick={() => set("breaks", values.breaks.filter((_, j) => j !== i))}
            >
              <Trash2 size={18} aria-hidden="true" />
            </button>
          </div>
        ))}
        {values.breaks.length < MAX_BREAKS ? (
          <button
            type="button"
            className="btn btn-sm self-start"
            onClick={() => set("breaks", [...values.breaks, { from: "10:30", to: "10:45" }])}
          >
            <Plus size={16} aria-hidden="true" />
            {t("ptm.form.addBreak")}
          </button>
        ) : null}
        {errors.breaks ? <p className="field-error">{errors.breaks}</p> : null}
      </fieldset>

      <fieldset className="field">
        <legend className="field-label">{t("ptm.form.mode")}</legend>
        <div className="seg self-start" role="radiogroup" aria-label={t("ptm.form.mode")}>
          {(["IN_PERSON", "VIDEO"] as const).map((mode) => (
            <label key={mode}>
              <input
                type="radio"
                name={modeName}
                value={mode}
                checked={values.mode === mode}
                onChange={() => set("mode", mode)}
              />
              {t(`ptm.mode.${mode}`)}
            </label>
          ))}
        </div>
      </fieldset>
      {values.mode === "IN_PERSON" ? (
        <TextField
          label={t("ptm.form.location")}
          name="location"
          value={values.location}
          maxLength={200}
          placeholder={t("ptm.form.locationPlaceholder")}
          error={errors.location}
          onChange={(e) => set("location", e.target.value)}
        />
      ) : (
        <TextField
          label={t("ptm.form.videoLink")}
          hint={t("ptm.form.videoLinkHint")}
          type="url"
          name="videoLink"
          value={values.videoLink}
          maxLength={500}
          placeholder="https://"
          error={errors.videoLink}
          onChange={(e) => set("videoLink", e.target.value)}
        />
      )}

      <fieldset className="field">
        <legend className="field-label">{t("ptm.form.sections")}</legend>
        {classes.error && !classes.data ? (
          <p className="field-error">{errorMessage(classes.error, t)}</p>
        ) : !classes.data ? (
          <LoadingRows rows={2} />
        ) : (
          <div className="flex max-h-56 flex-col gap-2 overflow-y-auto">
            {classes.data
              .filter((c) => c.sections.length > 0)
              .map((c) => (
                <div key={c.id} className="flex flex-wrap items-center gap-x-4 gap-y-1">
                  <b className="w-24 flex-none text-[13.5px]">{c.name}</b>
                  {c.sections.map((s) => (
                    <label key={s.id} className="check">
                      <input
                        type="checkbox"
                        name="sectionIds"
                        value={s.id}
                        checked={values.sectionIds.includes(s.id)}
                        onChange={(e) => toggleSection(s.id, e.target.checked)}
                      />
                      {`${c.name} ${s.name}`}
                    </label>
                  ))}
                </div>
              ))}
          </div>
        )}
        {errors.sectionIds ? <p className="field-error">{errors.sectionIds}</p> : null}
      </fieldset>
      <label className="check">
        <input
          type="checkbox"
          name="includeSubjectTeachers"
          checked={values.includeSubjectTeachers}
          onChange={(e) => set("includeSubjectTeachers", e.target.checked)}
        />
        {t("ptm.form.subjectTeachers")}
      </label>
      <TextAreaField
        label={t("ptm.form.note")}
        hint={t("ptm.form.noteHint")}
        name="note"
        rows={2}
        value={values.note}
        maxLength={MAX_NOTE_LENGTH}
        error={errors.note}
        onChange={(e) => set("note", e.target.value)}
      />
      <div className="flex justify-end gap-2">
        <button type="button" className="btn" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t("common.working") : event ? t("common.save") : t("ptm.form.create")}
        </button>
      </div>
    </form>
  );
}
