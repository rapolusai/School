"use client";

import Link from "next/link";
import { useState } from "react";
import { SelectField } from "@/components/ui/field";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { CertificateStatusPill, subjectName, subjectSub, typeLabel, useSectionOptions } from "@/components/views/certificates/certificates-shared";
import { certificateReportPaths, certificatesApi, type IssuedParams } from "@/lib/certificates-api";
import { formatDateTime, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { CERTIFICATE_TYPES, type CertificateRow, type CertificateType } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { addDays, DateFilter, Figure, rangeProblem, ReportFrame } from "./report-frame";

/* The certificate reports in the reports hub (docs/api/phase-2-certificates.md, "Reports"). Downloads are CSV. */

function PersonCell({ row }: { row: CertificateRow }) {
  const { t } = useI18n();
  return (
    <>
      <span className="block font-semibold">{subjectName(t, row.subject)}</span>
      <span className="block text-[12.5px] font-normal text-ink-3">{subjectSub(row.subject)}</span>
    </>
  );
}

/** Certificates issued in a period, by type. */
export function CertificatesIssuedReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => addDays(today, -30));
  const [to, setTo] = useState(today);
  const [type, setType] = useState<"" | CertificateType>("");
  const problemKey = rangeProblem(from, to);
  const params: IssuedParams = { from, to, type: type || undefined };
  const report = useApiData(problemKey ? null : `certificates:report:issued:${JSON.stringify(params)}`, () => certificatesApi.issuedReport(params));
  const data = report.loading ? undefined : report.data;
  const summary = [
    t("reports.filter.rangeIs", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) }),
    type ? typeLabel(t, type) : t("certificates.filter.allTypes"),
  ];
  return (
    <ReportFrame
      title={t("certificates.report.issued.title")}
      testId="report-certificates-issued"
      filterSummary={summary}
      problem={problemKey ? t(problemKey) : null}
      excel={{ path: certificateReportPaths.issuedCsv(params), fileName: "certificates-issued.csv" }}
      downloadLabel={t("certificates.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <DateFilter name="from" label={t("reports.filter.from")} value={from} onChange={setFrom} max={today} invalid={problemKey !== null} />
          <DateFilter name="to" label={t("reports.filter.to")} value={to} onChange={setTo} max={today} invalid={problemKey !== null} />
          <SelectField
            label={t("certificates.col.type")}
            name="type"
            value={type}
            onChange={(e) => setType(e.target.value as "" | CertificateType)}
            options={[{ value: "", label: t("certificates.filter.allTypes") }, ...CERTIFICATE_TYPES.map((x) => ({ value: x, label: typeLabel(t, x) }))]}
            className="w-full sm:w-auto sm:min-w-44"
          />
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("certificates.report.issued.total")} value={data.total} testId="certificates-issued-total" />
            {data.byType.map((x) => (
              <Figure key={x.type} label={typeLabel(t, x.type)} value={x.count} />
            ))}
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("certificates.report.issued.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="certificates-issued-table">
                <caption className="sr-only">{t("certificates.report.issued.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("certificates.col.serial")}</th>
                    <th scope="col">{t("certificates.col.type")}</th>
                    <th scope="col">{t("certificates.col.person")}</th>
                    <th scope="col">{t("certificates.col.issuedOn")}</th>
                    <th scope="col">{t("certificates.col.signedBy")}</th>
                    <th scope="col">{t("certificates.col.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.id}>
                      <th scope="row" className="mono">
                        <Link href={`/app/certificates/${encodeURIComponent(row.id)}`} className="link">
                          {row.serialNo}
                        </Link>
                      </th>
                      <td>{typeLabel(t, row.type)}</td>
                      <td>
                        <PersonCell row={row} />
                      </td>
                      <td className="whitespace-nowrap">{row.issuedOn ? formatPlainDate(row.issuedOn, locale) : "—"}</td>
                      <td>{row.signedByName ?? "—"}</td>
                      <td>
                        <CertificateStatusPill status={row.status} />
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

/** Requests waiting for the office and prepared certificates waiting for a signature. */
export function CertificatesPendingReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData("certificates:report:pending", certificatesApi.pendingReport);
  const data = report.data;
  return (
    <ReportFrame
      title={t("certificates.report.pending.title")}
      testId="report-certificates-pending"
      filterSummary={[]}
      problem={null}
      excel={{ path: certificateReportPaths.pendingCsv(), fileName: "certificates-pending.csv" }}
      downloadLabel={t("certificates.csv")}
      ready={Boolean(data)}
      filters={<p className="text-[13px] text-ink-3">{t("certificates.report.pending.intro")}</p>}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("certificates.status.REQUESTED")} value={data.requested} testId="certificates-pending-requested" />
            <Figure label={t("certificates.status.PREPARED")} value={data.prepared} testId="certificates-pending-prepared" />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("certificates.report.pending.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="certificates-pending-table">
                <caption className="sr-only">{t("certificates.report.pending.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("certificates.col.person")}</th>
                    <th scope="col">{t("certificates.col.type")}</th>
                    <th scope="col">{t("certificates.col.purpose")}</th>
                    <th scope="col">{t("certificates.col.requested")}</th>
                    <th scope="col">{t("certificates.col.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.id}>
                      <th scope="row">
                        <Link href={`/app/certificates/${encodeURIComponent(row.id)}`} className="link">
                          <PersonCell row={row} />
                        </Link>
                      </th>
                      <td>{typeLabel(t, row.type)}</td>
                      <td>{row.purpose ?? "—"}</td>
                      <td className="whitespace-nowrap">
                        {formatDateTime(row.requestedAt, locale)}
                        <span className="block text-[12.5px] text-ink-3">{t(row.requestedVia === "FAMILY" ? "certificates.viaFamily" : "certificates.viaOffice")}</span>
                      </td>
                      <td>
                        <CertificateStatusPill status={row.status} />
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

/** Students of a section, or the staff, with no ID card in use. */
export function IdCardsPendingReportView() {
  const { t } = useI18n();
  const sections = useSectionOptions();
  const [who, setWho] = useState("");
  const staff = who === "staff";
  const sectionId = staff ? undefined : who || undefined;
  const params = { sectionId, staff: staff || undefined };
  const report = useApiData(who ? `certificates:report:cards:${who}` : null, () => certificatesApi.cardsPendingReport(params));
  const data = report.loading ? undefined : report.data;
  const label = staff ? t("certificates.subjectType.STAFF") : (sections.find((s) => s.value === who)?.label ?? "");
  return (
    <ReportFrame
      title={t("certificates.report.cards.title")}
      testId="report-id-cards-pending"
      filterSummary={label ? [label] : []}
      problem={who ? null : t("certificates.report.cards.choose")}
      excel={who ? { path: certificateReportPaths.cardsPendingCsv(params), fileName: "id-cards-pending.csv" } : null}
      downloadLabel={t("certificates.csv")}
      ready={Boolean(data)}
      filters={
        <SelectField
          label={t("certificates.card.for")}
          name="who"
          value={who}
          onChange={(e) => setWho(e.target.value)}
          options={[{ value: "", label: t("certificates.report.cards.pick") }, { value: "staff", label: t("certificates.subjectType.STAFF") }, ...sections]}
          className="w-full sm:w-auto sm:min-w-52"
        />
      }
    >
      {!who ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("certificates.report.cards.total")} value={data.total} testId="id-cards-pending-total" />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("certificates.report.cards.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="id-cards-pending-table">
                <caption className="sr-only">{t("certificates.report.cards.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("certificates.col.person")}</th>
                    <th scope="col">{t("certificates.card.admissionNo")}</th>
                    <th scope="col">{t("certificates.col.detail")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.subject.id}>
                      <th scope="row">{subjectName(t, row.subject)}</th>
                      <td className="mono">{row.subject.code ?? "—"}</td>
                      <td>{row.subject.detail ?? row.sectionLabel ?? "—"}</td>
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
