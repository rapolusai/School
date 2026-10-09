"use client";

import { CheckCheck, Download, IdCard } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { saveTextFile } from "@/lib/attendance-api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { DISPLAY_TIME_ZONE, formatDateTime, formatLongDate, formatPlainDate, initials, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { staffApi, staffAttendanceApi } from "@/lib/staff-api";
import {
  STAFF_MARKABLE_STATUSES,
  type StaffDayEntry,
  type StaffDayRow,
  type StaffDaySheet,
  type StaffMarkableStatus,
  type StaffMonthReport,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import {
  formatClock,
  formatNumber,
  STAFF_DAY_LABEL,
  StaffCountsBar,
  staffDayTone,
  staffMark,
  StaffMarkBadge,
  StaffMarkLegend,
  Tabs,
} from "./staff-shared";

export const STAFF_ATTENDANCE_TABS = ["day", "month"] as const;
export type StaffAttendanceTab = (typeof STAFF_ATTENDANCE_TABS)[number];

const PLAIN_DATE = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

/** One editable row of the daily sheet. */
export type RowDraft = { status: StaffMarkableStatus | null; checkIn: string; checkOut: string };
export type SheetDraft = Record<string, RowDraft>;

/** Rows an admin may change: everyone not on approved leave that day. */
export function editableRows(sheet: StaffDaySheet): StaffDayRow[] {
  return sheet.rows.filter((r) => !r.onLeaveRequest && r.status !== "ON_LEAVE");
}

/** The sheet as saved, in the shape the form edits. Pure and exported for tests. */
export function draftOf(sheet: StaffDaySheet): SheetDraft {
  const draft: SheetDraft = {};
  for (const row of editableRows(sheet)) {
    draft[row.userId] = {
      status: row.status === "ON_LEAVE" ? null : row.status,
      checkIn: row.checkIn ?? "",
      checkOut: row.checkOut ?? "",
    };
  }
  return draft;
}

function sameRow(a: RowDraft | undefined, b: RowDraft | undefined): boolean {
  return (
    (a?.status ?? null) === (b?.status ?? null) &&
    (a?.checkIn ?? "") === (b?.checkIn ?? "") &&
    (a?.checkOut ?? "") === (b?.checkOut ?? "")
  );
}

/** The rows that changed, as API entries (marked rows only). Pure and exported for tests. */
export function changedEntries(base: SheetDraft, draft: SheetDraft): StaffDayEntry[] {
  const entries: StaffDayEntry[] = [];
  for (const [userId, row] of Object.entries(draft)) {
    if (!row.status || sameRow(base[userId], row)) continue;
    const absent = row.status === "ABSENT";
    entries.push({
      userId,
      status: row.status,
      checkIn: absent ? null : row.checkIn || null,
      checkOut: absent ? null : row.checkOut || null,
    });
  }
  return entries;
}

/**
 * The API's checks on each entry: times only on a present or half day, a check-out needs a
 * check-in and must not be before it, and no time after `nowTime` today. Pure and exported for tests.
 */
export function validateEntries(
  entries: StaffDayEntry[],
  date: string,
  today: string,
  nowTime: string,
): Record<string, MessageKey> {
  const problems: Record<string, MessageKey> = {};
  for (const e of entries) {
    const inTime = e.checkIn ?? "";
    const outTime = e.checkOut ?? "";
    if ((inTime && !TIME.test(inTime)) || (outTime && !TIME.test(outTime))) problems[e.userId] = "staffAttendance.v.time";
    else if (outTime && !inTime) problems[e.userId] = "staffAttendance.v.outWithoutIn";
    else if (inTime && outTime && outTime < inTime) problems[e.userId] = "staffAttendance.v.outBeforeIn";
    else if (date === today && ((inTime && inTime > nowTime) || (outTime && outTime > nowTime)))
      problems[e.userId] = "staffAttendance.v.future";
  }
  return problems;
}

function timeInIndia(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: DISPLAY_TIME_ZONE,
  }).format(now);
}

/** A year back from today, the oldest day the sheet may change. */
function yearBack(today: string): string {
  return `${Number(today.slice(0, 4)) - 1}${today.slice(4)}`;
}

