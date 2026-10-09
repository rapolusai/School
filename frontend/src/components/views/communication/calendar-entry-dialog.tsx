"use client";

import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { calendarApi } from "@/lib/communication-api";
import { useI18n } from "@/lib/i18n";
import {
  CALENDAR_AUDIENCES,
  ENTRY_KINDS,
  REMINDER_CHANNELS,
  type CalendarAudience,
  type CalendarEntry,
  type EntryKind,
  type EntryRequest,
  type NamedRef,
  type PlainDate,
  type ReminderChannel,
} from "@/lib/types";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate, isWholeNumberInRange } from "@/lib/validation";
import {
  channelLabel,
  daysBetween,
  kindLabel,
  MAX_DESCRIPTION,
  MAX_SPAN_DAYS,
  MAX_TITLE,
  shortTime,
} from "./communication-labels";

export type EntryValues = {
  kind: EntryKind;
  title: string;
  description: string;
  startsOn: string;
  endsOn: string;
  allDay: boolean;
  startTime: string;
  endTime: string;
  audience: CalendarAudience;
  classIds: string[];
  remind: boolean;
  reminderDays: string;
  reminderChannels: ReminderChannel[];
};

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

export function entryValues(entry: CalendarEntry | null, date: PlainDate): EntryValues {
  if (!entry) {
    return {
      kind: "EVENT",
      title: "",
      description: "",
      startsOn: date,
      endsOn: "",
      allDay: true,
      startTime: "",
      endTime: "",
      audience: "SCHOOL",
      classIds: [],
      remind: false,
      reminderDays: "2",
      reminderChannels: [],
    };
  }
  return {
    kind: entry.kind,
    title: entry.title,
    description: entry.description ?? "",
    startsOn: entry.startsOn,
    endsOn: entry.endsOn === entry.startsOn ? "" : entry.endsOn,
    allDay: !entry.startTime,
    startTime: shortTime(entry.startTime),
    endTime: shortTime(entry.endTime),
    audience: entry.audience,
    classIds: entry.classes.map((c) => c.id),
    remind: entry.reminderDays !== null,
    reminderDays: entry.reminderDays !== null ? String(entry.reminderDays) : "2",
    reminderChannels: entry.reminderChannels,
  };
}

/** The same checks as the API (docs/api/phase-1-communication.md, calendar). Exported for tests. */
export function validateEntry(values: EntryValues): Problems {
  const problems: Problems = {};
  const title = values.title.trim();
  if (!title) problems.title = "validation.required";
  else if (title.length > MAX_TITLE) problems.title = "validation.tooLong";
  if (values.description.trim().length > MAX_DESCRIPTION) problems.description = "validation.tooLong";
  if (!isPlainDate(values.startsOn)) problems.startsOn = "validation.date";
  if (values.endsOn) {
    if (!isPlainDate(values.endsOn)) problems.endsOn = "validation.date";
    else if (!problems.startsOn && values.endsOn < values.startsOn) problems.endsOn = "calendar.v.endBeforeStart";
    else if (!problems.startsOn && daysBetween(values.startsOn, values.endsOn) > MAX_SPAN_DAYS)
      problems.endsOn = "calendar.v.tooLong";
  }
  if (!values.allDay) {
    if (!TIME.test(values.startTime)) problems.startTime = "calendar.v.time";
    if (values.endTime && !TIME.test(values.endTime)) problems.endTime = "calendar.v.time";
    const oneDay = !values.endsOn || values.endsOn === values.startsOn;
    if (!problems.startTime && !problems.endTime && values.endTime && oneDay && values.endTime <= values.startTime) {
      problems.endTime = "calendar.v.endTime";
    }
  }
  if (values.audience === "CLASSES" && values.classIds.length === 0) problems.classIds = "calendar.v.classes";
  if (values.remind && !isWholeNumberInRange(values.reminderDays, 1, 30)) problems.reminderDays = "calendar.v.reminder";
  return problems;
}

export function toEntryRequest(values: EntryValues): EntryRequest {
  return {
    kind: values.kind,
    title: values.title.trim(),
    description: values.description.trim() || null,
    startsOn: values.startsOn,
    endsOn: values.endsOn || null,
    startTime: values.allDay ? null : values.startTime,
    endTime: values.allDay || !values.endTime ? null : values.endTime,
    audience: values.audience,
    classIds: values.audience === "CLASSES" ? values.classIds : [],
    reminderDays: values.remind ? Number(values.reminderDays) : null,
    reminderChannels: values.remind ? values.reminderChannels : [],
  };
}

