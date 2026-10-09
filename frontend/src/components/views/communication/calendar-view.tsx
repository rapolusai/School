"use client";

import { CalendarPlus, ChevronLeft, ChevronRight, Download, ListChecks, Pencil, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { saveTextFile } from "@/lib/attendance-api";
import { calendarApi } from "@/lib/communication-api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { ENTRY_KINDS, type CalendarEntry, type PlainDate } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { CalendarEntryDialog } from "./calendar-entry-dialog";
import {
  addMonths,
  channelLabel,
  entriesByDay,
  entryAudience,
  entryWhen,
  formatClock,
  isSchoolHoliday,
  kindLabel,
  monthGridDays,
} from "./communication-labels";
import { HolidaySuggestionsDialog } from "./holiday-suggestions-dialog";

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
/** Entries shown in a day cell before "+N more". */
export const CELL_ENTRIES = 3;

type Editing = { entry: CalendarEntry | null; date: PlainDate; nonce: number };

/** The school calendar: a month grid (an agenda list on phones), with add, edit and the holiday starter list. */
export function CalendarView({ initialMonth }: { initialMonth?: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [today] = useState(() => todayInIndia());
  const [month, setMonth] = useState(() => (initialMonth && MONTH.test(initialMonth) ? initialMonth : today.slice(0, 7)));
  const [viewing, setViewing] = useState<CalendarEntry | null>(null);
  const [dayOpen, setDayOpen] = useState<PlainDate | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [deleting, setDeleting] = useState<CalendarEntry | null>(null);
  const [starterOpen, setStarterOpen] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const days = useMemo(() => monthGridDays(month), [month]);
  const from = days[0];
  const to = days[days.length - 1];
  const list = useApiData(`calendar:${from}:${to}`, () => calendarApi.entries(from, to));
  const data = list.data && list.data.from === from && list.data.to === to ? list.data : undefined;
  const byDay = useMemo(() => entriesByDay(data?.entries ?? [], days), [data, days]);
  const canManage = Boolean(data?.canManage);
  const monthDays = days.filter((d) => d.startsWith(month));
  const agenda = monthDays.filter((d) => (byDay.get(d)?.length ?? 0) > 0);

  const monthTitle = new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(
    new Date(`${month}-01T00:00:00Z`),
  );
  const weekdays = Array.from({ length: 7 }, (_, i) =>
    new Intl.DateTimeFormat(locale, { weekday: "short", timeZone: "UTC" }).format(new Date(Date.UTC(2026, 9, 5 + i))),
  );
  const holidaysThisMonth = monthDays.filter((d) => byDay.get(d)?.some(isSchoolHoliday)).length;

  const openAdd = (date: PlainDate) => setEditing({ entry: null, date, nonce: Date.now() });

  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const text = await calendarApi.ics();
      saveTextFile("school-calendar.ics", text, "text/calendar;charset=utf-8");
    } catch (caught) {
      setDownloadError(errorMessage(toApiError(caught), t));
    } finally {
      setDownloading(false);
    }
  };

  const chip = (entry: CalendarEntry) => (
    <button
      key={entry.id}
      type="button"
      className={`cal-chip kind-${entry.kind}`}
      onClick={() => setViewing(entry)}
      title={`${kindLabel(t, entry.kind)}: ${entry.title}`}
    >
      {entry.startTime ? <span className="num">{formatClock(entry.startTime, locale)} </span> : null}
      {entry.title}
    </button>
  );

  return (
    <>
      <PageHead
        eyebrow={data ? plural(t, "calendar.holidaysThisMonth", holidaysThisMonth) : t("common.loading")}
        title={t("calendar.title")}
        actions={
          <>
            <button type="button" className="btn" onClick={download} disabled={downloading}>
              <Download size={18} aria-hidden="true" />
              {downloading ? t("common.working") : t("calendar.download")}
            </button>
            {canManage ? (
              <>
                <button type="button" className="btn" onClick={() => setStarterOpen(true)}>
                  <ListChecks size={18} aria-hidden="true" />
                  {t("calendar.starter.open")}
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => openAdd(month === today.slice(0, 7) ? today : `${month}-01`)}
                >
                  <Plus size={18} aria-hidden="true" />
                  {t("calendar.add")}
                </button>
              </>
            ) : null}
          </>
        }
      />
      <FormAlert message={downloadError} />

      <section className="card" aria-labelledby="calendar-month">
        <div className="cal-toolbar">
          <button
            type="button"
            className="iconbtn"
            onClick={() => setMonth((m) => addMonths(m, -1))}
            aria-label={t("calendar.previousMonth")}
          >
            <ChevronLeft size={20} aria-hidden="true" />
          </button>
          <h2 id="calendar-month" className="cal-month" aria-live="polite">
            {monthTitle}
          </h2>
          <button
            type="button"
            className="iconbtn"
            onClick={() => setMonth((m) => addMonths(m, 1))}
            aria-label={t("calendar.nextMonth")}
          >
            <ChevronRight size={20} aria-hidden="true" />
          </button>
          {month !== today.slice(0, 7) ? (
            <button type="button" className="btn btn-sm" onClick={() => setMonth(today.slice(0, 7))}>
              {t("calendar.today")}
            </button>
          ) : null}
          <ul className="cal-legend" aria-label={t("calendar.legend")}>
            {ENTRY_KINDS.map((kind) => (
              <li key={kind}>
                <span className={`cal-swatch kind-${kind}`} aria-hidden="true" />
                {kindLabel(t, kind)}
              </li>
            ))}
          </ul>
        </div>

        {list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : (
          <div aria-busy={list.loading}>
            <div className="cal-grid" data-testid="calendar-grid">
              {weekdays.map((name) => (
                <div key={name} className="cal-weekday" aria-hidden="true">
                  {name}
                </div>
              ))}
              {days.map((day) => {
                const entries = byDay.get(day) ?? [];
                const holiday = entries.some(isSchoolHoliday);
                const classes = [
                  "cal-day",
                  day.startsWith(month) ? "" : "is-out",
                  day === today ? "is-today" : "",
                  holiday ? "is-holiday" : "",
                  new Date(`${day}T00:00:00Z`).getUTCDay() === 0 ? "is-sunday" : "",
                ]
                  .filter(Boolean)
                  .join(" ");
                return (
                  <div
                    key={day}
                    className={classes}
                    role="group"
                    aria-label={formatPlainDate(day, locale)}
                    aria-current={day === today ? "date" : undefined}
                    data-date={day}
                  >
                    <div className="cal-date">
                      <span className="num">{Number(day.slice(8))}</span>
                      {canManage ? (
                        <button
                          type="button"
                          className="cal-add"
                          onClick={() => openAdd(day)}
                          aria-label={t("calendar.addOn", { date: formatPlainDate(day, locale) })}
                        >
                          <CalendarPlus size={14} aria-hidden="true" />
                        </button>
                      ) : null}
                    </div>
                    {entries.slice(0, CELL_ENTRIES).map(chip)}
                    {entries.length > CELL_ENTRIES ? (
                      <button type="button" className="cal-more" onClick={() => setDayOpen(day)}>
                        {t("calendar.more", { count: entries.length - CELL_ENTRIES })}
                      </button>
                    ) : null}
                  </div>
                );
              })}
            </div>

            <div className="cal-agenda" data-testid="calendar-agenda">
              {agenda.length === 0 ? (
                <p className="empty">{t("calendar.emptyMonth")}</p>
              ) : (
                <ol className="list">
                  {agenda.map((day) => (
                    <li key={day} className="cal-agenda-day">
                      <h3 className={day === today ? "text-accent" : undefined}>{formatPlainDate(day, locale)}</h3>
                      <div className="flex flex-col gap-1.5">{(byDay.get(day) ?? []).map(chip)}</div>
                    </li>
                  ))}
                </ol>
              )}
            </div>
            {data.entries.length === 0 ? (
              <p className="cal-empty-note text-[13px] text-ink-3">
                {canManage ? t("calendar.emptyManage") : t("calendar.emptyMonth")}
              </p>
            ) : null}
          </div>
        )}
      </section>

      <EntryDetailsDialog
        entry={viewing}
        canManage={canManage}
        onClose={() => setViewing(null)}
        onEdit={(entry) => {
          setViewing(null);
          setEditing({ entry, date: entry.startsOn, nonce: Date.now() });
        }}
        onDelete={(entry) => {
          setViewing(null);
          setDeleting(entry);
        }}
      />

      <Dialog
        open={dayOpen !== null}
        onClose={() => setDayOpen(null)}
        title={dayOpen ? formatPlainDate(dayOpen, locale) : ""}
        closeLabel={t("common.close")}
      >
        <div className="flex flex-col gap-1.5">
          {(dayOpen ? (byDay.get(dayOpen) ?? []) : []).map((entry) => (
            <button
              key={entry.id}
              type="button"
              className={`cal-chip kind-${entry.kind}`}
              onClick={() => {
                setDayOpen(null);
                setViewing(entry);
              }}
            >
              {entry.title}
            </button>
          ))}
        </div>
      </Dialog>

      {editing ? (
        <CalendarEntryDialog
          key={`${editing.entry?.id ?? "new"}:${editing.nonce}`}
          open
          entry={editing.entry}
          date={editing.date}
          classes={data?.classes ?? []}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            toast(editing.entry ? t("calendar.toast.saved", { title: saved.title }) : t("calendar.toast.added", { title: saved.title }));
            setEditing(null);
            list.reload();
          }}
        />
      ) : null}

      <ConfirmDialog
        open={deleting !== null}
        title={t("calendar.delete.title", { title: deleting?.title ?? "" })}
        body={t("calendar.delete.body")}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!deleting) return;
          await calendarApi.remove(deleting.id);
          setDeleting(null);
          toast(t("common.deleted"));
          list.reload();
        }}
        onClose={() => setDeleting(null)}
      />

      {canManage ? (
        <HolidaySuggestionsDialog
          open={starterOpen}
          onClose={() => setStarterOpen(false)}
          onAdded={(count) => {
            setStarterOpen(false);
            toast(plural(t, "calendar.starter.addedToast", count));
            list.reload();
          }}
        />
      ) : null}
    </>
  );
}

