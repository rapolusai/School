"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { useId, useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { ptmApi } from "@/lib/chat-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { PtmBooking, PtmEventSection, PtmTeacherSchedule } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { hhmm, timeRange } from "./chat-shared";
import { eventWhen, placeText } from "./ptm-shared";

/* Printable meeting schedules: one A4 page per teacher, or per class (/app/ptm/[id]/print). */

export type PrintBy = "teacher" | "class";

/** The bookings of a class across its teachers, by student then time. Pure, exported for tests. */
export function classBookings(sectionId: string, teachers: readonly PtmTeacherSchedule[]): PtmBooking[] {
  return teachers
    .flatMap((schedule) => schedule.slots.map((slot) => slot.booking))
    .filter((b): b is PtmBooking => b !== null && b.student.sectionId === sectionId)
    .sort(
      (a, b) =>
        (a.student.name ?? "").localeCompare(b.student.name ?? "") || a.startsAt.localeCompare(b.startsAt),
    );
}

type Printable = {
  title: string;
  date: string;
  startsAt: string;
  endsAt: string;
  mode: "IN_PERSON" | "VIDEO";
  location: string | null;
  teachers: PtmTeacherSchedule[];
  sections: PtmEventSection[];
};

/** /app/ptm/[id]/print: leadership prints every teacher's or class's schedule; a teacher prints their own. */
export function PtmPrintView({ id, initialBy }: { id: string; initialBy?: PrintBy }) {
  const { me } = useAuth();
  const manager = hasPermission(me, PERMISSIONS.ptmManage);
  const event = useApiData(manager ? `ptm:event:${id}` : null, () => ptmApi.event(id));
  const mine = useApiData(manager ? null : `ptm:my:${id}`, () => ptmApi.myEvent(id));
  const source = manager ? event : mine;
  let printable: Printable | undefined;
  if (manager && event.data) {
    printable = { ...event.data, teachers: event.data.teachers, sections: event.data.sections };
  } else if (!manager && mine.data) {
    printable = { ...mine.data.event, teachers: [mine.data.schedule], sections: [] };
  }
  return (
    <PrintFrame id={id} title={printable?.title}>
      {source.error && !source.data ? (
        <ErrorState error={source.error} onRetry={source.reload} />
      ) : !printable ? (
        <LoadingRows rows={6} />
      ) : (
        <PrintBody printable={printable} canByClass={manager} initialBy={manager ? (initialBy ?? "teacher") : "teacher"} />
      )}
    </PrintFrame>
  );
}

function PrintFrame({ id, title, children }: { id: string; title?: string; children: React.ReactNode }) {
  const { t } = useI18n();
  return (
    <>
      <PageHead
        eyebrow={title ?? t("ptm.title")}
        title={t("ptm.print.title")}
        actions={
          <>
            <Link href={`/app/ptm/${encodeURIComponent(id)}`} className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("ptm.print.back")}
            </Link>
            <button type="button" className="btn btn-primary" onClick={() => window.print()} disabled={!title}>
              <Printer size={18} aria-hidden="true" />
              {t("reports.print")}
            </button>
          </>
        }
      />
      {children}
    </>
  );
}

function PrintBody({ printable, canByClass, initialBy }: { printable: Printable; canByClass: boolean; initialBy: PrintBy }) {
  const { t } = useI18n();
  const radio = useId();
  const [by, setBy] = useState<PrintBy>(initialBy);
  const [which, setWhich] = useState("");
  const teachers = printable.teachers.filter((s) => !which || s.teacherId === which);
  const sections = printable.sections.filter((s) => !which || s.id === which);
  return (
    <>
      <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("ptm.print.options")}>
        {canByClass ? (
          <div className="seg" role="radiogroup" aria-label={t("ptm.print.by")}>
            {(["teacher", "class"] as const).map((option) => (
              <label key={option}>
                <input
                  type="radio"
                  name={radio}
                  value={option}
                  checked={by === option}
                  onChange={() => {
                    setBy(option);
                    setWhich("");
                  }}
                />
                {t(option === "teacher" ? "ptm.print.byTeacher" : "ptm.print.byClass")}
              </label>
            ))}
          </div>
        ) : null}
        {canByClass ? (
          <label className="field w-full sm:w-auto sm:min-w-52">
            <span className="field-label">{by === "teacher" ? t("ptm.print.teacher") : t("ptm.print.class")}</span>
            <select className="input" name="which" value={which} onChange={(e) => setWhich(e.target.value)}>
              <option value="">{by === "teacher" ? t("ptm.print.allTeachers") : t("ptm.print.allClasses")}</option>
              {by === "teacher"
                ? printable.teachers.map((s) => (
                    <option key={s.teacherId} value={s.teacherId}>
                      {s.name ?? "—"}
                    </option>
                  ))
                : printable.sections.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.label}
                    </option>
                  ))}
            </select>
          </label>
        ) : null}
      </form>
      <div className="card report-doc" data-testid="ptm-print">
        {by === "teacher"
          ? teachers.map((schedule) => <TeacherPage key={schedule.ptmTeacherId} printable={printable} schedule={schedule} />)
          : sections.map((section) => (
              <ClassPage key={section.id} printable={printable} section={section} bookings={classBookings(section.id, printable.teachers)} />
            ))}
        {(by === "teacher" ? teachers.length : sections.length) === 0 ? <p className="empty">{t("ptm.noTeachers")}</p> : null}
      </div>
    </>
  );
}

