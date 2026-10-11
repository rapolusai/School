"use client";

import { ArrowRight, Handshake, MessagesSquare } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { addDays } from "@/components/views/reports/report-frame";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { chatApi, familyChatApi, ptmApi, staffChatApi } from "@/lib/chat-api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { WEEK_DAYS, type ChatAway, type ChatSettings, type WeekDay } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ChatInbox } from "./chat-inbox";
import { OversightView } from "./oversight-views";
import { ChatNav, daysText, hhmm, MAX_AWAY_DAYS, MAX_AWAY_NOTE_LENGTH, timeRange } from "./chat-shared";

/** /app/chat: a member of staff's conversations with parents; someone who only oversees gets the oversight list. */
export function StaffChatView({ initialId }: { initialId?: string }) {
  const { t } = useI18n();
  const { me } = useAuth();
  if (!hasPermission(me, PERMISSIONS.chatUse)) return <OversightView />;
  return (
    <>
      <PageHead eyebrow={me?.tenant?.name} title={t("chat.staff.title")} />
      <ChatNav />
      <ChatInbox client={staffChatApi} side="STAFF" initialId={initialId} cacheKey="chat:staff" />
    </>
  );
}

/** /app/family/chat: a parent's conversations with their children's teachers. */
export function FamilyChatView({ initialId }: { initialId?: string }) {
  const { t } = useI18n();
  return (
    <>
      <PageHead title={t("nav.familyChat")} />
      <ChatInbox client={familyChatApi} side="PARENT" initialId={initialId} cacheKey="chat:family" />
    </>
  );
}

/* ------------------------------------------------------------------ dashboard cards */

/** The staff dashboard: unread messages from parents and the teacher's next parent-teacher meeting. */
export function ChatDashboardCards() {
  const { t, lang } = useI18n();
  const unread = useApiData("chat:staff:unread", staffChatApi.unread);
  const meetings = useApiData("ptm:mine", ptmApi.mine);
  const next = (meetings.data ?? []).find((m) => !m.event.past && m.event.status === "SCHEDULED");
  return (
    <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2 xl:grid-cols-4">
      <section className="card flex flex-col gap-1.5" data-testid="chat-unread-card">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-[13px] font-medium text-ink-2 font-body">{t("chat.card.title")}</h2>
          <MessagesSquare size={18} className="text-ink-3" aria-hidden="true" />
        </div>
        {unread.error && !unread.data ? (
          <p className="text-[13px] text-ink-3">{t("chat.card.unavailable")}</p>
        ) : !unread.data ? (
          <div className="skeleton h-8 w-16" aria-hidden="true" />
        ) : (
          <span className="kpi-value" data-testid="chat-unread-count">
            {unread.data.unread}
          </span>
        )}
        {unread.data ? (
          <span className="text-[12.5px] font-semibold text-ink-3">
            {unread.data.unread === 0
              ? t("chat.card.allRead")
              : plural(t, "chat.card.conversations", unread.data.conversations)}
          </span>
        ) : null}
        <Link href="/app/chat" className="link mt-auto inline-flex items-center gap-1 pt-2 text-[13.5px]">
          {t("chat.card.open")}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      </section>
      {next ? (
        <section className="card flex flex-col gap-1.5" data-testid="ptm-next-card">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-[13px] font-medium text-ink-2 font-body">{t("ptm.card.next")}</h2>
            <Handshake size={18} className="text-ink-3" aria-hidden="true" />
          </div>
          <b className="font-display text-lg leading-tight">{next.event.title}</b>
          <span className="text-[13px] text-ink-2">
            {formatPlainDate(next.event.date, localeFor(lang))} · {timeRange(next.schedule.availableFrom, next.schedule.availableTo)}
          </span>
          <span className="text-[12.5px] font-semibold text-ink-3">
            {plural(t, "ptm.card.booked", next.schedule.booked, { slots: next.schedule.slots.length })}
          </span>
          <Link
            href={`/app/ptm/${encodeURIComponent(next.event.id)}`}
            className="link mt-auto inline-flex items-center gap-1 pt-2 text-[13.5px]"
          >
            {t("ptm.card.schedule")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </section>
      ) : null}
    </div>
  );
}

/** The family home: unread messages from teachers, with the way to messages and meeting bookings. */
export function FamilyMessagesCard() {
  const { t } = useI18n();
  const unread = useApiData("chat:family:unread", familyChatApi.unread);
  const count = unread.data?.unread ?? 0;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-messages-card" data-testid="family-messages-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="family-messages-card" className="flex items-center gap-2">
          {t("nav.familyChat")}
          {count > 0 ? (
            <span className="chat-badge" data-testid="family-unread-badge" aria-label={plural(t, "chat.unread", count)}>
              {count}
            </span>
          ) : null}
        </h2>
        <MessagesSquare size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {unread.error && !unread.data ? (
        <p className="text-sm text-ink-2">{t("chat.card.unavailable")}</p>
      ) : !unread.data ? (
        <div className="skeleton h-5 w-40" aria-hidden="true" />
      ) : (
        <p className="text-sm text-ink-2">
          {count === 0 ? t("chat.family.noUnread") : plural(t, "chat.family.unread", count)}
        </p>
      )}
      <div className="mt-auto flex flex-wrap gap-x-5 gap-y-1">
        <Link href="/app/family/chat" className="link inline-flex items-center gap-1 text-[13.5px]">
          {t("chat.family.open")}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
        <Link href="/app/family/ptm" className="link inline-flex items-center gap-1 text-[13.5px]">
          {t("ptm.family.book")}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ settings */

/** /app/chat/settings: the teacher's "away" note, and the school's hours for messages (chat.oversee changes them). */
export function ChatSettingsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const canUse = hasPermission(me, PERMISSIONS.chatUse);
  return (
    <>
      <PageHead eyebrow={t("chat.staff.title")} title={t("chat.settings.title")} />
      <ChatNav />
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        {canUse ? <AwayCard /> : null}
        <HoursCard canEdit={hasPermission(me, PERMISSIONS.chatOversee)} />
      </div>
    </>
  );
}

/** Problems with an away form, as message keys by field. Pure, exported for tests. */
export function awayProblems(
  returnsOn: string,
  note: string,
  today: string,
): Partial<Record<"returnsOn" | "note", MessageKey>> {
  const problems: Partial<Record<"returnsOn" | "note", MessageKey>> = {};
  if (!/^\d{4}-\d{2}-\d{2}$/.test(returnsOn)) problems.returnsOn = "chat.v.returnsOn";
  else if (returnsOn <= today || returnsOn > addDays(today, MAX_AWAY_DAYS)) problems.returnsOn = "chat.v.returnsOnRange";
  if (note.length > MAX_AWAY_NOTE_LENGTH) problems.note = "chat.v.noteLong";
  return problems;
}

function AwayCard() {
  const { t } = useI18n();
  const away = useApiData("chat:away", chatApi.away);
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="chat-away" data-testid="chat-away-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="chat-away">{t("chat.awayCard.title")}</h2>
      </div>
      {away.error && !away.data ? (
        <ErrorState error={away.error} onRetry={away.reload} />
      ) : !away.data ? (
        <LoadingRows rows={3} />
      ) : (
        <AwayForm key={`${away.data.returnsOn ?? ""}:${away.data.note ?? ""}`} away={away.data} onSaved={away.reload} />
      )}
    </section>
  );
}