function EntryDetailsDialog({
  entry,
  canManage,
  onClose,
  onEdit,
  onDelete,
}: {
  entry: CalendarEntry | null;
  canManage: boolean;
  onClose: () => void;
  onEdit: (entry: CalendarEntry) => void;
  onDelete: (entry: CalendarEntry) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <Dialog open={entry !== null} onClose={onClose} title={entry?.title ?? ""} closeLabel={t("common.close")}>
      {entry ? (
        <div className="flex flex-col gap-3" data-testid="entry-details">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`cal-swatch kind-${entry.kind}`} aria-hidden="true" />
            <span className="font-semibold">{kindLabel(t, entry.kind)}</span>
            {isSchoolHoliday(entry) ? <Pill tone="good">{t("calendar.schoolClosed")}</Pill> : null}
          </div>
          <dl className="kv text-[13.5px]">
            <dt>{t("calendar.when")}</dt>
            <dd>{entryWhen(entry, t, locale)}</dd>
            <dt>{t("calendar.field.audience")}</dt>
            <dd>{entryAudience(entry, t)}</dd>
            {entry.reminderDays !== null ? (
              <>
                <dt>{t("calendar.field.reminder")}</dt>
                <dd>
                  {entry.reminderSentAt
                    ? t("calendar.reminderSent", { time: formatDateTime(entry.reminderSentAt, locale) })
                    : plural(t, "calendar.reminderDays", entry.reminderDays, {
                        channels: [t("notices.channels.appShort"), ...entry.reminderChannels.map((c) => channelLabel(t, c))].join(", "),
                      })}
                </dd>
              </>
            ) : null}
            {entry.updatedByName || entry.createdByName ? (
              <>
                <dt>{t("calendar.addedBy")}</dt>
                <dd>{entry.updatedByName ?? entry.createdByName}</dd>
              </>
            ) : null}
          </dl>
          {entry.description ? <p className="msg-body">{entry.description}</p> : null}
          {canManage ? (
            <div className="flex justify-end gap-2">
              <button type="button" className="btn" onClick={() => onDelete(entry)}>
                <Trash2 size={18} aria-hidden="true" />
                {t("common.delete")}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => onEdit(entry)}>
                <Pencil size={18} aria-hidden="true" />
                {t("common.edit")}
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
    </Dialog>
  );
}
