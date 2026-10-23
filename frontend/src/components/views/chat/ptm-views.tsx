"use client";

import { ArrowLeft, Ban, CalendarClock, Pencil, Plus, Printer } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadFailure, LoadingRows, PageHead, pendingTitle } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ptmApi } from "@/lib/chat-api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type {
  PtmAttendance,
  PtmBooking,
  PtmEventDetail,
  PtmEventSection,
  PtmEventSummary,
  PtmSlot,
  PtmTeacherSchedule,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AttendancePill, hhmm, MAX_NOTE_LENGTH, MAX_REASON_LENGTH, timeRange } from "./chat-shared";
import { EventFormDialog, EventStatusPill, eventWhen, placeText } from "./ptm-shared";

/* Parent-teacher meetings for staff: /app/ptm (meetings and one's own schedule) and /app/ptm/[id]. */

const eventHref = (eventId: string) => `/app/ptm/${encodeURIComponent(eventId)}`;

/** "Class 5 A, Class 5 B". Pure. */
export function sectionsText(sections: { label: string }[]): string {
  return sections.map((s) => s.label).join(", ");
}

/** /app/ptm: leadership's meetings (ptm.manage) and the teacher's own schedules (chat.use). */
export function PtmHomeView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const router = useRouter();
  const canManage = hasPermission(me, PERMISSIONS.ptmManage);
  const canUse = hasPermission(me, PERMISSIONS.chatUse);
  const [creating, setCreating] = useState(false);
  return (
    <>
      <PageHead
        eyebrow={me?.tenant?.name}
        title={t("ptm.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
              <Plus size={18} aria-hidden="true" />
              {t("ptm.new")}
            </button>
          ) : null
        }
      />
      {canUse ? <MyMeetings /> : null}
      {canManage ? <ManagedMeetings /> : null}
      <EventFormDialog
        open={creating}
        onClose={() => setCreating(false)}
        onSaved={(event) => {
          setCreating(false);
          router.push(eventHref(event.id));
        }}
      />
    </>
  );
}

