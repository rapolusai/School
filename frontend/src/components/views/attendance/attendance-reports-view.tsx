"use client";

import { ArrowLeft, Download } from "lucide-react";
import Link from "next/link";
import { useId, useRef, useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { attendanceApi, saveTextFile } from "@/lib/attendance-api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import type { MonthRegister, SectionDay } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { CountsBar, formatPercent, MarkBadge, MarkLegend, STATUS_LABEL, statusMark } from "./attendance-shared";

export const REPORT_TABS = ["month", "student"] as const;
export type ReportTab = (typeof REPORT_TABS)[number];

const TAB_LABELS: Record<ReportTab, MessageKey> = {
  month: "attendance.reports.tab.month",
  student: "attendance.reports.tab.student",
};

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** "Class 5 A" + "2026-10" → "attendance-class-5-a-2026-10.csv". Exported for tests. */
export function csvFileName(label: string, month: string): string {
  const slug = label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
  return `attendance-${slug || "section"}-${month}.csv`;
}

function SectionSelect({
  sections,
  value,
  onChange,
}: {
  sections: SectionDay[];
  value: string;
  onChange: (id: string) => void;
}) {
  const { t } = useI18n();
  return (
    <label className="field min-w-0 flex-1 sm:max-w-xs">
      <span className="field-label">{t("attendance.section")}</span>
      <select className="input" name="section" value={value} onChange={(e) => onChange(e.target.value)}>
        {sections.map((s) => (
          <option key={s.sectionId} value={s.sectionId}>
            {s.label}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Reports: a section's month register (students by days) and one student's summary. */
export function AttendanceReportsView() {
  const { t } = useI18n();
  const baseId = useId();
  const [tab, setTab] = useState<ReportTab>("month");
  const tabRefs = useRef<Partial<Record<ReportTab, HTMLButtonElement | null>>>({});
  const [today] = useState(() => todayInIndia());
  const [sectionChoice, setSectionChoice] = useState("");
  const [month, setMonth] = useState(today.slice(0, 7));

  const sections = useApiData("attendance:sections:report", () => attendanceApi.sections());
  const list = sections.data?.sections ?? [];
  const sectionId = list.some((s) => s.sectionId === sectionChoice) ? sectionChoice : (list[0]?.sectionId ?? "");

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const index = REPORT_TABS.indexOf(tab);
    let next: ReportTab | null = null;
    if (event.key === "ArrowRight") next = REPORT_TABS[(index + 1) % REPORT_TABS.length];
    else if (event.key === "ArrowLeft") next = REPORT_TABS[(index - 1 + REPORT_TABS.length) % REPORT_TABS.length];
    if (!next) return;
    event.preventDefault();
    setTab(next);
    tabRefs.current[next]?.focus();
  };

  return (
    <>
      <PageHead
        eyebrow={sections.data?.academicYear?.name ?? (sections.data ? t("attendance.noYear.short") : t("common.loading"))}
        title={t("attendance.reports.title")}
        actions={
          <Link href="/app/attendance" className="btn">
            <ArrowLeft size={18} aria-hidden="true" />
            {t("attendance.backToMarking")}
          </Link>
        }
      />

      <div role="tablist" aria-label={t("attendance.reports.title")} className="tabs" onKeyDown={onKeyDown}>
        {REPORT_TABS.map((key) => (
          <button
            key={key}
            ref={(el) => {
              tabRefs.current[key] = el;
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-${key}`}
            aria-selected={tab === key}
            aria-controls={`${baseId}-panel-${key}`}
            tabIndex={tab === key ? 0 : -1}
            onClick={() => setTab(key)}
          >
            {t(TAB_LABELS[key])}
          </button>
        ))}
      </div>

      <section
        className="card"
        role="tabpanel"
        id={`${baseId}-panel-${tab}`}
        aria-labelledby={`${baseId}-tab-${tab}`}
      >
        {sections.error && !sections.data ? (
          <ErrorState error={sections.error} onRetry={sections.reload} />
        ) : !sections.data ? (
          <LoadingRows rows={6} />
        ) : list.length === 0 ? (
          <p className="empty">{sections.data.academicYear ? t("attendance.noSections") : t("attendance.noYear")}</p>
        ) : tab === "month" ? (
          <MonthPanel
            sections={list}
            sectionId={sectionId}
            onSection={setSectionChoice}
            month={month}
            onMonth={setMonth}
            today={today}
          />
        ) : (
          <StudentPanel sections={list} sectionId={sectionId} onSection={setSectionChoice} today={today} />
        )}
      </section>
    </>
  );
}

function MonthPanel({
  sections,
  sectionId,
  onSection,
  month,
  onMonth,
  today,
}: {
  sections: SectionDay[];
  sectionId: string;
  onSection: (id: string) => void;
  month: string;
  onMonth: (month: string) => void;
  today: string;
}) {
  const { t } = useI18n();
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const register = useApiData(sectionId ? `attendance:month:${sectionId}:${month}` : null, () =>
    attendanceApi.month(sectionId, month),
  );
  const data = register.data && register.data.sectionId === sectionId && register.data.month === month ? register.data : undefined;

  const download = async () => {
    if (!data) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      const csv = await attendanceApi.monthCsv(sectionId, month);
      saveTextFile(csvFileName(data.label, month), csv);
    } catch (caught) {
      setDownloadError(errorMessage(toApiError(caught), t));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="toolbar" style={{ marginBottom: 0 }}>
        <SectionSelect sections={sections} value={sectionId} onChange={onSection} />
        <label className="field w-full sm:w-auto">
          <span className="field-label">{t("attendance.month")}</span>
          <input
            type="month"
            className="input"
            name="month"
            value={month}
            max={today.slice(0, 7)}
            onChange={(e) => {
              if (MONTH.test(e.target.value)) onMonth(e.target.value);
            }}
          />
        </label>
        <button
          type="button"
          className="btn w-full sm:ml-auto sm:w-auto sm:self-end"
          onClick={download}
          disabled={!data || downloading}
        >
          <Download size={18} aria-hidden="true" />
          {downloading ? t("common.working") : t("attendance.downloadCsv")}
        </button>
      </div>
      <FormAlert message={downloadError} />

      {register.error && !data ? (
        <ErrorState error={register.error} onRetry={register.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <MonthGrid register={data} />
      )}
    </div>
  );
}

/** The register grid: a row per student, a column per day. Scrolls sideways inside its card on phones. */
export function MonthGrid({ register }: { register: MonthRegister }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const weekday = new Intl.DateTimeFormat(locale, { weekday: "narrow", timeZone: "UTC" });
  const dayLabel = (date: string) => weekday.format(new Date(`${date}T00:00:00Z`));
  const isSunday = (date: string) => new Date(`${date}T00:00:00Z`).getUTCDay() === 0;

  return (
    <>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[13.5px]" data-testid="month-summary">
        <span>
          {t("attendance.daysMarked")}: <b className="num">{register.daysMarked}</b>
        </span>
        <span>
          {t("attendance.attendance")}: <b className="num">{formatPercent(register.presentPercent)}</b>
        </span>
      </div>
      {register.students.length === 0 ? (
        <p className="empty">{t("attendance.noStudents")}</p>
      ) : (
        <div className="table-wrap" data-testid="month-grid-scroll">
          <table className="month-table" data-testid="month-grid">
            <caption className="sr-only">{t("attendance.monthCaption", { section: register.label, month: register.month })}</caption>
            <thead>
              <tr>
                <th scope="col" className="sticky-col">
                  {t("attendance.col.student")}
                </th>
                {register.days.map((day) => (
                  <th
                    key={day.date}
                    scope="col"
                    className={isSunday(day.date) ? "is-sunday" : undefined}
                    title={formatPlainDate(day.date, locale)}
                  >
                    <span className="block num">{Number(day.date.slice(8))}</span>
                    <span className="block text-[10.5px] font-medium">{dayLabel(day.date)}</span>
                  </th>
                ))}
                <th scope="col" className="r">
                  {t("attendance.col.days")}
                </th>
                <th scope="col" className="r">
                  {t("attendance.col.percent")}
                </th>
              </tr>
            </thead>
            <tbody>
              {register.students.map((student) => (
                <tr key={student.studentId}>
                  <th scope="row" className="sticky-col">
                    <span className="block truncate font-semibold">{student.fullName}</span>
                    <span className="block text-[12px] font-normal text-ink-3">
                      {[
                        student.rollNo ? t("students.rollShort", { roll: student.rollNo }) : null,
                        student.inSection ? null : t("attendance.left"),
                      ]
                        .filter(Boolean)
                        .join(" · ") || student.admissionNo}
                    </span>
                  </th>
                  {student.marks.map((mark, i) => (
                    <td key={register.days[i]?.date ?? i} className={isSunday(register.days[i]?.date ?? "") ? "is-sunday" : undefined}>
                      {mark ? <MarkBadge mark={mark} /> : <span className="text-ink-3" aria-hidden="true">·</span>}
                    </td>
                  ))}
                  <td className="r num">{student.daysMarked}</td>
                  <td className="r num font-semibold">{formatPercent(student.presentPercent)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" className="sticky-col">
                  {t("attendance.row.present")}
                </th>
                {register.days.map((day) => (
                  <td key={day.date} className="num">
                    {day.marked ? day.counts.present + day.counts.late + day.counts.halfDay : ""}
                  </td>
                ))}
                <td />
                <td />
              </tr>
              <tr>
                <th scope="row" className="sticky-col">
                  {t("attendance.row.absent")}
                </th>
                {register.days.map((day) => (
                  <td key={day.date} className="num">
                    {day.marked ? day.counts.absent : ""}
                  </td>
                ))}
                <td />
                <td />
              </tr>
              <tr>
                <th scope="row" className="sticky-col">
                  {t("attendance.row.percent")}
                </th>
                {register.days.map((day) => (
                  <td key={day.date} className="num text-[11.5px]">
                    {day.marked && day.presentPercent !== null ? Math.round(day.presentPercent) : ""}
                  </td>
                ))}
                <td className="r num">{register.daysMarked}</td>
                <td className="r num font-semibold">{formatPercent(register.presentPercent)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
      <MarkLegend />
      <p className="text-[12.5px] text-ink-3">{t("attendance.percentRule")}</p>
    </>
  );
}

function StudentPanel({
  sections,
  sectionId,
  onSection,
  today,
}: {
  sections: SectionDay[];
  sectionId: string;
  onSection: (id: string) => void;
  today: string;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const month = today.slice(0, 7);
  const [studentChoice, setStudentChoice] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const roster = useApiData(sectionId ? `attendance:month:${sectionId}:${month}` : null, () =>
    attendanceApi.month(sectionId, month),
  );
  const students = roster.data?.sectionId === sectionId ? roster.data.students : [];
  const studentId = students.some((s) => s.studentId === studentChoice) ? studentChoice : (students[0]?.studentId ?? "");
  const range = from && to && to < from ? null : { from: from || undefined, to: to || undefined };
  const summary = useApiData(
    studentId && range ? `attendance:student:${studentId}:${from}:${to}` : null,
    () => attendanceApi.studentSummary(studentId, range?.from, range?.to),
  );
  const data = summary.data?.studentId === studentId ? summary.data : undefined;

  return (
    <div className="flex flex-col gap-3">
      <div className="toolbar" style={{ marginBottom: 0 }}>
        <SectionSelect sections={sections} value={sectionId} onChange={onSection} />
        <label className="field min-w-0 flex-1 sm:max-w-xs">
          <span className="field-label">{t("attendance.student")}</span>
          <select
            className="input"
            name="student"
            value={studentId}
            onChange={(e) => setStudentChoice(e.target.value)}
            disabled={students.length === 0}
          >
            {students.map((s) => (
              <option key={s.studentId} value={s.studentId}>
                {s.fullName}
              </option>
            ))}
          </select>
        </label>
        <label className="field w-[calc(50%-4px)] sm:w-auto">
          <span className="field-label">{t("attendance.from")}</span>
          <input type="date" className="input" name="from" value={from} max={today} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="field w-[calc(50%-4px)] sm:w-auto">
          <span className="field-label">{t("attendance.to")}</span>
          <input type="date" className="input" name="to" value={to} max={today} onChange={(e) => setTo(e.target.value)} />
        </label>
      </div>
      {range === null ? <FormAlert message={t("attendance.v.range")} /> : null}

      {roster.error && !roster.data ? (
        <ErrorState error={roster.error} onRetry={roster.reload} />
      ) : !roster.data ? (
        <LoadingRows rows={3} />
      ) : students.length === 0 ? (
        <p className="empty">{t("attendance.noStudents")}</p>
      ) : summary.error && !data ? (
        <ErrorState error={summary.error} onRetry={summary.reload} />
      ) : !data ? (
        range === null ? null : <LoadingRows rows={3} />
      ) : (
        <div className="flex flex-col gap-3" data-testid="student-summary">
          <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
            <h2>{data.fullName}</h2>
            <span className="text-[13.5px] text-ink-3">
              {t("attendance.range", {
                from: formatPlainDate(data.from, locale),
                to: formatPlainDate(data.to, locale),
              })}
            </span>
          </div>
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
            <span className="kpi-value">{formatPercent(data.presentPercent)}</span>
            <span className="text-[13.5px] text-ink-2">
              {t("attendance.daysMarked")}: <b className="num">{data.daysMarked}</b>
            </span>
          </div>
          <CountsBar counts={data.counts} />
          <div>
            <h3 className="mb-1.5 text-[14px] font-semibold">{t("attendance.absences")}</h3>
            {data.absences.length === 0 ? (
              <p className="text-[13.5px] text-ink-3">{t("attendance.noAbsences")}</p>
            ) : (
              <ul className="flex flex-wrap gap-1.5">
                {data.absences.map((date) => (
                  <li key={date} className="chip">
                    {formatPlainDate(date, locale)}
                  </li>
                ))}
              </ul>
            )}
          </div>
          {data.days.length > 0 ? (
            <details>
              <summary className="link cursor-pointer text-[13.5px]">{t("attendance.everyDay")}</summary>
              <ul className="mt-2 flex flex-wrap gap-1.5">
                {data.days.map((day) => (
                  <li key={day.date} className="chip">
                    <MarkBadge mark={statusMark(day.status)} />
                    <span className="sr-only">{t(STATUS_LABEL[day.status])}</span>
                    {formatPlainDate(day.date, locale)}
                  </li>
                ))}
              </ul>
            </details>
          ) : null}
        </div>
      )}
    </div>
  );
}