function PageTop({ printable, heading, sub }: { printable: Printable; heading: string; sub: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  const [printedAt] = useState(() => new Date());
  return (
    <header className="mb-3 border-b border-line pb-2">
      <p className="text-[13px] font-bold">{me?.tenant?.name}</p>
      <h2 className="text-[18px]">{heading}</h2>
      <p className="text-[13px]">{sub}</p>
      <p className="text-[12.5px] text-ink-3">
        {printable.title} · {eventWhen(printable, locale)} · {placeText(t, printable)}
      </p>
      <p className="text-[11.5px] text-ink-3">
        {t("reports.printedBy", { date: formatDateTime(printedAt, locale), name: me?.name ?? "" })}
      </p>
    </header>
  );
}

function TeacherPage({ printable, schedule }: { printable: Printable; schedule: PtmTeacherSchedule }) {
  const { t } = useI18n();
  return (
    <section className="ptm-print-page" data-testid="ptm-print-teacher">
      <PageTop
        printable={printable}
        heading={schedule.name ?? "—"}
        sub={`${schedule.kind === "CLASS_TEACHER" ? t("ptm.kind.CLASS_TEACHER") : schedule.subjects || t("ptm.kind.SUBJECT_TEACHER")} · ${timeRange(
          schedule.availableFrom,
          schedule.availableTo,
        )} · ${plural(t, "ptm.card.booked", schedule.booked, { slots: schedule.booked + schedule.free })}`}
      />
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th scope="col">{t("ptm.col.time")}</th>
              <th scope="col">{t("ptm.col.student")}</th>
              <th scope="col">{t("ptm.col.class")}</th>
              <th scope="col">{t("ptm.col.parent")}</th>
              <th scope="col">{t("ptm.print.came")}</th>
              <th scope="col">{t("ptm.print.notes")}</th>
            </tr>
          </thead>
          <tbody>
            {schedule.slots.map((slot) => (
              <tr key={slot.id}>
                <td className="num whitespace-nowrap">{timeRange(slot.startsAt, slot.endsAt)}</td>
                <td>{slot.booking ? (slot.booking.student.name ?? "—") : slot.blocked ? t("ptm.slot.blocked") : "—"}</td>
                <td>{slot.booking?.student.classLabel ?? ""}</td>
                <td>{slot.booking ? (slot.booking.parentName ?? t("ptm.slot.bySchoolShort")) : ""}</td>
                <td>{slot.booking?.attendance ? t(`ptm.attendance.${slot.booking.attendance}`) : ""}</td>
                <td className="w-1/4" />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ClassPage({ printable, section, bookings }: { printable: Printable; section: PtmEventSection; bookings: PtmBooking[] }) {
  const { t } = useI18n();
  return (
    <section className="ptm-print-page" data-testid="ptm-print-class">
      <PageTop
        printable={printable}
        heading={section.label}
        sub={`${section.classTeacherName ? t("ptm.sections.classTeacher", { name: section.classTeacherName }) : t("ptm.sections.noClassTeacher")} · ${t(
          "ptm.sections.booked",
          { booked: section.studentsBooked, students: section.students },
        )}`}
      />
      {bookings.length === 0 ? (
        <p className="empty">{t("ptm.print.noBookings")}</p>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t("ptm.col.student")}</th>
                <th scope="col">{t("ptm.col.teacher")}</th>
                <th scope="col">{t("ptm.col.time")}</th>
                <th scope="col">{t("ptm.col.parent")}</th>
                <th scope="col">{t("ptm.print.came")}</th>
              </tr>
            </thead>
            <tbody>
              {bookings.map((b) => (
                <tr key={b.id}>
                  <td>{b.student.name ?? "—"}</td>
                  <td>{b.teacherName ?? "—"}</td>
                  <td className="num whitespace-nowrap">{hhmm(b.startsAt)}</td>
                  <td>{b.parentName ?? t("ptm.slot.bySchoolShort")}</td>
                  <td>{b.attendance ? t(`ptm.attendance.${b.attendance}`) : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
