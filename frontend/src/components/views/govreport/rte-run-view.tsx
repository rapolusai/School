"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatDateTime, formatPlainDate } from "@/lib/format";
import { govreportApi } from "@/lib/govreport-api";
import { localeFor, useI18n } from "@/lib/i18n";
import type { LotteryRunDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { VerifyNote } from "./govreport-shared";

/* /app/govreport/rte/runs/[runId]: a lottery run's published list and the allotment letters, both printable. */

export function RteRunView({ runId }: { runId: string }) {
  const run = useApiData(`govreport:run:${runId}`, () =>
    govreportApi.run(runId),
  );
  if (run.error && !run.data)
    return <ErrorState error={run.error} onRetry={run.reload} />;
  if (!run.data) return <LoadingRows rows={6} />;
  return <RunDocument detail={run.data} />;
}

/** The published list (or the letters) of a run. Exported for tests. */
export function RunDocument({ detail }: { detail: LotteryRunDetail }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [letters, setLetters] = useState(false);
  const { run, intake } = detail;
  const allotted = detail.allotments.filter((a) => a.outcome === "ALLOTTED");
  return (
    <>
      <PageHead
        eyebrow={t("govreport.rte.title")}
        title={t("govreport.rte.runTitle", {
          no: run.runNo,
          className: intake.className ?? "—",
        })}
        actions={
          <>
            <Link
              href={`/app/govreport/rte/${encodeURIComponent(intake.id)}`}
              className="btn"
            >
              <ArrowLeft size={18} aria-hidden="true" />
              {t("govreport.rte.backToIntake")}
            </Link>
            <button
              type="button"
              className="btn"
              onClick={() => setLetters((x) => !x)}
              data-testid="rte-toggle-letters"
            >
              {letters
                ? t("govreport.rte.showList")
                : t("govreport.rte.showLetters")}
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => window.print()}
            >
              <Printer size={18} aria-hidden="true" />
              {t("reports.print")}
            </button>
          </>
        }
      />
      <VerifyNote className="no-print" />
      {!run.current ? (
        <div className="alert alert-warn no-print" role="note">
          <span>{t("govreport.rte.notCurrent")}</span>
        </div>
      ) : null}
      {letters ? (
        <div
          className="print-area flex flex-col gap-3.5"
          data-testid="rte-letters"
        >
          {allotted.length === 0 ? (
            <p className="empty">{t("govreport.rte.noAllotted")}</p>
          ) : null}
          {allotted.map((a) => (
            <article
              key={a.application.id}
              className="card break-after-page"
              aria-label={a.application.childName}
            >
              <p className="font-semibold">{detail.schoolName}</p>
              <h2 className="mt-2">{t("govreport.rte.letterTitle")}</h2>
              <p className="mt-2">
                {t("govreport.rte.letterTo", {
                  name: a.application.guardianName ?? "—",
                })}
              </p>
              <p className="mt-2">
                {t("govreport.rte.letterBody", {
                  child: a.application.childName,
                  className: intake.className ?? "—",
                  year: intake.academicYearName ?? "",
                  date: formatPlainDate(run.ranAt.slice(0, 10), locale),
                  position: a.position,
                })}
              </p>
              <p className="mt-2">{t("govreport.rte.letterDocs")}</p>
              <p className="mt-8">{t("govreport.rte.letterSign")}</p>
            </article>
          ))}
        </div>
      ) : (
        <section
          className="card print-area"
          data-testid="rte-run-list"
          aria-labelledby="rte-run-head"
        >
          <header className="mb-3">
            <p className="font-semibold">{detail.schoolName}</p>
            <h2 id="rte-run-head">
              {t("govreport.rte.listTitle", {
                className: intake.className ?? "—",
                year: intake.academicYearName ?? "",
              })}
            </h2>
            <p className="text-[13px] text-ink-3">
              {t("govreport.rte.runFacts", {
                date: formatDateTime(run.ranAt, locale),
                seed: run.seed,
                seats: run.seats,
                applicants: run.applicants,
                witness: run.witnessName,
              })}
            </p>
            {run.reason ? (
              <p className="text-[13px] text-ink-3">
                {t("govreport.rte.rerunReason", { reason: run.reason })}
              </p>
            ) : null}
          </header>
          <div className="table-wrap">
            <table className="table report-table">
              <caption className="sr-only">
                {t("govreport.rte.listTitle", {
                  className: intake.className ?? "—",
                  year: intake.academicYearName ?? "",
                })}
              </caption>
              <thead>
                <tr>
                  <th scope="col">{t("govreport.rte.position")}</th>
                  <th scope="col">{t("govreport.rte.child")}</th>
                  <th scope="col">{t("govreport.rte.band")}</th>
                  <th scope="col">{t("govreport.rte.result")}</th>
                </tr>
              </thead>
              <tbody>
                {detail.allotments.map((a) => (
                  <tr key={a.application.id} data-testid="rte-allotment">
                    <td className="num">{a.position}</td>
                    <th scope="row">{a.application.childName}</th>
                    <td className="num">{a.distanceBand}</td>
                    <td>
                      {a.outcome === "ALLOTTED" ? (
                        <Pill tone="good">{t("govreport.rte.allotted")}</Pill>
                      ) : (
                        <Pill tone="warn">
                          {t("govreport.rte.waitlistNo", {
                            no: a.waitlistNo ?? 0,
                          })}
                        </Pill>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  );
}