/** The staff daily sheet and the monthly report. */
export function StaffAttendanceView({ initialTab, initialDate }: { initialTab?: string; initialDate?: string }) {
  const { t } = useI18n();
  const [tab, setTab] = useState<StaffAttendanceTab>(initialTab === "month" ? "month" : "day");
  const [today] = useState(() => todayInIndia());
  const tabs = [
    { key: "day" as const, label: t("staffAttendance.tab.day") },
    { key: "month" as const, label: t("staffAttendance.tab.month") },
  ];
  return (
    <>
      <PageHead
        eyebrow={t("staffAttendance.eyebrow")}
        title={t("staffAttendance.title")}
        actions={
          <Link href="/app/staff" className="btn">
            <IdCard size={18} aria-hidden="true" />
            {t("nav.staff")}
          </Link>
        }
      />
      <Tabs tabs={tabs} value={tab} onChange={setTab} label={t("staffAttendance.title")}>
        {tab === "day" ? (
          <DaySheetPanel
            today={today}
            initialDate={initialDate && PLAIN_DATE.test(initialDate) && initialDate <= today ? initialDate : today}
          />
        ) : (
          <MonthPanel today={today} />
        )}
      </Tabs>
    </>
  );
}

function DaySheetPanel({ today, initialDate }: { today: string; initialDate: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = hasPermission(me, PERMISSIONS.staffAttendanceManage);
  const [date, setDate] = useState(initialDate);
  const [draft, setDraft] = useState<{ date: string; rows: SheetDraft } | null>(null);
  const [saved, setSaved] = useState<StaffDaySheet | null>(null);
  const [problems, setProblems] = useState<Partial<Record<string, MessageKey>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const day = useApiData(`staff-attendance:day:${date}`, () => staffAttendanceApi.day(date));
  const loaded = day.data?.date === date ? day.data : undefined;
  const sheet = saved?.date === date ? saved : loaded;
  const base = sheet ? draftOf(sheet) : {};
  const rows = draft?.date === date ? draft.rows : base;
  const entries = changedEntries(base, rows);
  const canEdit = Boolean(sheet?.canEdit) && canManage;

  const update = (userId: string, change: Partial<RowDraft>) => {
    setFormError(null);
    setProblems((prev) => (prev[userId] ? { ...prev, [userId]: undefined } : prev));
    setDraft((prev) => {
      const current = { ...(prev?.date === date ? prev.rows : base) };
      const row = current[userId] ?? { status: null, checkIn: "", checkOut: "" };
      const next = { ...row, ...change };
      if (next.status === "ABSENT") {
        next.checkIn = "";
        next.checkOut = "";
      }
      current[userId] = next;
      return { date, rows: current };
    });
  };

  const markAllPresent = () => {
    if (!sheet) return;
    const next = { ...rows };
    for (const row of editableRows(sheet)) {
      if (row.onRoll && !next[row.userId]?.status) next[row.userId] = { status: "PRESENT", checkIn: "", checkOut: "" };
    }
    setDraft({ date, rows: next });
  };

  const onDate = (value: string) => {
    if (!PLAIN_DATE.test(value)) return;
    setDate(value);
    setSaved(null);
    setProblems({});
    setFormError(null);
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!sheet || !canEdit || entries.length === 0 || saving) return;
    const found = validateEntries(entries, date, today, timeInIndia());
    if (Object.keys(found).length > 0) {
      setProblems(found);
      setFormError(t("validation.fixErrors"));
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      const result = await staffAttendanceApi.saveDay(date, { entries });
      setSaved(result.sheet);
      setDraft(null);
      setProblems({});
      const parts = [
        result.marked > 0 ? plural(t, "staffAttendance.savedMarked", result.marked) : null,
        result.corrected > 0 ? plural(t, "staffAttendance.savedCorrected", result.corrected) : null,
      ].filter(Boolean);
      toast(parts.length > 0 ? parts.join(" ") : t("staffAttendance.savedNothing"));
    } catch (caught) {
      setFormError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  const unmarked = sheet ? editableRows(sheet).filter((r) => r.onRoll && !rows[r.userId]?.status).length : 0;

  return (
    <section className="card">
      <div className="toolbar">
        <label className="field w-full sm:w-auto">
          <span className="field-label">{t("staffAttendance.date")}</span>
          <input
            type="date"
            className="input"
            name="date"
            value={date}
            max={today}
            min={yearBack(today)}
            onChange={(e) => onDate(e.target.value)}
          />
        </label>
        {sheet ? (
          <p className="w-full text-[13.5px] text-ink-2 sm:ml-auto sm:w-auto sm:self-end">
            {formatLongDate(`${sheet.date}T12:00:00+05:30`, locale)}
            {!sheet.workingDay ? ` · ${t("staffAttendance.offDay")}` : ""}
          </p>
        ) : null}
      </div>

      {day.error && !sheet ? (
        <ErrorState error={day.error} onRetry={day.reload} />
      ) : !sheet ? (
        <LoadingRows rows={6} />
      ) : sheet.rows.length === 0 ? (
        <p className="empty" data-testid="staff-sheet-empty">
          {t("staffAttendance.noStaff")}
        </p>
      ) : (
        <form method="post" onSubmit={onSubmit} noValidate aria-label={t("staffAttendance.sheet")}>
          <div className="flex flex-wrap items-center gap-2.5">
            <StaffCountsBar counts={sheet.counts} notMarked={sheet.notMarked} testId="staff-sheet-counts" />
            {canEdit && unmarked > 0 ? (
              <button type="button" className="btn sm:ml-auto" onClick={markAllPresent}>
                <CheckCheck size={18} aria-hidden="true" />
                {t("staffAttendance.markAllPresent")}
              </button>
            ) : null}
          </div>

          <ul className="att-list mt-2" aria-label={t("staffAttendance.sheet")} data-testid="staff-sheet">
            {sheet.rows.map((row) => (
              <SheetRow
                key={row.userId}
                row={row}
                draft={rows[row.userId]}
                canEdit={canEdit}
                problem={problems[row.userId]}
                onChange={(change) => update(row.userId, change)}
              />
            ))}
          </ul>

          <StaffMarkLegend />

          {canEdit ? (
            <div className="att-savebar">
              <FormAlert message={formError} />
              <p className="text-[13px] text-ink-3">
                {entries.length > 0 ? plural(t, "staffAttendance.changes", entries.length) : t("staffAttendance.saveHint")}
              </p>
              <button
                type="submit"
                className="btn btn-primary btn-lg"
                disabled={entries.length === 0 || saving}
                data-testid="staff-sheet-save"
              >
                {saving ? t("common.saving") : t("staffAttendance.save")}
              </button>
            </div>
          ) : (
            <p className="mt-3 text-[13px] text-ink-3">
              {canManage ? t("staffAttendance.tooOld") : t("staffAttendance.readOnly")}
            </p>
          )}
        </form>
      )}
    </section>
  );
}

/** One person on the daily sheet: P / H / A and the times, or their approved leave. */
function SheetRow({
  row,
  draft: rowDraft,
  canEdit: editable,
  problem,
  onChange,
}: {
  row: StaffDayRow;
  draft: RowDraft | undefined;
  canEdit: boolean;
  problem: MessageKey | undefined;
  onChange: (change: Partial<RowDraft>) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const locked = row.onLeaveRequest || row.status === "ON_LEAVE";
  const status = locked ? row.status : (rowDraft?.status ?? null);
  const meta = [
    row.employeeCode,
    row.designation,
    row.departmentName,
    row.onRoll ? null : t("staffAttendance.notOnRoll"),
  ]
    .filter(Boolean)
    .join(" · ");
  const recorded = [
    row.checkInAt ? `${t("staffAttendance.in")} ${formatClock(row.checkInAt, locale)}` : null,
    row.checkOutAt ? `${t("staffAttendance.out")} ${formatClock(row.checkOutAt, locale)}` : null,
    row.source === "SELF" ? t("staffAttendance.source.SELF") : null,
    row.checkInNote ? `“${row.checkInNote}”` : null,
    row.checkOutNote ? `“${row.checkOutNote}”` : null,
    row.editedAt && row.updatedByName
      ? t("staffAttendance.editedBy", { name: row.updatedByName, time: formatDateTime(row.editedAt, locale) })
      : null,
  ]
    .filter(Boolean)
    .join(" · ");
  return (
    <li className="att-row" data-testid="staff-sheet-row">
      <div className="person">
        <span className="avatar" aria-hidden="true">
          {initials(row.name)}
        </span>
        <span className="min-w-0">
          <b>{row.name}</b>
          <span className="sub">{meta || "—"}</span>
          {recorded ? <span className="sub">{recorded}</span> : null}
        </span>
      </div>
      {locked ? (
        <Pill tone="accent" dot>
          {row.status === "HALF_DAY" ? t("staffAttendance.halfDayLeave") : t("staffAttendance.onLeave")}
        </Pill>
      ) : editable ? (
        <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
          <div className="att-opts" role="group" aria-label={row.name}>
            {STAFF_MARKABLE_STATUSES.map((s) => (
              <button
                key={s}
                type="button"
                data-v={staffMark(s)}
                aria-pressed={status === s}
                aria-label={t(STAFF_DAY_LABEL[s])}
                title={t(STAFF_DAY_LABEL[s])}
                onClick={() => onChange({ status: s })}
              >
                {staffMark(s)}
              </button>
            ))}
          </div>
          {status && status !== "ABSENT" ? (
            <div className="flex gap-2">
              <label className="flex flex-col text-[12px] text-ink-3">
                {t("staffAttendance.in")}
                <input
                  type="time"
                  className="input w-[112px]"
                  name={`in-${row.userId}`}
                  value={rowDraft?.checkIn ?? ""}
                  onChange={(e) => onChange({ checkIn: e.target.value })}
                  aria-label={t("staffAttendance.inFor", { name: row.name })}
                />
              </label>
              <label className="flex flex-col text-[12px] text-ink-3">
                {t("staffAttendance.out")}
                <input
                  type="time"
                  className="input w-[112px]"
                  name={`out-${row.userId}`}
                  value={rowDraft?.checkOut ?? ""}
                  onChange={(e) => onChange({ checkOut: e.target.value })}
                  aria-label={t("staffAttendance.outFor", { name: row.name })}
                />
              </label>
            </div>
          ) : null}
        </div>
      ) : status ? (
        <Pill tone={staffDayTone(status)} dot>
          {t(STAFF_DAY_LABEL[status])}
        </Pill>
      ) : (
        <Pill tone="warn">{t("staff.notMarked")}</Pill>
      )}
      {problem ? (
        <p className="field-error w-full" role="alert">
          {t(problem)}
        </p>
      ) : null}
    </li>
  );
}

/** "staff-attendance-2026-10.csv", or with the department: "staff-attendance-science-2026-10.csv". */
export function staffCsvFileName(month: string, departmentName?: string | null): string {
  const slug = (departmentName ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
  return slug ? `staff-attendance-${slug}-${month}.csv` : `staff-attendance-${month}.csv`;
}

function MonthPanel({ today }: { today: string }) {
  const { t } = useI18n();
  const [month, setMonth] = useState(today.slice(0, 7));
  const [departmentId, setDepartmentId] = useState("");
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const departments = useApiData("staff:departments", staffApi.departments);
  const report = useApiData(`staff-attendance:month:${month}:${departmentId}`, () =>
    staffAttendanceApi.month(month, departmentId || undefined),
  );
  const data =
    report.data && report.data.month === month && (report.data.departmentId ?? "") === departmentId
      ? report.data
      : undefined;
  const departmentName = departments.data?.find((d) => d.id === departmentId)?.name ?? null;

  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const csv = await staffAttendanceApi.monthCsv(month, departmentId || undefined);
      saveTextFile(staffCsvFileName(month, departmentName), csv);
    } catch (caught) {
      setDownloadError(errorMessage(toApiError(caught), t));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <section className="card flex flex-col gap-3">
      <div className="toolbar" style={{ marginBottom: 0 }}>
        <label className="field w-full sm:w-auto">
          <span className="field-label">{t("staffAttendance.month")}</span>
          <input
            type="month"
            className="input"
            name="month"
            value={month}
            max={today.slice(0, 7)}
            onChange={(e) => {
              if (MONTH.test(e.target.value)) setMonth(e.target.value);
            }}
          />
        </label>
        <label className="field min-w-0 flex-1 sm:max-w-xs">
          <span className="field-label">{t("staff.filter.department")}</span>
          <select className="input" name="departmentId" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
            <option value="">{t("staff.filter.allDepartments")}</option>
            {(departments.data ?? []).map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="btn w-full sm:ml-auto sm:w-auto sm:self-end"
          onClick={download}
          disabled={!data || downloading}
          data-testid="staff-csv"
        >
          <Download size={18} aria-hidden="true" />
          {downloading ? t("common.working") : t("staffAttendance.downloadCsv")}
        </button>
      </div>
      <FormAlert message={downloadError} />
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <StaffMonthGrid report={data} />
      )}
    </section>
  );
}

/** Staff by days, with each person's totals. Scrolls sideways inside its card on phones. */
export function StaffMonthGrid({ report }: { report: StaffMonthReport }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const weekday = new Intl.DateTimeFormat(locale, { weekday: "narrow", timeZone: "UTC" });
  const dayLabel = (date: string) => weekday.format(new Date(`${date}T00:00:00Z`));
  const offClass = (working: boolean | undefined) => (working === false ? "is-sunday" : undefined);

  return (
    <>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[13.5px]" data-testid="staff-month-summary">
        <span>
          {t("staffAttendance.workingDays")}: <b className="num">{report.workingDays}</b>
        </span>
        <StaffCountsBar counts={report.totals} />
      </div>
      {report.staff.length === 0 ? (
        <p className="empty">{t("staffAttendance.noStaff")}</p>
      ) : (
        <div className="table-wrap">
          <table className="month-table" data-testid="staff-month-grid">
            <caption className="sr-only">{t("staffAttendance.monthCaption", { month: report.month })}</caption>
            <thead>
              <tr>
                <th scope="col" className="sticky-col">
                  {t("staffAttendance.col.staff")}
                </th>
                {report.days.map((day) => (
                  <th key={day.date} scope="col" className={offClass(day.workingDay)} title={formatPlainDate(day.date, locale)}>
                    <span className="block num">{Number(day.date.slice(8))}</span>
                    <span className="block text-[10.5px] font-medium">{dayLabel(day.date)}</span>
                  </th>
                ))}
                <th scope="col" className="r" title={t("staff.day.PRESENT")}>
                  P
                </th>
                <th scope="col" className="r" title={t("staff.day.HALF_DAY")}>
                  H
                </th>
                <th scope="col" className="r" title={t("staff.day.ABSENT")}>
                  A
                </th>
                <th scope="col" className="r" title={t("staff.day.ON_LEAVE")}>
                  L
                </th>
                <th scope="col" className="r">
                  {t("staffAttendance.col.worked")}
                </th>
              </tr>
            </thead>
            <tbody>
              {report.staff.map((line) => (
                <tr key={line.userId}>
                  <th scope="row" className="sticky-col">
                    <span className="block truncate font-semibold">{line.name}</span>
                    <span className="block text-[12px] font-normal text-ink-3">
                      {[line.employeeCode, line.departmentName, line.active ? null : t("staff.status.LEFT")]
                        .filter(Boolean)
                        .join(" · ") || "—"}
                    </span>
                  </th>
                  {line.marks.map((mark, i) => (
                    <td key={report.days[i]?.date ?? i} className={offClass(report.days[i]?.workingDay)}>
                      {mark ? <StaffMarkBadge mark={mark} /> : <span className="text-ink-3" aria-hidden="true">·</span>}
                    </td>
                  ))}
                  <td className="r num">{line.counts.present}</td>
                  <td className="r num">{line.counts.halfDay}</td>
                  <td className="r num">{line.counts.absent}</td>
                  <td className="r num">{line.counts.onLeave}</td>
                  <td className="r num font-semibold">{formatNumber(line.daysWorked)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" className="sticky-col">
                  {t("staffAttendance.row.present")}
                </th>
                {report.days.map((day) => (
                  <td key={day.date} className="num">
                    {day.counts.present + day.counts.halfDay > 0 ? day.counts.present + day.counts.halfDay : ""}
                  </td>
                ))}
                <td className="r num">{report.totals.present}</td>
                <td className="r num">{report.totals.halfDay}</td>
                <td className="r num">{report.totals.absent}</td>
                <td className="r num">{report.totals.onLeave}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
      )}
      <StaffMarkLegend />
      <p className="text-[12.5px] text-ink-3">{t("staffAttendance.workedRule")}</p>
    </>
  );
}
