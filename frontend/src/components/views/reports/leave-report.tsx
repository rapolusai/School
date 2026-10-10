"use client";

import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { reportPaths, reportsApi, type LeaveParams } from "@/lib/reports-api";
import { leaveApi, staffApi } from "@/lib/staff-api";
import type { LeaveTakenReport } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { Figure, nameOf, ReportFrame } from "./report-frame";

/** 1.5 → "1.5", 2 → "2": leave days can be halves. */
export function formatDays(days: number): string {
  return Number.isInteger(days) ? String(days) : days.toFixed(1);
}

/** Approved leave by staff member and leave type for an academic year (the current one unless chosen). */
export function LeaveReportView() {
  const { t } = useI18n();
  const [yearId, setYearId] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const [leaveTypeId, setLeaveTypeId] = useState("");
  const years = useApiData("reports:years", api.listYears).data ?? [];
  const departments = useApiData("reports:departments", staffApi.departments).data ?? [];
  const types = useApiData("reports:leave-types", () => leaveApi.types(true)).data ?? [];

  const params: LeaveParams = {
    yearId: yearId || undefined,
    departmentId: departmentId || undefined,
    leaveTypeId: leaveTypeId || undefined,
  };
  const report = useApiData(`reports:leave:${yearId}:${departmentId}:${leaveTypeId}`, () => reportsApi.leave(params));
  const data =
    report.data &&
    (!yearId || report.data.academicYearId === yearId) &&
    (report.data.departmentId ?? "") === departmentId &&
    (report.data.leaveTypeId ?? "") === leaveTypeId
      ? report.data
      : undefined;

  const yearName = data?.academicYearName ?? nameOf(years, yearId);
  const departmentName = nameOf(departments, departmentId);
  const typeName = nameOf(types, leaveTypeId);
  const summary = [
    yearName ? t("reports.filter.yearIs", { name: yearName }) : null,
    departmentName ? t("reports.filter.departmentIs", { name: departmentName }) : t("reports.filter.allDepartments"),
    typeName ? t("reports.filter.leaveTypeIs", { name: typeName }) : t("reports.filter.allLeaveTypes"),
  ].filter((s): s is string => Boolean(s));
  const slug = (yearName ?? "year").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || "year";

  return (
    <ReportFrame
      title={t("reports.leave.title")}
      testId="report-leave"
      filterSummary={summary}
      problem={null}
      excel={{ path: reportPaths.leaveXlsx(params), fileName: `leave-taken-${slug}.xlsx` }}
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("reports.filter.year")}</span>
            <select className="input" name="yearId" value={yearId} onChange={(e) => setYearId(e.target.value)}>
              <option value="">{t("reports.filter.currentYear")}</option>
              {years.map((y) => (
                <option key={y.id} value={y.id}>
                  {y.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("reports.filter.department")}</span>
            <select
              className="input"
              name="departmentId"
              value={departmentId}
              onChange={(e) => setDepartmentId(e.target.value)}
            >
              <option value="">{t("reports.filter.allDepartments")}</option>
              {departments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("reports.filter.leaveType")}</span>
            <select
              className="input"
              name="leaveTypeId"
              value={leaveTypeId}
              onChange={(e) => setLeaveTypeId(e.target.value)}
            >
              <option value="">{t("reports.filter.allLeaveTypes")}</option>
              {types.map((type) => (
                <option key={type.id} value={type.id}>
                  {type.name}
                </option>
              ))}
            </select>
          </label>
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <LeaveBody report={data} />
      )}
    </ReportFrame>
  );
}

function LeaveBody({ report }: { report: LeaveTakenReport }) {
  const { t } = useI18n();
  if (!report.academicYearId) return <p className="empty">{t("reports.leave.noYear")}</p>;
  return (
    <>
      <div className="report-figures">
        <Figure label={t("reports.leave.total")} value={formatDays(report.total)} testId="leave-total" />
        <Figure label={t("reports.leave.lossOfPay")} value={formatDays(report.lossOfPay)} />
        <Figure label={t("reports.leave.pending")} value={formatDays(report.pending)} />
        <Figure label={t("reports.leave.staff")} value={report.staff.length} />
      </div>
      {report.staff.length === 0 ? (
        <p className="empty">{t("reports.leave.empty")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="leave-table">
              <caption className="sr-only">{t("reports.leave.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("reports.col.staff")}</th>
                  <th scope="col">{t("reports.col.department")}</th>
                  {report.types.map((type) => (
                    <th key={type.id} scope="col" className="r" title={type.name}>
                      {type.code}
                    </th>
                  ))}
                  <th scope="col" className="r">
                    {t("reports.col.total")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.lossOfPay")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.pending")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {report.staff.map((line) => (
                  <tr key={line.userId}>
                    <th scope="row">
                      <span className="block font-semibold">{line.name}</span>
                      <span className="block text-[12.5px] font-normal text-ink-3">
                        {[line.employeeCode, line.active ? null : t("reports.leave.inactive")].filter(Boolean).join(" · ")}
                      </span>
                    </th>
                    <td>{line.departmentName ?? "—"}</td>
                    {line.days.map((days, i) => (
                      <td key={report.types[i]?.id ?? i} className="r num">
                        {days ? formatDays(days) : "·"}
                      </td>
                    ))}
                    <td className="r num font-semibold">{formatDays(line.total)}</td>
                    <td className="r num">{formatDays(line.lossOfPay)}</td>
                    <td className="r num">{formatDays(line.pending)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("reports.total")}</th>
                  <td />
                  {report.typeTotals.map((days, i) => (
                    <td key={report.types[i]?.id ?? i} className="r num">
                      {formatDays(days)}
                    </td>
                  ))}
                  <td className="r num">{formatDays(report.total)}</td>
                  <td className="r num">{formatDays(report.lossOfPay)}</td>
                  <td className="r num">{formatDays(report.pending)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="leave-cards">
            {report.staff.map((line) => (
              <li key={line.userId} className="rowcard items-start">
                <div className="min-w-0 flex-1">
                  <b className="block truncate font-semibold">{line.name}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {[line.departmentName, line.employeeCode].filter(Boolean).join(" · ") || "—"}
                  </span>
                  <span className="mt-1 flex flex-wrap gap-1">
                    {line.days.map((days, i) =>
                      days ? (
                        <span key={report.types[i]?.id ?? i} className="chip">
                          {report.types[i]?.code} {formatDays(days)}
                        </span>
                      ) : null,
                    )}
                    {line.pending ? <Pill tone="warn">{t("reports.leave.pendingDays", { days: formatDays(line.pending) })}</Pill> : null}
                  </span>
                </div>
                <b className="num">{formatDays(line.total)}</b>
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="text-[12.5px] text-ink-3">{t("reports.leave.rule")}</p>
    </>
  );
}