/** Add or change a calendar entry (calendar.manage). Mount with a key per entry so the form starts fresh. */
export function CalendarEntryDialog({
  open,
  entry,
  date,
  classes,
  onClose,
  onSaved,
}: {
  open: boolean;
  entry: CalendarEntry | null;
  date: PlainDate;
  classes: NamedRef[];
  onClose: () => void;
  onSaved: (entry: CalendarEntry) => void;
}) {
  const { t } = useI18n();
  const form = useForm<EntryValues>(entryValues(entry, date));
  const { values, errors, set } = form;

  const toggle = <K extends "classIds" | "reminderChannels">(key: K, value: string, on: boolean) => {
    const list = values[key] as string[];
    set(key, (on ? [...list, value] : list.filter((v) => v !== value)) as EntryValues[K]);
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateEntry(values), t, event.currentTarget)) return;
    await form.submit(t, async () => {
      const request = toEntryRequest(values);
      const saved = entry ? await calendarApi.update(entry.id, request) : await calendarApi.create(request);
      onSaved(saved);
    });
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={entry ? t("calendar.entry.editTitle") : t("calendar.entry.addTitle")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="grid2">
          <SelectField
            label={t("calendar.field.kind")}
            name="kind"
            value={values.kind}
            onChange={(e) => set("kind", e.target.value as EntryKind)}
            options={ENTRY_KINDS.map((k) => ({ value: k, label: kindLabel(t, k) }))}
            error={errors.kind}
          />
          <TextField
            label={t("calendar.field.title")}
            name="title"
            value={values.title}
            onChange={(e) => set("title", e.target.value)}
            error={errors.title}
            maxLength={MAX_TITLE}
            autoComplete="off"
            required
            data-autofocus
          />
        </div>
        <div className="grid2">
          <TextField
            label={t("calendar.field.startsOn")}
            name="startsOn"
            type="date"
            value={values.startsOn}
            onChange={(e) => set("startsOn", e.target.value)}
            error={errors.startsOn}
            required
          />
          <TextField
            label={t("calendar.field.endsOn")}
            name="endsOn"
            type="date"
            value={values.endsOn}
            min={values.startsOn || undefined}
            onChange={(e) => set("endsOn", e.target.value)}
            error={errors.endsOn}
            hint={t("calendar.field.endsOn.hint")}
          />
        </div>
        <label className="check self-start">
          <input
            type="checkbox"
            name="allDay"
            checked={values.allDay}
            onChange={(e) => set("allDay", e.target.checked)}
          />
          <span>{t("calendar.field.allDay")}</span>
        </label>
        {values.allDay ? null : (
          <div className="grid2">
            <TextField
              label={t("calendar.field.startTime")}
              name="startTime"
              type="time"
              value={values.startTime}
              onChange={(e) => set("startTime", e.target.value)}
              error={errors.startTime}
              required
            />
            <TextField
              label={t("calendar.field.endTime")}
              name="endTime"
              type="time"
              value={values.endTime}
              onChange={(e) => set("endTime", e.target.value)}
              error={errors.endTime}
            />
          </div>
        )}
        <fieldset className="fieldset">
          <legend>{t("calendar.field.audience")}</legend>
          <div className="seg self-start" role="radiogroup" aria-label={t("calendar.field.audience")}>
            {CALENDAR_AUDIENCES.map((a) => (
              <label key={a}>
                <input
                  type="radio"
                  name="audience"
                  value={a}
                  checked={values.audience === a}
                  onChange={() => set("audience", a)}
                />
                {t(`calendar.audience.${a}`)}
              </label>
            ))}
          </div>
          {values.audience === "CLASSES" ? (
            classes.length === 0 ? (
              <p className="text-[13px] text-ink-3">{t("notices.audience.noClasses")}</p>
            ) : (
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3" role="group" aria-label={t("calendar.field.classes")}>
                {classes.map((c) => (
                  <label key={c.id} className="check">
                    <input
                      type="checkbox"
                      name="classIds"
                      value={c.id}
                      checked={values.classIds.includes(c.id)}
                      onChange={(e) => toggle("classIds", c.id, e.target.checked)}
                    />
                    <span>{c.name}</span>
                  </label>
                ))}
              </div>
            )
          ) : null}
          {errors.classIds ? <p className="field-error">{errors.classIds}</p> : null}
          <p className="field-hint">{t(`calendar.audience.${values.audience}.hint`)}</p>
        </fieldset>
        <TextAreaField
          label={t("calendar.field.description")}
          name="description"
          rows={3}
          value={values.description}
          onChange={(e) => set("description", e.target.value)}
          error={errors.description}
          maxLength={MAX_DESCRIPTION}
        />
        <fieldset className="fieldset">
          <legend>{t("calendar.field.reminder")}</legend>
          <label className="check self-start">
            <input
              type="checkbox"
              name="remind"
              checked={values.remind}
              onChange={(e) => set("remind", e.target.checked)}
            />
            <span>{t("calendar.field.remind")}</span>
          </label>
          {values.remind ? (
            <>
              <TextField
                label={t("calendar.field.reminderDays")}
                name="reminderDays"
                type="number"
                inputMode="numeric"
                min={1}
                max={30}
                className="max-w-[160px]"
                value={values.reminderDays}
                onChange={(e) => set("reminderDays", e.target.value)}
                error={errors.reminderDays}
              />
              <div className="flex flex-wrap gap-2" role="group" aria-label={t("calendar.field.reminderChannels")}>
                {REMINDER_CHANNELS.map((c) => (
                  <label key={c} className="check">
                    <input
                      type="checkbox"
                      name="reminderChannels"
                      value={c}
                      checked={values.reminderChannels.includes(c)}
                      onChange={(e) => toggle("reminderChannels", c, e.target.checked)}
                    />
                    <span>{channelLabel(t, c)}</span>
                  </label>
                ))}
              </div>
              <p className="field-hint">{t("calendar.field.reminder.hint")}</p>
            </>
          ) : null}
        </fieldset>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
