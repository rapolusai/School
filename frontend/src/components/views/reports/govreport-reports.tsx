"use client";

import { useState } from "react";
import {
  useYears,
  YearSelect,
} from "@/components/views/govreport/govreport-shared";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { govPaths, govreportApi } from "@/lib/govreport-api";
import { localeFor, translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";
import { DateFilter, Figure, ReportFrame } from "./report-frame";

/* The Government group of the reports hub (docs/api/phase-3-govreport.md, "Reports"). Downloads are CSV. */

function useYearFilter() {
  const { current, years } = useYears();
  const [chosen, setChosen] = useState("");
  const yearId = chosen || current;
  const yearName = years.find((y) => y.id === yearId)?.name ?? "";
  return { yearId, yearName, setChosen };
}

/** Students' government fields: what is complete, by section. */
export function GovCompletenessReportView() {
  const { t } = useI18n();
  const { yearId, yearName, setChosen } = useYearFilter();
  const report = useApiData(
    yearId ? `govreport:report:completeness:${yearId}` : null,
    () => govreportApi.completeness(yearId),
  );
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("govreport.report.completeness.title")}
      testId="report-govreport-completeness"
      filterSummary={[yearName]}
      problem={null}
      excel={
        yearId
          ? {
              path: govPaths.completenessCsv({ yearId }),
              fileName: "government-fields-completeness.csv",
            }
          : null
      }
      downloadLabel={t("govreport.exports.csv")}
      ready={Boolean(data)}
      filters={<YearSelect value={yearId} onChange={setChosen} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure
              label={t("govreport.report.students")}
              value={data.students}
            />
            <Figure label={t("govreport.complete")} value={data.complete} />
            <Figure
              label={t("govreport.report.percent")}
              value={`${data.percent}%`}
              testId="govreport-completeness-percent"
            />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("govreport.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table">
                <caption className="sr-only">
                  {t("govreport.report.completeness.title")}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{t("govreport.section")}</th>
                    <th scope="col">{t("govreport.report.students")}</th>
                    <th scope="col">{t("govreport.complete")}</th>
                    <th scope="col">{t("govreport.report.percent")}</th>
                    <th scope="col">{t("govreport.report.mostMissing")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => {
                    const top = Object.entries(r.missingByField)
                      .filter(([, n]) => n > 0)
                      .sort((a, b) => b[1] - a[1])
                      .slice(0, 3);
                    return (
                      <tr key={r.sectionId}>
                        <th scope="row">{r.label}</th>
                        <td className="num">{r.students}</td>
                        <td className="num">{r.complete}</td>
                        <td className="num">{r.percent}%</td>
                        <td>
                          {top
                            .map(
                              ([f, n]) =>
                                `${translateOr(t, `govreport.field.${f}`, f)} (${n})`,
                            )
                            .join(", ") || "—"}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/** Enrolment by class, gender and social category. */
export function GovEnrolmentReportView() {
  const { t, lang } = useI18n();
  const { yearId, yearName, setChosen } = useYearFilter();
  const [today] = useState(() => todayInIndia());
  const [asOf, setAsOf] = useState(today);
  const params = { yearId, asOf };
  const report = useApiData(
    yearId ? `govreport:report:enrolment:${yearId}:${asOf}` : null,
    () => govreportApi.enrolmentReport(params),
  );
  const data = report.loading ? undefined : report.data;
  const categories =
    useApiData("govreport:codes:SOCIAL_CATEGORY", () =>
      govreportApi.codes("SOCIAL_CATEGORY"),
    ).data ?? [];
  const catLabel = (code: string) =>
    code === "NOT_RECORDED"
      ? t("govreport.report.notRecorded")
      : (categories.find((c) => c.code === code)?.label ?? code);
  return (
    <ReportFrame
      title={t("govreport.report.enrolment.title")}
      testId="report-govreport-enrolment"
      filterSummary={[
        yearName,
        t("govreport.asOfDate", {
          date: formatPlainDate(asOf, localeFor(lang)),
        }),
      ]}
      problem={null}
      excel={
        yearId
          ? {
              path: govPaths.enrolmentCsv(params),
              fileName: "enrolment-by-category.csv",
            }
          : null
      }
      downloadLabel={t("govreport.exports.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <YearSelect value={yearId} onChange={setChosen} />
          <DateFilter
            name="asOf"
            label={t("govreport.asOf")}
            value={asOf}
            onChange={setAsOf}
            max={today}
            invalid={false}
          />
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : data.rows.length === 0 ? (
        <p className="empty">{t("govreport.report.empty")}</p>
      ) : (
        <div className="table-wrap">
          <table
            className="table report-table"
            data-testid="govreport-enrolment-table"
          >
            <caption className="sr-only">
              {t("govreport.report.enrolment.title")}
            </caption>
            <thead>
              <tr>
                <th scope="col">{t("govreport.report.class")}</th>
                <th scope="col">{t("govreport.report.boys")}</th>
                <th scope="col">{t("govreport.report.girls")}</th>
                <th scope="col">{t("govreport.report.other")}</th>
                <th scope="col">{t("govreport.report.total")}</th>
                {data.categories.map((c) => (
                  <th key={c} scope="col">
                    {catLabel(c)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {[...data.rows, data.totals].map((r) => (
                <tr key={r.classId ?? "total"}>
                  <th scope="row">
                    {r.classId ? r.className : t("govreport.report.total")}
                  </th>
                  <td className="num">{r.boys}</td>
                  <td className="num">{r.girls}</td>
                  <td className="num">{r.other}</td>
                  <td className="num">{r.total}</td>
                  {data.categories.map((c) => (
                    <td key={c} className="num">
                      {r.byCategory[c] ?? 0}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </ReportFrame>
  );
}

/** RTE seats and allotments by intake. */
export function GovRteReportView() {
  const { t } = useI18n();
  const { yearId, yearName, setChosen } = useYearFilter();
  const report = useApiData(
    yearId ? `govreport:report:rte:${yearId}` : null,
    () => govreportApi.rteReport({ yearId }),
  );
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("govreport.report.rte.title")}
      testId="report-govreport-rte"
      filterSummary={[yearName]}
      problem={null}
      excel={
        yearId
          ? { path: govPaths.rteCsv({ yearId }), fileName: "rte-seats.csv" }
          : null
      }
      downloadLabel={t("govreport.exports.csv")}
      ready={Boolean(data)}
      filters={<YearSelect value={yearId} onChange={setChosen} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("govreport.rte.seats")} value={data.seats} />
            <Figure
              label={t("govreport.rte.allotted")}
              value={data.allotted}
              testId="govreport-rte-allotted"
            />
            <Figure
              label={t("govreport.report.waitlisted")}
              value={data.waitlisted}
            />
          </div>
          {data.intakes.length === 0 ? (
            <p className="empty">{t("govreport.rte.none")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table">
                <caption className="sr-only">
                  {t("govreport.report.rte.title")}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{t("govreport.rte.entryClass")}</th>
                    <th scope="col">{t("govreport.rte.totalSeats")}</th>
                    <th scope="col">{t("govreport.rte.rteSeats")}</th>
                    <th scope="col">{t("govreport.report.applicants")}</th>
                    <th scope="col">{t("govreport.report.eligible")}</th>
                    <th scope="col">{t("govreport.report.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.intakes.map((i) => (
                    <tr key={i.id}>
                      <th scope="row">{i.className ?? "—"}</th>
                      <td className="num">{i.totalSeats}</td>
                      <td className="num">{i.rteSeats}</td>
                      <td className="num">{i.applicants}</td>
                      <td className="num">{i.eligible}</td>
                      <td>
                        {t(`govreport.rte.status.${i.status}` as MessageKey)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/** Scheme enrolments and amounts by scheme. */
export function GovSchemesReportView() {
  const { t } = useI18n();
  const { yearId, yearName, setChosen } = useYearFilter();
  const report = useApiData(
    yearId ? `govreport:report:schemes:${yearId}` : null,
    () => govreportApi.schemeReport({ yearId }),
  );
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("govreport.report.schemes.title")}
      testId="report-govreport-schemes"
      filterSummary={[yearName]}
      problem={null}
      excel={
        yearId
          ? {
              path: govPaths.schemesReportCsv({ yearId }),
              fileName: "scheme-enrolments.csv",
            }
          : null
      }
      downloadLabel={t("govreport.exports.csv")}
      ready={Boolean(data)}
      filters={<YearSelect value={yearId} onChange={setChosen} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure
              label={t("govreport.report.students")}
              value={data.students}
            />
            <Figure
              label={t("govreport.schemes.amount")}
              value={formatPaise(data.amountPaise)}
            />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("govreport.schemes.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table">
                <caption className="sr-only">
                  {t("govreport.report.schemes.title")}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{t("govreport.schemes.scheme")}</th>
                    <th scope="col">{t("govreport.report.students")}</th>
                    <th scope="col">
                      {t("govreport.schemes.status.APPROVED")}
                    </th>
                    <th scope="col">
                      {t("govreport.schemes.status.RECEIVED")}
                    </th>
                    <th scope="col">{t("govreport.schemes.amount")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => (
                    <tr key={r.schemeCode}>
                      <th scope="row">{r.schemeLabel ?? r.schemeCode}</th>
                      <td className="num">{r.students}</td>
                      <td className="num">{r.byStatus.APPROVED ?? 0}</td>
                      <td className="num">{r.byStatus.RECEIVED ?? 0}</td>
                      <td className="num">{formatPaise(r.amountPaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}
