"use client";

import { Video } from "lucide-react";
import { useId, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { ptmApi } from "@/lib/chat-api";
import { formatDateTime, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { PtmChild, PtmFamilyEvent, PtmFamilySlot, PtmFamilyTeacher } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AttendancePill, hhmm, timeRange } from "./chat-shared";
import { EventStatusPill, eventWhen, placeText } from "./ptm-shared";

/* /app/family/ptm: a parent books, changes or cancels one slot per child with each teacher at a meeting. */

/** A slot a parent may pick now: free and before the cut-off. Pure, exported for tests. */
export function canPick(slot: PtmFamilySlot, now: Date): boolean {
  return slot.available && new Date(slot.bookableUntil).getTime() > now.getTime();
}

type Choice = { child: PtmChild; event: PtmFamilyEvent; teacher: PtmFamilyTeacher; slot: PtmFamilySlot };

export function FamilyPtmView({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const family = useApiData("ptm:family", ptmApi.family);
  const [chosen, setChosen] = useState<string | null>(initialChildId ?? null);
  const radio = useId();
  const children = family.data?.children ?? [];
  const child = children.find((c) => c.studentId === chosen) ?? children[0];
  return (
    <>
      <PageHead title={t("ptm.family.title")} />
      {family.error && !family.data ? (
        <ErrorState error={family.error} onRetry={family.reload} />
      ) : !family.data ? (
        <LoadingRows rows={4} />
      ) : children.length === 0 || !child ? (
        <p className="empty">{t("ptm.family.noChildren")}</p>
      ) : (
        <>
          {children.length > 1 ? (
            <div className="seg self-start" role="radiogroup" aria-label={t("portal.switcher.label")}>
              {children.map((c) => (
                <label key={c.studentId}>
                  <input
                    type="radio"
                    name={radio}
                    value={c.studentId}
                    checked={c.studentId === child.studentId}
                    onChange={() => setChosen(c.studentId)}
                  />
                  {c.name ?? "—"}
                </label>
              ))}
            </div>
          ) : null}
          <ChildMeetings key={child.studentId} child={child} onChanged={family.reload} />
          <p className="text-[12.5px] text-ink-3">
            {family.data.remindersEnabled ? t("ptm.family.reminders") : t("ptm.family.noReminders")}
          </p>
        </>
      )}
    </>
  );
}

function ChildMeetings({ child, onChanged }: { child: PtmChild; onChanged: () => void }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const [now] = useState(() => new Date());
  const [choice, setChoice] = useState<Choice | null>(null);
  const [moving, setMoving] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<{ teacher: PtmFamilyTeacher; event: PtmFamilyEvent } | null>(null);

  if (child.events.length === 0) {
    return (
      <section className="card">
        <p className="empty" data-testid="ptm-family-empty">
          {t("ptm.family.none", { name: child.name ?? "" })}
        </p>
      </section>
    );
  }

  return (
    <>
      {child.events.map((event) => (
        <EventCard
          key={event.id}
          child={child}
          event={event}
          now={now}
          moving={moving}
          onPick={(teacher, slot) => setChoice({ child, event, teacher, slot })}
          onMove={(teacher) => setMoving(moving === teacher.teacherId ? null : teacher.teacherId)}
          onCancel={(teacher) => setCancelling({ teacher, event })}
        />
      ))}
      <ConfirmDialog
        open={choice !== null}
        danger={false}
        title={
          choice
            ? t(choice.teacher.booking ? "ptm.family.moveTitle" : "ptm.family.bookTitle", {
                time: hhmm(choice.slot.startsAt),
                name: choice.teacher.name ?? "",
              })
            : ""
        }
        body={
          choice
            ? t("ptm.family.bookBody", {
                child: choice.child.name ?? "",
                date: formatPlainDate(choice.event.date, localeFor(lang)),
                hours: choice.event.cutoffHours,
              })
            : ""
        }
        confirmLabel={choice?.teacher.booking ? t("ptm.family.move") : t("ptm.family.bookSlot")}
        onClose={() => setChoice(null)}
        onConfirm={async () => {
          if (!choice) return;
          const booked = choice.teacher.booking
            ? await ptmApi.familyMove(choice.teacher.booking.id, choice.slot.id)
            : await ptmApi.familyBook(choice.slot.id, choice.child.studentId);
          setChoice(null);
          setMoving(null);
          toast(t("ptm.family.booked", { time: hhmm(booked.startsAt) }));
          onChanged();
        }}
      />
      <ConfirmDialog
        open={cancelling !== null}
        title={t("ptm.family.cancelTitle")}
        body={t("ptm.family.cancelBody", {
          name: cancelling?.teacher.name ?? "",
          time: hhmm(cancelling?.teacher.booking?.startsAt),
        })}
        confirmLabel={t("ptm.booking.cancel")}
        onClose={() => setCancelling(null)}
        onConfirm={async () => {
          const booking = cancelling?.teacher.booking;
          if (!booking) return;
          await ptmApi.familyCancel(booking.id);
          setCancelling(null);
          toast(t("ptm.family.cancelled"));
          onChanged();
        }}
      />
    </>
  );
}

function EventCard({
  child,
  event,
  now,
  moving,
  onPick,
  onMove,
  onCancel,
}: {
  child: PtmChild;
  event: PtmFamilyEvent;
  now: Date;
  moving: string | null;
  onPick: (teacher: PtmFamilyTeacher, slot: PtmFamilySlot) => void;
  onMove: (teacher: PtmFamilyTeacher) => void;
  onCancel: (teacher: PtmFamilyTeacher) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const titleId = useId();
  const open = event.status === "SCHEDULED" && !event.past;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={titleId} data-testid="ptm-family-event">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 id={titleId}>{event.title}</h2>
          <p className="mt-1 text-[13px] text-ink-2">{eventWhen(event, locale)}</p>
          <p className="text-[13px] text-ink-3">
            {[child.name, child.classLabel].filter(Boolean).join(" · ")} · {placeText(t, event)}
          </p>
        </div>
        <EventStatusPill status={event.status} past={event.past} />
      </div>
      {event.status === "CANCELLED" ? (
        <div className="alert alert-bad">
          <span>{t("ptm.cancelledShort", { reason: event.cancelReason ?? "" })}</span>
        </div>
      ) : null}
      {event.note ? <p className="whitespace-pre-wrap text-[13.5px] text-ink-2">{event.note}</p> : null}
      {event.teachers.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("ptm.family.noTeachers")}</p>
      ) : (
        <ul className="flex flex-col gap-4">
          {event.teachers.map((teacher) => {
            const booking = teacher.booking;
            const showSlots = open && (!booking || moving === teacher.teacherId);
            return (
              <li key={teacher.teacherId} className="flex flex-col gap-2 border-t border-line pt-3" data-testid="ptm-family-teacher">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="min-w-0">
                    <b className="block">{teacher.name ?? "—"}</b>
                    {teacher.detail ? <span className="block text-[12.5px] text-ink-3">{teacher.detail}</span> : null}
                  </span>
                  {booking ? (
                    <Pill tone="good" dot>
                      {t("ptm.family.bookedAt", { time: timeRange(booking.startsAt, booking.endsAt) })}
                    </Pill>
                  ) : open ? (
                    <Pill tone="warn">{t("ptm.family.notBooked")}</Pill>
                  ) : null}
                </div>
                {booking && event.mode === "VIDEO" && event.videoLink && open ? (
                  <a className="link inline-flex items-center gap-1 text-[13.5px]" href={event.videoLink} target="_blank" rel="noreferrer noopener">
                    <Video size={16} aria-hidden="true" />
                    {t("ptm.family.join")}
                  </a>
                ) : null}
                {booking && event.past ? (
                  <div className="flex flex-col gap-1">
                    <AttendancePill attendance={booking.attendance} />
                    {booking.parentNote ? (
                      <p className="whitespace-pre-wrap text-[13.5px]" data-testid="ptm-family-note">
                        {t("ptm.family.teacherNote", { note: booking.parentNote })}
                      </p>
                    ) : null}
                  </div>
                ) : null}
                {booking && open ? (
                  booking.canChange ? (
                    <div className="flex flex-wrap items-center gap-2">
                      <button type="button" className="btn btn-sm" aria-expanded={moving === teacher.teacherId} onClick={() => onMove(teacher)}>
                        {moving === teacher.teacherId ? t("ptm.family.keep") : t("ptm.family.change")}
                      </button>
                      <button type="button" className="btn btn-sm btn-ghost" onClick={() => onCancel(teacher)}>
                        {t("ptm.booking.cancel")}
                      </button>
                      <span className="text-[12.5px] text-ink-3">
                        {t("ptm.family.changeUntil", { date: formatDateTime(booking.changeUntil, locale) })}
                      </span>
                    </div>
                  ) : (
                    <p className="text-[12.5px] text-ink-3">
                      {booking.bookedBy === "SCHOOL" ? t("ptm.family.bySchool") : t("ptm.family.changesClosed")}
                    </p>
                  )
                ) : null}
                {showSlots ? (
                  <SlotGrid
                    teacher={teacher}
                    now={now}
                    onPick={(slot) => onPick(teacher, slot)}
                    label={t("ptm.family.slotsFor", { name: teacher.name ?? "" })}
                  />
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      {open ? (
        <p className="text-[12.5px] text-ink-3">{plural(t, "ptm.family.cutoff", event.cutoffHours)}</p>
      ) : null}
    </section>
  );
}

function SlotGrid({
  teacher,
  now,
  onPick,
  label,
}: {
  teacher: PtmFamilyTeacher;
  now: Date;
  onPick: (slot: PtmFamilySlot) => void;
  label: string;
}) {
  const { t } = useI18n();
  const slots = teacher.slots.filter((s) => s.id !== teacher.booking?.slotId);
  const free = slots.filter((s) => canPick(s, now)).length;
  if (slots.length === 0 || free === 0) {
    return <p className="text-[13.5px] text-ink-3">{t("ptm.family.full")}</p>;
  }
  return (
    <div className="flex flex-col gap-1.5">
      <div className="ptm-slots" role="group" aria-label={label} data-testid="ptm-slots">
        {slots.map((slot) => {
          const pickable = canPick(slot, now);
          return (
            <button
              key={slot.id}
              type="button"
              className="btn btn-sm num"
              disabled={!pickable}
              onClick={() => onPick(slot)}
              aria-label={pickable ? t("ptm.family.pick", { time: hhmm(slot.startsAt) }) : t("ptm.family.taken", { time: hhmm(slot.startsAt) })}
            >
              {hhmm(slot.startsAt)}
            </button>
          );
        })}
      </div>
      <p className="text-[12.5px] text-ink-3">{plural(t, "ptm.family.freeSlots", free)}</p>
    </div>
  );
}
