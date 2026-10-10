"use client";

import { BarChart3, BellRing, CalendarOff, CheckCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { attendanceApi } from "@/lib/attendance-api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatLongDate, formatPlainDate, initials, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import {
  ATTENDANCE_STATUSES,
  type AttendanceStatus,
  type RegisterView,
  type SectionDay,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { LeaveRequestsLink } from "@/components/views/portal/leave-inbox";
import { countStatuses, CountsBar, MarkLegend, presentPercent, STATUS_LABEL, statusMark } from "./attendance-shared";

const PLAIN_DATE = /^\d{4}-\d{2}-\d{2}$/;

type Marks = Record<string, AttendanceStatus>;

/** The first section still to be marked that the caller may mark, else the first one. Exported for tests. */
export function defaultSection(sections: SectionDay[]): string {
  return (sections.find((s) => s.canMark && !s.marked && s.students > 0) ?? sections[0])?.sectionId ?? "";
}

/** Sections grouped by class, in the order the API sends them (class order, then section). */
function byClass(sections: SectionDay[]): { classId: string; className: string; sections: SectionDay[] }[] {
  const groups: { classId: string; className: string; sections: SectionDay[] }[] = [];
  for (const section of sections) {
    const last = groups[groups.length - 1];
    if (last && last.classId === section.classId) last.sections.push(section);
    else groups.push({ classId: section.classId, className: section.className, sections: [section] });
  }
  return groups;
}

function marksOf(view: RegisterView): Marks {
  const marks: Marks = {};
  for (const entry of view.entries) {
    if (entry.status) marks[entry.studentId] = entry.status;
    // An unmarked register starts from approved child leave (docs/api/phase-1-portal.md); the teacher can change it.
    else if (!view.marked && entry.inSection && entry.approvedLeave) marks[entry.studentId] = entry.approvedLeave.prefill;
  }
  return marks;
}

function sameMarks(a: Marks, b: Marks): boolean {
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((key) => a[key] === b[key]);
}

/**
 * Day-wise attendance: pick a date and a section, mark every student and save. Teachers see only
 * the sections they are class teacher of; admins and principals see every section.
 */
export function AttendanceView({
  initialDate,
  initialSectionId,
}: {
  initialDate?: string;
  initialSectionId?: string;
}) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [date, setDate] = useState(() =>
    initialDate && PLAIN_DATE.test(initialDate) && initialDate <= today ? initialDate : today,
  );
  const [chosen, setChosen] = useState(initialSectionId ?? "");
  const [draft, setDraft] = useState<{ key: string; marks: Marks } | null>(null);
  const [saved, setSaved] = useState<{ key: string; view: RegisterView } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const canSettings = hasPermission(me, PERMISSIONS.settingsManage);

  const sections = useApiData(`attendance:sections:${date}`, () => attendanceApi.sections(date));
  // The previous day's list stays in sections.data while another day loads: never show it as this day's.
  const day = sections.data?.date === date ? sections.data : undefined;
  const list = day?.sections ?? [];
  const sectionId = list.some((s) => s.sectionId === chosen) ? chosen : defaultSection(list);
  const key = `${sectionId}:${date}`;
  const register = useApiData(sectionId ? `attendance:register:${key}` : null, () =>
    attendanceApi.register(sectionId, date),
  );
  const loaded =
    register.data && register.data.sectionId === sectionId && register.data.date === date ? register.data : undefined;
  const view = saved?.key === key ? saved.view : loaded;
  const base = view ? marksOf(view) : {};
  const marks = draft?.key === key ? draft.marks : base;
  const dirty = draft?.key === key && !sameMarks(draft.marks, base);
  const entries = view?.entries ?? [];
  const counts = countStatuses(entries.map((e) => marks[e.studentId]));
  const unmarked = entries.filter((e) => e.inSection && !marks[e.studentId]).length;
  const canEdit = Boolean(view?.canEdit);
  const year = day?.academicYear ?? null;
  const markedCount = list.filter((s) => s.marked).length;

  const setMark = (studentId: string, status: AttendanceStatus) => {
    setProblem(null);
    setDraft((prev) => ({ key, marks: { ...(prev?.key === key ? prev.marks : base), [studentId]: status } }));
  };

  const markAllPresent = () => {
    setProblem(null);
    const next: Marks = { ...(draft?.key === key ? draft.marks : base) };
    for (const entry of entries) if (entry.inSection) next[entry.studentId] = entry.approvedLeave?.prefill ?? "PRESENT";
    setDraft({ key, marks: next });
  };

  const onSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!view || !canEdit) return;
    if (unmarked > 0) {
      setProblem(plural(t, "attendance.v.unmarked", unmarked));
      return;
    }
    setProblem(null);
    setConfirming(true);
  };

  const save = async () => {
    if (!view) return;
    const body = {
      entries: entries
        .filter((e) => marks[e.studentId])
        .map((e) => ({ studentId: e.studentId, status: marks[e.studentId] })),
    };
    const result = await attendanceApi.saveRegister(view.sectionId, view.date, body);
    setConfirming(false);
    setSaved({ key, view: result.register });
    setDraft(null);
    sections.reload();
    const message = t("attendance.saved", { section: view.label });
    toast(result.alertsQueued > 0 ? `${message} ${plural(t, "attendance.alertsQueued", result.alertsQueued)}` : message);
  };

  const onDate = (value: string) => {
    if (!PLAIN_DATE.test(value)) return;
    setDate(value);
    setProblem(null);
  };

  return (
    <>
      <PageHead
        eyebrow={[formatLongDate(`${date}T12:00:00+05:30`, locale), year?.name].filter(Boolean).join(" · ")}
        title={t("attendance.title")}
        actions={
          <>
            <LeaveRequestsLink />
            <Link href="/app/attendance/reports" className="btn">
              <BarChart3 size={18} aria-hidden="true" />
              {t("attendance.reports")}
            </Link>
            {canSettings ? (
              <Link href="/app/attendance/settings" className="btn">
                <BellRing size={18} aria-hidden="true" />
                {t("attendance.alertSettings")}
              </Link>
            ) : null}
          </>
        }
      />

      <section className="card">
        <div className="toolbar">
          <label className="field w-full sm:w-auto">
            <span className="field-label">{t("attendance.date")}</span>
            <input
              type="date"
              className="input"
              name="date"
              value={date}
              max={today}
              min={year?.startsOn}
              onChange={(e) => onDate(e.target.value)}
            />
          </label>
          <label className="field min-w-0 flex-1 sm:max-w-sm">
            <span className="field-label">{t("attendance.section")}</span>
            <select
              className="input"
              name="section"
              value={sectionId}
              onChange={(e) => {
                setChosen(e.target.value);
                setProblem(null);
              }}
              disabled={list.length === 0}
            >
              {list.length === 0 ? <option value="">{t("attendance.noSections.short")}</option> : null}
              {byClass(list).map((group) => (
                <optgroup key={group.classId} label={group.className}>
                  {group.sections.map((s) => (
                    <option key={s.sectionId} value={s.sectionId}>
                      {`${s.label} · ${s.marked ? t("attendance.marked") : t("attendance.notMarked")}`}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
          {list.length > 1 ? (
            <p className="w-full text-[13px] text-ink-3 sm:ml-auto sm:w-auto sm:self-end" data-testid="sections-marked">
              {t("attendance.sectionsMarked", { marked: markedCount, total: list.length })}
            </p>
          ) : null}
        </div>

        {day?.holiday ? (
          <div className="alert alert-info mb-3" data-testid="attendance-holiday">
            <CalendarOff size={18} aria-hidden="true" className="mt-0.5 flex-none" />
            <span>{t("attendance.holiday", { name: day.holiday })}</span>
          </div>
        ) : null}

        {sections.error ? (
          <ErrorState error={sections.error} onRetry={sections.reload} />
        ) : !day ? (
          <LoadingRows rows={6} />
        ) : year === null ? (
          <div className="empty flex flex-col items-center gap-3" data-testid="attendance-no-year">
            <p>{t("attendance.noYear")}</p>
            {hasPermission(me, PERMISSIONS.academicsRead) ? (
              <Link href="/app/setup" className="btn">
                {t("students.openSetup")}
              </Link>
            ) : null}
          </div>
        ) : list.length === 0 ? (
          <p className="empty" data-testid="attendance-no-sections">
            {day.canMark && !hasPermission(me, PERMISSIONS.attendanceManage)
              ? t("attendance.noSections.teacher")
              : t("attendance.noSections")}
          </p>
        ) : register.error && !view ? (
          <ErrorState error={register.error} onRetry={register.reload} />
        ) : !view ? (
          <LoadingRows rows={6} />
        ) : (
          <form method="post" onSubmit={onSubmit} noValidate aria-labelledby="register-title">
            <div className="card-head mt-2">
              <div className="min-w-0">
                <h2 id="register-title">{view.label}</h2>
                <p className="mt-1 text-[13px] text-ink-3" data-testid="register-meta">
                  {view.marked && view.markedByName
                    ? t("attendance.markedBy", {
                        name: view.markedByName,
                        time: formatDateTime(view.markedAt, locale),
                      })
                    : t("attendance.notMarkedFor", { date: formatPlainDate(view.date, locale) })}
                  {view.updatedByName && view.updatedAt
                    ? ` · ${t("attendance.changedBy", {
                        name: view.updatedByName,
                        time: formatDateTime(view.updatedAt, locale),
                      })}`
                    : ""}
                </p>
              </div>
              <Pill tone={view.marked ? "good" : "warn"} dot>
                {view.marked ? t("attendance.marked") : t("attendance.notMarked")}
              </Pill>
            </div>

            {entries.length === 0 ? (
              <p className="empty">{t("attendance.noStudents")}</p>
            ) : (
              <>
                <div className="flex flex-wrap items-center gap-2.5">
                  <CountsBar counts={counts} unmarked={unmarked} testId="attendance-counts" />
                  {canEdit ? (
                    <button type="button" className="btn sm:ml-auto" onClick={markAllPresent}>
                      <CheckCheck size={18} aria-hidden="true" />
                      {t("attendance.markAllPresent")}
                    </button>
                  ) : null}
                </div>

                <ul className="att-list mt-2" aria-label={t("attendance.students")}>
                  {entries.map((entry) => (
                    <li key={entry.studentId} className="att-row" data-testid="att-row">
                      <div className="person">
                        <span className="avatar" aria-hidden="true">
                          {initials(entry.fullName)}
                        </span>
                        <span className="min-w-0">
                          <b>{entry.fullName}</b>
                          <span className="sub">
                            {[
                              entry.rollNo ? t("students.rollShort", { roll: entry.rollNo }) : null,
                              entry.inSection ? null : t("attendance.left"),
                              entry.approvedLeave
                                ? t(entry.approvedLeave.halfDay ? "portal.register.halfDayLeave" : "portal.register.onLeave")
                                : null,
                            ]
                              .filter(Boolean)
                              .join(" · ") || entry.admissionNo}
                          </span>
                        </span>
                      </div>
                      <div className="att-opts" role="group" aria-label={entry.fullName}>
                        {ATTENDANCE_STATUSES.map((status) => (
                          <button
                            key={status}
                            type="button"
                            data-v={statusMark(status)}
                            aria-pressed={marks[entry.studentId] === status}
                            aria-label={t(STATUS_LABEL[status])}
                            title={t(STATUS_LABEL[status])}
                            disabled={!canEdit}
                            onClick={() => setMark(entry.studentId, status)}
                          >
                            {statusMark(status)}
                          </button>
                        ))}
                      </div>
                    </li>
                  ))}
                </ul>

                <MarkLegend />

                {canEdit ? (
                  <div className="att-savebar">
                    <FormAlert message={problem} />
                    <p className="text-[13px] text-ink-3">
                      {presentPercent(counts) !== null
                        ? t("attendance.presentShare", { percent: presentPercent(counts) ?? 0 })
                        : t("attendance.saveHint")}
                    </p>
                    <button
                      type="submit"
                      className="btn btn-primary btn-lg"
                      disabled={view.marked && !dirty}
                      data-testid="attendance-save"
                    >
                      {view.marked ? t("attendance.saveChanges") : t("attendance.save")}
                    </button>
                  </div>
                ) : (
                  <p className="mt-3 text-[13px] text-ink-3">
                    {view.holiday ? t("attendance.holiday", { name: view.holiday }) : t("attendance.readOnly")}
                  </p>
                )}
              </>
            )}
          </form>
        )}
      </section>

      {view ? (
        <ConfirmDialog
          open={confirming}
          danger={false}
          title={t("attendance.confirm.title", { section: view.label })}
          body={t("attendance.confirm.body", {
            date: formatPlainDate(view.date, locale),
            present: counts.present,
            absent: counts.absent,
            late: counts.late,
            halfDay: counts.halfDay,
            leave: counts.leave,
          })}
          confirmLabel={view.marked ? t("attendance.saveChanges") : t("attendance.save")}
          onConfirm={save}
          onClose={() => setConfirming(false)}
        />
      ) : null}
    </>
  );
}