function AwayForm({ away, onSaved }: { away: ChatAway; onSaved: () => void }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const [today] = useState(() => todayInIndia());
  const [returnsOn, setReturnsOn] = useState(away.returnsOn ?? "");
  const [note, setNote] = useState(away.note ?? "");
  const [errors, setErrors] = useState<Partial<Record<string, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const save = async (body: { returnsOn: string | null; note: string | null }) => {
    setBusy(true);
    setFormError(null);
    try {
      await chatApi.setAway(body);
      toast(body.returnsOn ? t("chat.awayCard.saved") : t("chat.awayCard.cleared"));
      onSaved();
    } catch (caught) {
      const failure = toApiError(caught);
      if (failure.errors) setErrors(failure.errors);
      setFormError(errorMessage(failure, t));
    } finally {
      setBusy(false);
    }
  };

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems = awayProblems(returnsOn, note, today);
    if (Object.keys(problems).length) {
      setErrors(
        Object.fromEntries(Object.entries(problems).map(([field, key]) => [field, t(key as MessageKey, { max: MAX_AWAY_DAYS })])),
      );
      return;
    }
    setErrors({});
    void save({ returnsOn, note: note.trim() || null });
  };

  return (
    <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate>
      {away.away && away.returnsOn ? (
        <div className="alert alert-warn">
          <span>{t("chat.awayCard.now", { date: formatPlainDate(away.returnsOn, localeFor(lang)) })}</span>
        </div>
      ) : (
        <p className="text-[13.5px] text-ink-2">{t("chat.awayCard.intro")}</p>
      )}
      <FormAlert message={formError} />
      <TextField
        label={t("chat.awayCard.returnsOn")}
        type="date"
        name="returnsOn"
        value={returnsOn}
        min={addDays(today, 1)}
        max={addDays(today, MAX_AWAY_DAYS)}
        error={errors.returnsOn}
        onChange={(e) => setReturnsOn(e.target.value)}
      />
      <TextAreaField
        label={t("chat.awayCard.note")}
        hint={t("chat.awayCard.noteHint")}
        name="note"
        rows={3}
        value={note}
        maxLength={MAX_AWAY_NOTE_LENGTH}
        error={errors.note}
        onChange={(e) => setNote(e.target.value)}
      />
      <div className="flex flex-wrap justify-end gap-2">
        {away.away ? (
          <button type="button" className="btn" disabled={busy} onClick={() => void save({ returnsOn: null, note: null })}>
            {t("chat.awayCard.back")}
          </button>
        ) : null}
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t("common.working") : t("chat.awayCard.save")}
        </button>
      </div>
    </form>
  );
}