function ManagedMeetings() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const events = useApiData("ptm:events", ptmApi.events);
  const rows = events.data ?? [];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="ptm-events" data-testid="ptm-events">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="ptm-events">{t("ptm.events.title")}</h2>
      </div>
      <RemindersToggle />
      {events.error && !events.data ? (
        <ErrorState error={events.error} onRetry={events.reload} />
      ) : !events.data ? (
        <LoadingRows rows={3} />
      ) : rows.length === 0 ? (
        <p className="empty">{t("ptm.events.empty")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">{t("ptm.col.meeting")}</th>
                  <th scope="col">{t("ptm.col.when")}</th>
                  <th scope="col">{t("ptm.col.classes")}</th>
                  <th scope="col" className="r">
                    {t("ptm.col.booked")}
                  </th>
                  <th scope="col">{t("ptm.col.status")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((e) => (
                  <tr key={e.id}>
                    <td>
                      <Link className="link" href={eventHref(e.id)}>
                        {e.title}
                      </Link>
                      <span className="block text-[12.5px] text-ink-3">{placeText(t, e)}</span>
                    </td>
                    <td>{eventWhen(e, locale)}</td>
                    <td>{sectionsText(e.sections)}</td>
                    <td className="r num">
                      {e.booked} / {e.slots}
                    </td>
                    <td>
                      <EventStatusPill status={e.status} past={e.past} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden">
            {rows.map((e) => (
              <li key={e.id}>
                <EventRowCard event={e} />
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

function EventRowCard({ event, sub }: { event: PtmEventSummary; sub?: string }) {
  const { t, lang } = useI18n();
  return (
    <Link className="rowcard items-start" href={eventHref(event.id)} data-testid="ptm-event-row">
      <span className="badge-ic" aria-hidden="true">
        <CalendarClock size={18} />
      </span>
      <span className="min-w-0 flex-1">
        <b className="block">{event.title}</b>
        <span className="block text-[13px]">{eventWhen(event, localeFor(lang))}</span>
        <span className="block text-[12.5px] text-ink-3">
          {sub ?? `${sectionsText(event.sections)} · ${t("ptm.bookedOf", { booked: event.booked, slots: event.slots })}`}
        </span>
      </span>
      <EventStatusPill status={event.status} past={event.past} />
    </Link>
  );
}

function RemindersToggle() {
  const { t } = useI18n();
  const { toast } = useToast();
  const settings = useApiData("ptm:settings", ptmApi.settings);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!settings.data) return null;
  const on = settings.data.remindersEnabled;
  return (
    <div className="flex flex-col gap-1">
      <label className="check">
        <input
          type="checkbox"
          name="remindersEnabled"
          checked={on}
          disabled={busy}
          onChange={async (e) => {
            setBusy(true);
            setError(null);
            try {
              await ptmApi.saveSettings(e.target.checked);
              toast(e.target.checked ? t("ptm.reminders.on") : t("ptm.reminders.off"));
              settings.reload();
            } catch (caught) {
              setError(errorMessage(toApiError(caught), t));
            } finally {
              setBusy(false);
            }
          }}
        />
        {t("ptm.reminders.label")}
      </label>
      <p className="text-[12.5px] text-ink-3">{t("ptm.reminders.hint")}</p>
      <FormAlert message={error} />
    </div>
  );
}

function MyMeetings() {
  const { t } = useI18n();
  const mine = useApiData("ptm:mine", ptmApi.mine);
  const rows = mine.data ?? [];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="ptm-mine" data-testid="ptm-mine">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="ptm-mine">{t("ptm.mine.title")}</h2>
      </div>
      {mine.error && !mine.data ? (
        <ErrorState error={mine.error} onRetry={mine.reload} />
      ) : !mine.data ? (
        <LoadingRows rows={2} />
      ) : rows.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("ptm.mine.empty")}</p>
      ) : (
        <ul className="rowcards">
          {rows.map((m) => (
            <li key={m.event.id}>
              <EventRowCard
                event={m.event}
                sub={`${t("ptm.mine.hours", { hours: timeRange(m.schedule.availableFrom, m.schedule.availableTo) })} · ${plural(
                  t,
                  "ptm.card.booked",
                  m.schedule.booked,
                  { slots: m.schedule.booked + m.schedule.free },
                )}`}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/* ------------------------------------------------------------------ one meeting */

/** /app/ptm/[id]: the whole meeting for ptm.manage, the teacher's own schedule otherwise. */
export function PtmEventView({ id }: { id: string }) {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.ptmManage) ? <ManagedEvent id={id} /> : <TeacherEvent id={id} />;
}

/** What a schedule needs to know about its meeting. */
type Meeting = {
  eventId: string;
  date: string;
  startsAt: string;
  endsAt: string;
  /** Scheduled and not over: slots and bookings can change. */
  open: boolean;
  sections: PtmEventSection[];
};

function ManagedEvent({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const loaded = useApiData(`ptm:event:${id}`, () => ptmApi.event(id));
  const [current, setCurrent] = useState<PtmEventDetail | null>(null);
  const [editing, setEditing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const data = current ?? loaded.data;
  const reload = () => {
    setCurrent(null);
    loaded.reload();
  };
  const open = Boolean(data && data.status === "SCHEDULED" && !data.past);

  return (
    <>
      <PageHead
        eyebrow={t("ptm.title")}
        title={data?.title ?? pendingTitle(t, loaded.error)}
        actions={
          <>
            <Link href="/app/ptm" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("ptm.back")}
            </Link>
            {data ? (
              <Link href={`${eventHref(id)}/print`} className="btn">
                <Printer size={18} aria-hidden="true" />
                {t("ptm.print")}
              </Link>
            ) : null}
            {open ? (
              <>
                <button type="button" className="btn" onClick={() => setEditing(true)}>
                  <Pencil size={18} aria-hidden="true" />
                  {t("common.edit")}
                </button>
                <button type="button" className="btn btn-danger" onClick={() => setCancelling(true)}>
                  <Ban size={18} aria-hidden="true" />
                  {t("ptm.cancel")}
                </button>
              </>
            ) : null}
          </>
        }
      />
      {loaded.error && !data ? (
        <LoadFailure error={loaded.error} onRetry={loaded.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          {data.status === "CANCELLED" ? (
            <div className="alert alert-bad" role="status">
              <span>
                {t("ptm.cancelled", {
                  name: data.cancelledByName ?? "—",
                  date: formatDateTime(data.cancelledAt, locale),
                  reason: data.cancelReason ?? "",
                })}
              </span>
            </div>
          ) : null}
          <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
            <section className="card flex flex-col gap-3" aria-labelledby="ptm-info" data-testid="ptm-info">
              <div className="card-head" style={{ marginBottom: 0 }}>
                <h2 id="ptm-info">{t("ptm.info.title")}</h2>
                <EventStatusPill status={data.status} past={data.past} />
              </div>
              <dl className="kv text-[13.5px]">
                <dt>{t("ptm.info.when")}</dt>
                <dd>{eventWhen(data, locale)}</dd>
                <dt>{t("ptm.info.slots")}</dt>
                <dd>
                  {t("ptm.form.minutes", { count: data.slotMinutes })}
                  {data.breaks.length
                    ? ` · ${t("ptm.info.breaks", { breaks: data.breaks.map((b) => timeRange(b.from, b.to)).join(", ") })}`
                    : ""}
                </dd>
                <dt>{t("ptm.info.place")}</dt>
                <dd>
                  {placeText(t, data)}
                  {data.videoLink ? (
                    <a className="link block break-all" href={data.videoLink} target="_blank" rel="noreferrer noopener">
                      {data.videoLink}
                    </a>
                  ) : null}
                </dd>
                <dt>{t("ptm.info.classes")}</dt>
                <dd>
                  {sectionsText(data.sections)}
                  {data.includeSubjectTeachers ? ` · ${t("ptm.info.subjectTeachers")}` : ""}
                </dd>
                <dt>{t("ptm.info.cutoff")}</dt>
                <dd>{plural(t, "ptm.info.cutoffHours", data.cutoffHours)}</dd>
                <dt>{t("ptm.info.reminders")}</dt>
                <dd>{data.remindersEnabled ? t("ptm.info.remindersOn") : t("ptm.info.remindersOff")}</dd>
                {data.note ? (
                  <>
                    <dt>{t("ptm.info.note")}</dt>
                    <dd className="whitespace-pre-wrap">{data.note}</dd>
                  </>
                ) : null}
                <dt>{t("ptm.info.createdBy")}</dt>
                <dd>
                  {data.createdByName ?? "—"} · {formatDateTime(data.createdAt, locale)}
                </dd>
              </dl>
            </section>
            <SectionsCard sections={data.sections} />
          </div>
          {data.teachers.length === 0 ? (
            <p className="empty">{t("ptm.noTeachers")}</p>
          ) : (
            data.teachers.map((schedule) => (
              <ScheduleCard
                key={schedule.ptmTeacherId}
                schedule={schedule}
                meeting={{
                  eventId: data.id,
                  date: data.date,
                  startsAt: data.startsAt,
                  endsAt: data.endsAt,
                  open,
                  sections: data.sections,
                }}
                manager
                canBook={hasPermission(me, PERMISSIONS.studentsRead)}
                onChanged={reload}
              />
            ))
          )}
          <EventFormDialog
            open={editing}
            event={data}
            onClose={() => setEditing(false)}
            onSaved={(saved) => {
              setEditing(false);
              setCurrent(saved);
              toast(t("ptm.saved"));
            }}
          />
          <CancelEventDialog
            open={cancelling}
            onClose={() => setCancelling(false)}
            onCancel={async (reason) => {
              setCurrent(await ptmApi.cancel(id, reason));
              setCancelling(false);
            }}
          />
        </>
      )}
    </>
  );
}

function SectionsCard({ sections }: { sections: PtmEventSection[] }) {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="ptm-sections" data-testid="ptm-sections">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="ptm-sections">{t("ptm.sections.title")}</h2>
      </div>
      <ul className="rowcards">
        {sections.map((s) => (
          <li key={s.id} className="rowcard">
            <span className="min-w-0 flex-1">
              <b className="block">{s.label}</b>
              <span className="block text-[12.5px] text-ink-3">
                {s.classTeacherName ? t("ptm.sections.classTeacher", { name: s.classTeacherName }) : t("ptm.sections.noClassTeacher")}
              </span>
            </span>
            <span className="num text-[13.5px]">{t("ptm.sections.booked", { booked: s.studentsBooked, students: s.students })}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function CancelEventDialog({
  open,
  onClose,
  onCancel,
}: {
  open: boolean;
  onClose: () => void;
  onCancel: (reason: string) => Promise<void>;
}) {
  const { t } = useI18n();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  return (
    <Dialog open={open} onClose={onClose} title={t("ptm.cancel.title")} description={t("ptm.cancel.body")} closeLabel={t("common.close")}>
      <form
        method="post"
        className="flex flex-col gap-3"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (!reason.trim()) return setError(t("chat.v.reason"));
          setBusy(true);
          setFormError(null);
          try {
            await onCancel(reason.trim());
          } catch (caught) {
            setFormError(errorMessage(toApiError(caught), t));
          } finally {
            setBusy(false);
          }
        }}
      >
        <FormAlert message={formError} />
        <TextAreaField
          label={t("ptm.cancel.reason")}
          name="reason"
          rows={3}
          value={reason}
          maxLength={MAX_REASON_LENGTH}
          error={error ?? undefined}
          onChange={(e) => {
            setReason(e.target.value);
            setError(null);
          }}
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.back")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={busy}>
            {busy ? t("common.working") : t("ptm.cancel")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function TeacherEvent({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const loaded = useApiData(`ptm:my:${id}`, () => ptmApi.myEvent(id));
  const data = loaded.data;
  return (
    <>
      <PageHead
        eyebrow={t("ptm.title")}
        title={data?.event.title ?? pendingTitle(t, loaded.error)}
        actions={
          <>
            <Link href="/app/ptm" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("ptm.back")}
            </Link>
            {data ? (
              <Link href={`${eventHref(id)}/print`} className="btn">
                <Printer size={18} aria-hidden="true" />
                {t("ptm.print")}
              </Link>
            ) : null}
          </>
        }
      />
      {loaded.error && !data ? (
        <LoadFailure error={loaded.error} onRetry={loaded.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          {data.event.status === "CANCELLED" ? (
            <div className="alert alert-bad" role="status">
              <span>{t("ptm.cancelledShort", { reason: data.cancelReason ?? "" })}</span>
            </div>
          ) : null}
          <section className="card flex flex-col gap-3" aria-labelledby="ptm-info">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2 id="ptm-info">{t("ptm.info.title")}</h2>
              <EventStatusPill status={data.event.status} past={data.event.past} />
            </div>
            <dl className="kv text-[13.5px]">
              <dt>{t("ptm.info.when")}</dt>
              <dd>{eventWhen(data.event, locale)}</dd>
              <dt>{t("ptm.info.place")}</dt>
              <dd>
                {placeText(t, data.event)}
                {data.videoLink ? (
                  <a className="link block break-all" href={data.videoLink} target="_blank" rel="noreferrer noopener">
                    {data.videoLink}
                  </a>
                ) : null}
              </dd>
              <dt>{t("ptm.info.classes")}</dt>
              <dd>{sectionsText(data.event.sections)}</dd>
              <dt>{t("ptm.info.cutoff")}</dt>
              <dd>{plural(t, "ptm.info.cutoffHours", data.cutoffHours)}</dd>
              {data.note ? (
                <>
                  <dt>{t("ptm.info.note")}</dt>
                  <dd className="whitespace-pre-wrap">{data.note}</dd>
                </>
              ) : null}
            </dl>
          </section>
          <ScheduleCard
            schedule={data.schedule}
            meeting={{
              eventId: data.event.id,
              date: data.event.date,
              startsAt: data.event.startsAt,
              endsAt: data.event.endsAt,
              open: data.event.status === "SCHEDULED" && !data.event.past,
              sections: [],
            }}
            manager={false}
            canBook={false}
            onChanged={loaded.reload}
          />
        </>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ a teacher's schedule */

/** "Class teacher" or the subjects a subject teacher teaches. */
function teacherDetail(t: ReturnType<typeof useI18n>["t"], schedule: PtmTeacherSchedule): string {
  return schedule.kind === "CLASS_TEACHER"
    ? [t("ptm.kind.CLASS_TEACHER"), schedule.subjects].filter(Boolean).join(" · ")
    : schedule.subjects || t("ptm.kind.SUBJECT_TEACHER");
}

/** "Free", "Blocked" or who booked the slot. */
function slotState(t: ReturnType<typeof useI18n>["t"], slot: PtmSlot): string {
  if (slot.booking) return slot.booking.student.name ?? "—";
  return slot.blocked ? t("ptm.slot.blocked") : t("ptm.slot.free");
}

function bookedByText(t: ReturnType<typeof useI18n>["t"], booking: PtmBooking): string {
  return booking.bookedBy === "SCHOOL"
    ? t("ptm.slot.bySchool", { name: booking.bookedByName ?? "—" })
    : booking.parentName ?? booking.bookedByName ?? "—";
}

function ScheduleCard({
  schedule,
  meeting,
  manager,
  canBook,
  onChanged,
}: {
  schedule: PtmTeacherSchedule;
  meeting: Meeting;
  manager: boolean;
  canBook: boolean;
  onChanged: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [today] = useState(() => todayInIndia());
  const [hours, setHours] = useState(false);
  const [marking, setMarking] = useState<PtmBooking | null>(null);
  const [booking, setBooking] = useState<PtmSlot | null>(null);
  const [cancelling, setCancelling] = useState<PtmBooking | null>(null);
  const [error, setError] = useState<string | null>(null);
  const titleId = useId();

  const block = async (slot: PtmSlot) => {
    setError(null);
    try {
      if (manager) await ptmApi.block(slot.id, !slot.blocked);
      else await ptmApi.myBlock(slot.id, !slot.blocked);
      toast(slot.blocked ? t("ptm.slot.unblocked") : t("ptm.slot.blockedDone"));
      onChanged();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    }
  };

  const actions = (slot: PtmSlot) => {
    const b = slot.booking;
    if (b) {
      return (
        <>
          <button type="button" className="btn btn-sm" onClick={() => setMarking(b)}>
            {b.attendance ? t("ptm.mark.edit") : t("ptm.mark")}
          </button>
          {manager && meeting.open ? (
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => setCancelling(b)}>
              {t("ptm.booking.cancel")}
            </button>
          ) : null}
        </>
      );
    }
    if (!meeting.open) return null;
    return (
      <>
        {manager && canBook && !slot.blocked ? (
          <button type="button" className="btn btn-sm" onClick={() => setBooking(slot)}>
            {t("ptm.booking.forFamily")}
          </button>
        ) : null}
        <button type="button" className="btn btn-sm btn-ghost" onClick={() => block(slot)}>
          {slot.blocked ? t("ptm.slot.unblock") : t("ptm.slot.block")}
        </button>
      </>
    );
  };

  return (
    <section className="card flex flex-col gap-3" aria-labelledby={titleId} data-testid="ptm-schedule">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 id={titleId}>{schedule.name ?? "—"}</h2>
          <p className="mt-1 text-[13px] text-ink-3">
            {teacherDetail(t, schedule)} · {timeRange(schedule.availableFrom, schedule.availableTo)}
          </p>
        </div>
        <span className="flex flex-wrap items-center gap-2">
          <Pill tone="info">{t("ptm.schedule.counts", { booked: schedule.booked, free: schedule.free })}</Pill>
          {meeting.open ? (
            <button type="button" className="btn btn-sm" onClick={() => setHours(true)}>
              {t("ptm.hours.change")}
            </button>
          ) : null}
        </span>
      </div>
      <FormAlert message={error} />
      {schedule.slots.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("ptm.schedule.noSlots")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">{t("ptm.col.time")}</th>
                  <th scope="col">{t("ptm.col.student")}</th>
                  <th scope="col">{t("ptm.col.parent")}</th>
                  <th scope="col">{t("ptm.col.attendance")}</th>
                  <th scope="col">
                    <span className="sr-only">{t("common.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {schedule.slots.map((slot) => (
                  <tr key={slot.id} data-testid="ptm-slot-row">
                    <td className="num whitespace-nowrap">{timeRange(slot.startsAt, slot.endsAt)}</td>
                    <td>
                      {slot.booking ? (
                        <>
                          <b className="block">{slot.booking.student.name ?? "—"}</b>
                          <span className="block text-[12.5px] text-ink-3">{slot.booking.student.classLabel}</span>
                        </>
                      ) : (
                        <Pill tone={slot.blocked ? "neutral" : "good"}>{slotState(t, slot)}</Pill>
                      )}
                    </td>
                    <td>{slot.booking ? bookedByText(t, slot.booking) : "—"}</td>
                    <td>{slot.booking ? <AttendancePill attendance={slot.booking.attendance} /> : null}</td>
                    <td className="r">
                      <span className="inline-flex flex-wrap justify-end gap-1.5">{actions(slot)}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden">
            {schedule.slots.map((slot) => (
              <li key={slot.id} className="rowcard items-start" data-testid="ptm-slot-card">
                <b className="num w-14 flex-none">{hhmm(slot.startsAt)}</b>
                <span className="min-w-0 flex-1">
                  <span className="block">{slotState(t, slot)}</span>
                  {slot.booking ? (
                    <span className="block text-[12.5px] text-ink-3">
                      {[slot.booking.student.classLabel, bookedByText(t, slot.booking)].filter(Boolean).join(" · ")}
                    </span>
                  ) : null}
                  {slot.booking ? <AttendancePill attendance={slot.booking.attendance} /> : null}
                </span>
                <span className="flex flex-col items-end gap-1.5">{actions(slot)}</span>
              </li>
            ))}
          </ul>
        </>
      )}
      <HoursDialog
        open={hours}
        schedule={schedule}
        meeting={meeting}
        onClose={() => setHours(false)}
        onSave={async (from, to) => {
          if (manager) await ptmApi.hours(meeting.eventId, schedule.teacherId, from, to);
          else await ptmApi.myHours(meeting.eventId, from, to);
          setHours(false);
          toast(t("ptm.hours.saved"));
          onChanged();
        }}
      />
      <MarkDialog
        booking={marking}
        canMarkAttendance={today >= meeting.date}
        onClose={() => setMarking(null)}
        onSave={async (body) => {
          if (!marking) return;
          if (manager) await ptmApi.mark(marking.id, body);
          else await ptmApi.myMark(marking.id, body);
          setMarking(null);
          toast(t("ptm.mark.saved"));
          onChanged();
        }}
      />
      {manager ? (
        <BookForFamilyDialog
          slot={booking}
          schedule={schedule}
          meeting={meeting}
          onClose={() => setBooking(null)}
          onBooked={() => {
            setBooking(null);
            toast(t("ptm.booking.done"));
            onChanged();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={cancelling !== null}
        title={t("ptm.booking.cancelTitle")}
        body={t("ptm.booking.cancelBody", {
          student: cancelling?.student.name ?? "",
          time: hhmm(cancelling?.startsAt),
        })}
        confirmLabel={t("ptm.booking.cancel")}
        onClose={() => setCancelling(null)}
        onConfirm={async () => {
          if (!cancelling) return;
          await ptmApi.cancelBooking(cancelling.id);
          setCancelling(null);
          onChanged();
        }}
      />
    </section>
  );
}

function HoursDialog({
  open,
  schedule,
  meeting,
  onClose,
  onSave,
}: {
  open: boolean;
  schedule: PtmTeacherSchedule;
  meeting: Meeting;
  onClose: () => void;
  onSave: (from: string, to: string) => Promise<void>;
}) {
  const { t } = useI18n();
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t("ptm.hours.title", { name: schedule.name ?? "" })}
      description={t("ptm.hours.body", { hours: timeRange(meeting.startsAt, meeting.endsAt) })}
      closeLabel={t("common.close")}
    >
      {open ? <HoursForm schedule={schedule} meeting={meeting} onClose={onClose} onSave={onSave} /> : null}
    </Dialog>
  );
}

function HoursForm({
  schedule,
  meeting,
  onClose,
  onSave,
}: {
  schedule: PtmTeacherSchedule;
  meeting: Meeting;
  onClose: () => void;
  onSave: (from: string, to: string) => Promise<void>;
}) {
  const { t } = useI18n();
  const [from, setFrom] = useState(hhmm(schedule.availableFrom));
  const [to, setTo] = useState(hhmm(schedule.availableTo));
  const [errors, setErrors] = useState<Partial<Record<string, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (to <= from) return setErrors({ availableTo: t("ptm.v.timeOrder") });
    if (from < hhmm(meeting.startsAt) || to > hhmm(meeting.endsAt)) {
      return setErrors({ availableTo: t("ptm.v.hoursInside", { hours: timeRange(meeting.startsAt, meeting.endsAt) }) });
    }
    setErrors({});
    setBusy(true);
    setFormError(null);
    try {
      await onSave(from, to);
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
      <FormAlert message={formError} />
      <div className="grid grid-cols-2 gap-3">
        <TextField
          label={t("ptm.hours.from")}
          type="time"
          name="availableFrom"
          value={from}
          min={hhmm(meeting.startsAt)}
          max={hhmm(meeting.endsAt)}
          error={errors.availableFrom}
          onChange={(e) => setFrom(e.target.value)}
          data-autofocus
        />
        <TextField
          label={t("ptm.hours.to")}
          type="time"
          name="availableTo"
          value={to}
          min={hhmm(meeting.startsAt)}
          max={hhmm(meeting.endsAt)}
          error={errors.availableTo}
          onChange={(e) => setTo(e.target.value)}
        />
      </div>
      <p className="text-[12.5px] text-ink-3">{t("ptm.hours.rule")}</p>
      <div className="flex justify-end gap-2">
        <button type="button" className="btn" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t("common.working") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function MarkDialog({
  booking,
  canMarkAttendance,
  onClose,
  onSave,
}: {
  booking: PtmBooking | null;
  canMarkAttendance: boolean;
  onClose: () => void;
  onSave: (body: { attendance: PtmAttendance | null; privateNote: string | null; parentNote: string | null }) => Promise<void>;
}) {
  const { t } = useI18n();
  return (
    <Dialog
      open={booking !== null}
      onClose={onClose}
      title={t("ptm.mark.title", { student: booking?.student.name ?? "", time: hhmm(booking?.startsAt) })}
      closeLabel={t("common.close")}
    >
      {booking ? (
        <MarkForm key={booking.id} booking={booking} canMarkAttendance={canMarkAttendance} onClose={onClose} onSave={onSave} />
      ) : null}
    </Dialog>
  );
}

function MarkForm({
  booking,
  canMarkAttendance,
  onClose,
  onSave,
}: {
  booking: PtmBooking;
  canMarkAttendance: boolean;
  onClose: () => void;
  onSave: (body: { attendance: PtmAttendance | null; privateNote: string | null; parentNote: string | null }) => Promise<void>;
}) {
  const { t } = useI18n();
  const name = useId();
  const [attendance, setAttendance] = useState<PtmAttendance | null>(booking.attendance);
  const [privateNote, setPrivateNote] = useState(booking.privateNote ?? "");
  const [parentNote, setParentNote] = useState(booking.parentNote ?? "");
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const choices: { value: PtmAttendance | null; label: string }[] = [
    { value: null, label: t("ptm.attendance.none") },
    { value: "ATTENDED", label: t("ptm.attendance.ATTENDED") },
    { value: "NO_SHOW", label: t("ptm.attendance.NO_SHOW") },
  ];
  return (
    <form
      method="post"
      className="flex flex-col gap-3"
      noValidate
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setFormError(null);
        try {
          await onSave({
            attendance: canMarkAttendance ? attendance : booking.attendance,
            privateNote: privateNote.trim() || null,
            parentNote: parentNote.trim() || null,
          });
        } catch (caught) {
          setFormError(errorMessage(toApiError(caught), t));
        } finally {
          setBusy(false);
        }
      }}
    >
      <FormAlert message={formError} />
      <p className="text-[13.5px] text-ink-2">
        {[booking.student.classLabel, bookedByText(t, booking)].filter(Boolean).join(" · ")}
      </p>
      <fieldset className="field">
        <legend className="field-label">{t("ptm.col.attendance")}</legend>
        <div className="seg self-start" role="radiogroup" aria-label={t("ptm.col.attendance")}>
          {choices.map((choice) => (
            <label key={choice.value ?? "none"}>
              <input
                type="radio"
                name={name}
                value={choice.value ?? ""}
                checked={attendance === choice.value}
                disabled={!canMarkAttendance}
                onChange={() => setAttendance(choice.value)}
              />
              {choice.label}
            </label>
          ))}
        </div>
        {!canMarkAttendance ? <p className="field-hint">{t("ptm.mark.notYet")}</p> : null}
      </fieldset>
      <TextAreaField
        label={t("ptm.mark.privateNote")}
        hint={t("ptm.mark.privateHint")}
        name="privateNote"
        rows={2}
        value={privateNote}
        maxLength={MAX_NOTE_LENGTH}
        onChange={(e) => setPrivateNote(e.target.value)}
      />
      <TextAreaField
        label={t("ptm.mark.parentNote")}
        hint={t("ptm.mark.parentHint")}
        name="parentNote"
        rows={2}
        value={parentNote}
        maxLength={MAX_NOTE_LENGTH}
        onChange={(e) => setParentNote(e.target.value)}
      />
      <div className="flex justify-end gap-2">
        <button type="button" className="btn" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t("common.working") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function BookForFamilyDialog({
  slot,
  schedule,
  meeting,
  onClose,
  onBooked,
}: {
  slot: PtmSlot | null;
  schedule: PtmTeacherSchedule;
  meeting: Meeting;
  onClose: () => void;
  onBooked: () => void;
}) {
  const { t } = useI18n();
  return (
    <Dialog
      open={slot !== null}
      onClose={onClose}
      title={t("ptm.booking.forFamilyTitle", { time: hhmm(slot?.startsAt), name: schedule.name ?? "" })}
      description={t("ptm.booking.forFamilyBody")}
      closeLabel={t("common.close")}
    >
      {slot ? <BookForm slot={slot} meeting={meeting} onClose={onClose} onBooked={onBooked} /> : null}
    </Dialog>
  );
}

function BookForm({
  slot,
  meeting,
  onClose,
  onBooked,
}: {
  slot: PtmSlot;
  meeting: Meeting;
  onClose: () => void;
  onBooked: () => void;
}) {
  const { t } = useI18n();
  const [sectionId, setSectionId] = useState(meeting.sections[0]?.id ?? "");
  const [studentId, setStudentId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const students = useApiData(sectionId ? `ptm:students:${sectionId}` : null, () =>
    api.listStudents({ sectionId, status: "ACTIVE", size: 100 }),
  );
  const options = students.data?.items ?? [];
  return (
    <form
      method="post"
      className="flex flex-col gap-3"
      noValidate
      onSubmit={async (e) => {
        e.preventDefault();
        if (!studentId) return setError(t("ptm.v.student"));
        setBusy(true);
        setError(null);
        try {
          await ptmApi.book(meeting.eventId, slot.id, studentId);
          onBooked();
        } catch (caught) {
          setError(errorMessage(toApiError(caught), t));
        } finally {
          setBusy(false);
        }
      }}
    >
      <FormAlert message={error} />
      <SelectField
        label={t("ptm.booking.section")}
        name="sectionId"
        value={sectionId}
        options={meeting.sections.map((s) => ({ value: s.id, label: s.label }))}
        onChange={(e) => {
          setSectionId(e.target.value);
          setStudentId("");
        }}
      />
      {students.error && !students.data ? (
        <ErrorState error={students.error} onRetry={students.reload} />
      ) : !students.data ? (
        <LoadingRows rows={2} />
      ) : (
        <SelectField
          label={t("ptm.booking.student")}
          name="studentId"
          value={studentId}
          options={[
            { value: "", label: t("common.choose") },
            ...options.map((s) => ({ value: s.id, label: s.rollNo ? `${s.rollNo}. ${s.fullName}` : s.fullName })),
          ]}
          onChange={(e) => setStudentId(e.target.value)}
        />
      )}
      <div className="flex justify-end gap-2">
        <button type="button" className="btn" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t("common.working") : t("ptm.booking.book")}
        </button>
      </div>
    </form>
  );
}
