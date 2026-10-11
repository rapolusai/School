"use client";

import { Fragment, useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { Figure, nameOf, ReportFrame } from "@/components/views/reports/report-frame";
import { formatPaise, todayInIndia } from "@/lib/format";
import { plural, useI18n } from "@/lib/i18n";
import { financialYearsUpTo, formatPayDays, MONTH_PATTERN } from "@/lib/payroll";
import { payrollApi, payrollPaths, type RegisterParams, type YearParams } from "@/lib/payroll-api";
import type { PayrollRegister, PayrollStatutorySummary, PayrollYearToDate } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { RunStatusPill, useMonthLabel } from "./payroll-ui";

function useDepartments() {
  return useApiData("payroll:report-departments", payrollApi.departments).data ?? [];
}

function YearFilter({ value, onChange }: { value: string; onChange: (year: string) => void }) {
  const { t } = useI18n();
  const years = financialYearsUpTo(todayInIndia().slice(0, 7));
  return (
    <label className="field w-full sm:w-auto sm:min-w-40">
      <span className="field-label">{t("payroll.report.financialYear")}</span>
      <select className="input" name="financialYear" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{t("payroll.report.thisYear")}</option>
        {years.map((y) => (
          <option key={y} value={y}>
            {y}
          </option>
        ))}
      </select>
    </label>
  );
}

/* ------------------------------------------------------------------ register */

/** The payroll register of a month by department (the latest month with a run unless chosen). */
export function PayrollRegisterReport() {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const [month, setMonth] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const departments = useDepartments();
  const problem = month && !MONTH_PATTERN.test(month) ? t("payroll.v.month") : null;
  const params: RegisterParams = { month: month || undefined, departmentId: departmentId || undefined };
  const report = useApiData(problem ? null : `payroll:register:${month}:${departmentId}`, () =>
    payrollApi.register(params),
  );
  const data =
    report.data && (!month || report.data.month === month) && (report.data.departmentId ?? "") === departmentId
      ? report.data
      : undefined;
  const shownMonth = data?.month ?? month;
  const departmentName = nameOf(departments, departmentId);
  const summary = [
    shownMonth ? t("payroll.report.monthIs", { month: monthLabel(shownMonth) }) : null,
    departmentName ? t("reports.filter.departmentIs", { name: departmentName }) : t("reports.filter.allDepartments"),
  ].filter((s): s is string => Boolean(s));

  return (
    <ReportFrame
      title={t("payroll.report.register.title")}
      testId="report-payroll-register"
      filterSummary={summary}
      problem={problem}
      excel={
        data
          ? {
              path: payrollPaths.registerCsv({ month: data.month, departmentId: departmentId || undefined }),
              fileName: `payroll-register-${data.month}.csv`,
            }
          : null
      }
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("payroll.col.month")}</span>
            <input
              type="month"
              className="input"
              name="month"
              value={month}
              max={todayInIndia().slice(0, 7)}
              onChange={(e) => setMonth(e.target.value)}
            />
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
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <RegisterBody report={data} />
      )}
    </ReportFrame>
  );
}