function HoursCard({ canEdit }: { canEdit: boolean }) {
  const { t } = useI18n();
  const settings = useApiData("chat:settings", chatApi.settings);
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="chat-hours" data-testid="chat-hours-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="chat-hours">{t("chat.hours.title")}</h2>
      </div>
      {settings.error && !settings.data ? (
        <ErrorState error={settings.error} onRetry={settings.reload} />
      ) : !settings.data ? (
        <LoadingRows rows={4} />
      ) : canEdit ? (
        <HoursForm key={JSON.stringify(settings.data)} settings={settings.data} onSaved={settings.reload} />
      ) : (
        <>
          <p className="text-[13.5px] text-ink-2">
            {t("chat.hours.current", {
              days: daysText(t, settings.data.availableDays),
              from: hhmm(settings.data.availableFrom),
              to: hhmm(settings.data.availableTo),
            })}
          </p>
          <p className="text-[12.5px] text-ink-3">{t("chat.hours.rule")}</p>
        </>
      )}
    </section>
  );
}

/** Problems with the school's hours, as message keys by field. Pure, exported for tests. */
export function hoursProblems(days: readonly WeekDay[], from: string, to: string): Partial<Record<string, MessageKey>> {
  const problems: Partial<Record<string, MessageKey>> = {};
  if (days.length === 0) problems.availableDays = "chat.v.days";
  if (!/^\d{2}:\d{2}/.test(from)) problems.availableFrom = "chat.v.time";
  if (!/^\d{2}:\d{2}/.test(to)) problems.availableTo = "chat.v.time";
  else if (hhmm(to) <= hhmm(from)) problems.availableTo = "chat.v.timeOrder";
  return problems;
}

function HoursForm({ settings, onSaved }: { settings: ChatSettings; onSaved: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [days, setDays] = useState<WeekDay[]>(settings.availableDays);
  const [from, setFrom] = useState(hhmm(settings.availableFrom));
  const [to, setTo] = useState(hhmm(settings.availableTo));
  const [notify, setNotify] = useState(settings.notifyNewMessages);
  const [errors, setErrors] = useState<Partial<Record<string, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems = hoursProblems(days, from, to);
    if (Object.keys(problems).length) {
      setErrors(Object.fromEntries(Object.entries(problems).map(([field, key]) => [field, t(key as MessageKey)])));
      return;
    }
    setErrors({});
    setBusy(true);
    setFormError(null);
    try {
      await chatApi.saveSettings({ availableDays: days, availableFrom: from, availableTo: to, notifyNewMessages: notify });
      toast(t("chat.hours.saved"));
      onSaved();
    } catch (caught) {
      const failure = toApiError(caught);
      if (failure.errors) setErrors(failure.errors);
      setFormError(errorMessage(failure, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate>
      <p className="text-[13.5px] text-ink-2">{t("chat.hours.intro")}</p>
      <FormAlert message={formError} />
      <fieldset className="field">
        <legend className="field-label">{t("chat.hours.days")}</legend>
        <div className="flex flex-wrap gap-x-4 gap-y-2">
          {WEEK_DAYS.map((day) => (
            <label key={day} className="check">
              <input
                type="checkbox"
                name="availableDays"
                value={day}
                checked={days.includes(day)}
                onChange={(e) =>
                  setDays((prev) => (e.target.checked ? [...prev, day] : prev.filter((d) => d !== day)))
                }
              />
              {t(`timetable.day.${day}` as MessageKey)}
            </label>
          ))}
        </div>
        {errors.availableDays ? <p className="field-error">{errors.availableDays}</p> : null}
      </fieldset>
      <div className="grid grid-cols-2 gap-3">
        <TextField
          label={t("chat.hours.from")}
          type="time"
          name="availableFrom"
          value={from}
          error={errors.availableFrom}
          onChange={(e) => setFrom(e.target.value)}
        />
        <TextField
          label={t("chat.hours.to")}
          type="time"
          name="availableTo"
          value={to}
          error={errors.availableTo}
          onChange={(e) => setTo(e.target.value)}
        />
      </div>
      <label className="check">
        <input type="checkbox" name="notifyNewMessages" checked={notify} onChange={(e) => setNotify(e.target.checked)} />
        {t("chat.hours.notify")}
      </label>
      <p className="text-[12.5px] text-ink-3">{t("chat.hours.rule")}</p>
      <div className="flex justify-end">
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t("common.working") : t("common.save")}
        </button>
      </div>
      {settings.ptmRemindersEnabled ? <Pill tone="info">{t("chat.hours.ptmReminders")}</Pill> : null}
    </form>
  );
}