export function RegisterBody({ report }: { report: PayrollRegister }) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  if (!report.runId) return <p className="empty">{t("payroll.report.noRun", { month: monthLabel(report.month) })}</p>;
  const totals = report.totals;
  return (
    <>
      <div className="report-figures">
        <Figure label={t("payroll.col.staff")} value={totals.count} />
        <Figure label={t("payroll.col.gross")} value={formatPaise(totals.grossPaise)} />
        <Figure label={t("payroll.col.deductions")} value={formatPaise(totals.deductionsPaise)} />
        <Figure label={t("payroll.col.net")} value={formatPaise(totals.netPaise)} testId="register-net" />
        <Figure label={t("payroll.col.employer")} value={formatPaise(totals.employerPaise)} />
      </div>
      <p className="flex flex-wrap items-center gap-2 text-[13px] text-ink-2">
        {report.status ? <RunStatusPill status={report.status} /> : null}
        {report.status === "DRAFT" ? t("payroll.report.draft") : null}
      </p>
      {report.departments.length === 0 ? (
        <p className="empty">{t("payroll.report.empty")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="register-table">
              <caption className="sr-only">{t("payroll.report.register.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("payroll.col.name")}</th>
                  <th scope="col" className="r">
                    {t("payroll.col.days")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.gross")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.pf")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.esi")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.pt")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.tds")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.other")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.net")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {report.departments.map((d) => (
                  <Fragment key={d.departmentId ?? "none"}>
                    <tr className="report-group-row">
                      <th scope="rowgroup" colSpan={9}>
                        {d.departmentName ?? t("payroll.report.noDepartment")}
                      </th>
                    </tr>
                    {d.rows.map((row) => (
                      <tr key={row.userId}>
                        <th scope="row" className="report-indent">
                          <span className="block font-semibold">{row.name}</span>
                          <span className="block text-[12.5px] font-normal text-ink-3">
                            {[row.employeeCode, row.designation].filter(Boolean).join(" · ")}
                          </span>
                        </th>
                        <td className="r num">
                          {formatPayDays(row.payableDays)}
                          {row.lopDays > 0 ? (
                            <span className="block text-[12px] text-bad">
                              {t("payroll.run.lop", { days: formatPayDays(row.lopDays) })}
                            </span>
                          ) : null}
                        </td>
                        <td className="r num">{formatPaise(row.grossPaise)}</td>
                        <td className="r num">{formatPaise(row.pfEmployeePaise)}</td>
                        <td className="r num">{formatPaise(row.esiEmployeePaise)}</td>
                        <td className="r num">{formatPaise(row.ptPaise)}</td>
                        <td className="r num">{formatPaise(row.tdsPaise)}</td>
                        <td className="r num">{formatPaise(row.otherDeductionsPaise)}</td>
                        <td className="r num font-semibold">{formatPaise(row.netPaise)}</td>
                      </tr>
                    ))}
                    <tr>
                      <th scope="row" className="report-indent font-semibold">
                        {t("payroll.report.departmentTotal", { count: d.totals.count })}
                      </th>
                      <td />
                      <td className="r num font-semibold">{formatPaise(d.totals.grossPaise)}</td>
                      <td className="r num">{formatPaise(d.totals.pfEmployeePaise)}</td>
                      <td className="r num">{formatPaise(d.totals.esiEmployeePaise)}</td>
                      <td className="r num">{formatPaise(d.totals.ptPaise)}</td>
                      <td className="r num">{formatPaise(d.totals.tdsPaise)}</td>
                      <td className="r num">{formatPaise(d.totals.otherDeductionsPaise)}</td>
                      <td className="r num font-semibold">{formatPaise(d.totals.netPaise)}</td>
                    </tr>
                  </Fragment>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("payroll.report.total", { count: totals.count })}</th>
                  <td />
                  <td className="r num">{formatPaise(totals.grossPaise)}</td>
                  <td className="r num">{formatPaise(totals.pfEmployeePaise)}</td>
                  <td className="r num">{formatPaise(totals.esiEmployeePaise)}</td>
                  <td className="r num">{formatPaise(totals.ptPaise)}</td>
                  <td className="r num">{formatPaise(totals.tdsPaise)}</td>
                  <td className="r num">{formatPaise(totals.otherDeductionsPaise)}</td>
                  <td className="r num">{formatPaise(totals.netPaise)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <div className="md:hidden" data-testid="register-cards">
            {report.departments.map((d) => (
              <div key={d.departmentId ?? "none"} className="report-card-group">
                <b className="font-semibold">{d.departmentName ?? t("payroll.report.noDepartment")}</b>
                <ul className="rowcards">
                  {d.rows.map((row) => (
                    <li key={row.userId} className="rowcard items-start">
                      <span className="min-w-0 flex-1">
                        <b className="block truncate font-semibold">{row.name}</b>
                        <span className="block text-[12.5px] text-ink-3">
                          {t("payroll.run.cardLine", {
                            days: formatPayDays(row.payableDays),
                            gross: formatPaise(row.grossPaise),
                            deductions: formatPaise(row.deductionsPaise),
                          })}
                        </span>
                      </span>
                      <b className="num">{formatPaise(row.netPaise)}</b>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </>
      )}
      <p className="text-[12.5px] text-ink-3">{t("payroll.report.register.note")}</p>
    </>
  );
}

/* ------------------------------------------------------------------ year to date */

/** Year to date per member of staff: approved and paid months of a financial year. */
export function PayrollYearToDateReport() {
  const { t } = useI18n();
  const [year, setYear] = useState("");
  const params: YearParams = { financialYear: year || undefined };
  const report = useApiData(`payroll:ytd:${year}`, () => payrollApi.yearToDate(params));
  const data = report.data && (!year || report.data.financialYear === year) ? report.data : undefined;
  const shownYear = data?.financialYear ?? year;
  return (
    <ReportFrame
      title={t("payroll.report.ytd.title")}
      testId="report-payroll-ytd"
      filterSummary={shownYear ? [t("payroll.report.yearIs", { year: shownYear })] : []}
      problem={null}
      excel={
        data
          ? {
              path: payrollPaths.yearToDateCsv({ financialYear: data.financialYear }),
              fileName: `payroll-year-to-date-${data.financialYear}.csv`,
            }
          : null
      }
      ready={Boolean(data)}
      filters={<YearFilter value={year} onChange={setYear} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <YtdBody report={data} />
      )}
    </ReportFrame>
  );
}

function YtdBody({ report }: { report: PayrollYearToDate }) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const totals = report.totals;
  return (
    <>
      <div className="report-figures">
        <Figure label={t("payroll.report.monthsPaid")} value={report.months.length} />
        <Figure label={t("payroll.col.gross")} value={formatPaise(totals.grossPaise)} />
        <Figure label={t("payroll.col.tds")} value={formatPaise(totals.tdsPaise)} />
        <Figure label={t("payroll.col.net")} value={formatPaise(totals.netPaise)} testId="ytd-net" />
      </div>
      {report.months.length > 0 ? (
        <p className="text-[13px] text-ink-2">
          {t("payroll.report.monthsIncluded", { months: report.months.map((m) => monthLabel(m)).join(", ") })}
        </p>
      ) : null}
      {report.rows.length === 0 ? (
        <p className="empty">{t("payroll.report.ytd.empty")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="ytd-table">
              <caption className="sr-only">{t("payroll.report.ytd.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("payroll.col.name")}</th>
                  <th scope="col" className="r">
                    {t("payroll.report.months")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.gross")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.pf")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.esi")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.pt")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.tds")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.other")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.net")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {report.rows.map((row) => (
                  <tr key={row.userId ?? row.name}>
                    <th scope="row">
                      <span className="block font-semibold">{row.name}</span>
                      <span className="block text-[12.5px] font-normal text-ink-3">
                        {[row.employeeCode, row.departmentName].filter(Boolean).join(" · ")}
                      </span>
                    </th>
                    <td className="r num">{row.months}</td>
                    <td className="r num">{formatPaise(row.grossPaise)}</td>
                    <td className="r num">{formatPaise(row.pfEmployeePaise)}</td>
                    <td className="r num">{formatPaise(row.esiEmployeePaise)}</td>
                    <td className="r num">{formatPaise(row.ptPaise)}</td>
                    <td className="r num">{formatPaise(row.tdsPaise)}</td>
                    <td className="r num">{formatPaise(row.otherDeductionsPaise)}</td>
                    <td className="r num font-semibold">{formatPaise(row.netPaise)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("payroll.report.total", { count: report.rows.length })}</th>
                  <td />
                  <td className="r num">{formatPaise(totals.grossPaise)}</td>
                  <td className="r num">{formatPaise(totals.pfEmployeePaise)}</td>
                  <td className="r num">{formatPaise(totals.esiEmployeePaise)}</td>
                  <td className="r num">{formatPaise(totals.ptPaise)}</td>
                  <td className="r num">{formatPaise(totals.tdsPaise)}</td>
                  <td className="r num">{formatPaise(totals.otherDeductionsPaise)}</td>
                  <td className="r num">{formatPaise(totals.netPaise)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="ytd-cards">
            {report.rows.map((row) => (
              <li key={row.userId ?? row.name} className="rowcard items-start">
                <span className="min-w-0 flex-1">
                  <b className="block truncate font-semibold">{row.name}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {plural(t, "payroll.report.monthCount", row.months)} ·{" "}
                    {t("payslip.mine.gross", { amount: formatPaise(row.grossPaise) })} ·{" "}
                    {t("payroll.report.tdsAmount", { amount: formatPaise(row.tdsPaise) })}
                  </span>
                </span>
                <b className="num">{formatPaise(row.netPaise)}</b>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ statutory summary */

/** PF, ESI, professional tax and TDS by month for a financial year. */
export function PayrollStatutoryReport() {
  const { t } = useI18n();
  const [year, setYear] = useState("");
  const params: YearParams = { financialYear: year || undefined };
  const report = useApiData(`payroll:statutory:${year}`, () => payrollApi.statutory(params));
  const data = report.data && (!year || report.data.financialYear === year) ? report.data : undefined;
  const shownYear = data?.financialYear ?? year;
  return (
    <ReportFrame
      title={t("payroll.report.statutory.title")}
      testId="report-payroll-statutory"
      filterSummary={shownYear ? [t("payroll.report.yearIs", { year: shownYear })] : []}
      problem={null}
      excel={
        data
          ? {
              path: payrollPaths.statutoryCsv({ financialYear: data.financialYear }),
              fileName: `payroll-statutory-${data.financialYear}.csv`,
            }
          : null
      }
      ready={Boolean(data)}
      filters={<YearFilter value={year} onChange={setYear} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <StatutoryBody report={data} />
      )}
    </ReportFrame>
  );
}

function StatutoryBody({ report }: { report: PayrollStatutorySummary }) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const totals = report.totals;
  const pf = (m: PayrollStatutorySummary["totals"]) =>
    m.pfEmployeePaise + m.epfEmployerPaise + m.epsPaise + m.edliPaise + m.pfAdminPaise;
  return (
    <>
      <div className="report-figures">
        <Figure label={t("payroll.report.pfTotal")} value={formatPaise(pf(totals))} />
        <Figure label={t("payroll.run.esi")} value={formatPaise(totals.esiEmployeePaise + totals.esiEmployerPaise)} />
        <Figure label={t("payroll.run.pt")} value={formatPaise(totals.ptPaise)} />
        <Figure label={t("payroll.run.tds")} value={formatPaise(totals.tdsPaise)} testId="statutory-tds" />
      </div>
      {report.months.length === 0 ? (
        <p className="empty">{t("payroll.report.statutory.empty")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="statutory-table">
              <caption className="sr-only">{t("payroll.report.statutory.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("payroll.col.month")}</th>
                  <th scope="col" className="r">
                    {t("payroll.col.staff")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.report.pfEmployee")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.report.epf")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.report.eps")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.report.edliAdmin")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.report.esiSplit")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.pt")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.tds")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.report.totalCol")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {report.months.map((m) => (
                  <tr key={m.month ?? "total"}>
                    <th scope="row">
                      <span className="block font-semibold">{monthLabel(m.month)}</span>
                      {m.status ? (
                        <span className="block">
                          <RunStatusPill status={m.status} />
                        </span>
                      ) : null}
                    </th>
                    <td className="r num">{m.staffCount}</td>
                    <td className="r num">{formatPaise(m.pfEmployeePaise)}</td>
                    <td className="r num">{formatPaise(m.epfEmployerPaise)}</td>
                    <td className="r num">{formatPaise(m.epsPaise)}</td>
                    <td className="r num">
                      {formatPaise(m.edliPaise)} / {formatPaise(m.pfAdminPaise)}
                    </td>
                    <td className="r num">
                      {formatPaise(m.esiEmployeePaise)} / {formatPaise(m.esiEmployerPaise)}
                    </td>
                    <td className="r num">{formatPaise(m.ptPaise)}</td>
                    <td className="r num">{formatPaise(m.tdsPaise)}</td>
                    <td className="r num font-semibold">{formatPaise(m.totalPaise)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("payroll.total")}</th>
                  <td />
                  <td className="r num">{formatPaise(totals.pfEmployeePaise)}</td>
                  <td className="r num">{formatPaise(totals.epfEmployerPaise)}</td>
                  <td className="r num">{formatPaise(totals.epsPaise)}</td>
                  <td className="r num">
                    {formatPaise(totals.edliPaise)} / {formatPaise(totals.pfAdminPaise)}
                  </td>
                  <td className="r num">
                    {formatPaise(totals.esiEmployeePaise)} / {formatPaise(totals.esiEmployerPaise)}
                  </td>
                  <td className="r num">{formatPaise(totals.ptPaise)}</td>
                  <td className="r num">{formatPaise(totals.tdsPaise)}</td>
                  <td className="r num">{formatPaise(totals.totalPaise)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="statutory-cards">
            {report.months.map((m) => (
              <li key={m.month ?? "total"} className="rowcard items-start">
                <span className="min-w-0 flex-1">
                  <b className="block truncate font-semibold">{monthLabel(m.month)}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {t("payroll.report.statutoryLine", {
                      pf: formatPaise(pf(m)),
                      esi: formatPaise(m.esiEmployeePaise + m.esiEmployerPaise),
                      pt: formatPaise(m.ptPaise),
                      tds: formatPaise(m.tdsPaise),
                    })}
                  </span>
                </span>
                <b className="num">{formatPaise(m.totalPaise)}</b>
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="text-[12.5px] text-ink-3">{t("payroll.report.statutory.note")}</p>
    </>
  );
}
