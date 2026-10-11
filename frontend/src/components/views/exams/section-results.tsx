"use client";

import { ArrowLeft, FileText, Printer } from "lucide-react";
import Link from "next/link";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { examsApi } from "@/lib/exams-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { ResultRow, SectionResults, SubjectScore } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { formatPct, formatScore, ResultPill } from "./exams-shared";

/** A subject's score as shown in the results table: "72/100", "AB", or "—". Pure, exported for tests. */
export function scoreText(score: SubjectScore | undefined): string {
  if (!score) return "—";
  if (score.code) return score.code;
  if (score.obtained === null || score.max === null) return "—";
  return `${formatScore(score.obtained)}/${formatScore(score.max)}`;
}

/** /app/exams/results/[examId]/[sectionId]: every student's marks, total, grade, result and rank. */
export function SectionResultsView({ examId, sectionId }: { examId: string; sectionId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const results = useApiData(`exams:results:${examId}:${sectionId}`, () => examsApi.sectionResults(examId, sectionId));
  const data = results.data;

  return (
    <>
      <PageHead
        eyebrow={data ? `${data.examName} · ${data.termName}` : undefined}
        title={data ? t("exams.results.of", { section: data.sectionLabel }) : t("exams.results.title")}
        actions={
          <>
            {data ? (
              <Link
                href={`/app/exams/${encodeURIComponent(examId)}/${encodeURIComponent(data.classId)}`}
                className="btn"
              >
                <ArrowLeft size={18} aria-hidden="true" />
                {t("exams.backToExam")}
              </Link>
            ) : null}
            <button type="button" className="btn" disabled={!data} onClick={() => window.print()}>
              <Printer size={18} aria-hidden="true" />
              {t("exams.print")}
            </button>
          </>
        }
      />
      {results.error && !data ? (
        <section className="card">
          <ErrorState error={results.error} onRetry={results.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : (
        <section className="card report-doc" data-testid="section-results">
          <header className="report-print-head">
            <p className="report-print-school">{data.className}</p>
            <h1>{t("exams.results.of", { section: data.sectionLabel })}</h1>
            <p>
              {data.examName} · {data.termName}
            </p>
          </header>
          <div className="flex flex-wrap items-center gap-2">
            {data.resultsPublishedAt ? (
              <Pill tone="good" dot>
                {t("exams.results.publishedOn", { date: formatDateTime(data.resultsPublishedAt, locale) })}
              </Pill>
            ) : (
              <Pill tone="warn" dot>
                {t("exams.results.notPublished")}
              </Pill>
            )}
            {data.sheetsPending > 0 ? (
              <span className="text-[13px] text-ink-3">{plural(t, "exams.results.sheetsPending", data.sheetsPending)}</span>
            ) : null}
            <span className="text-[13px] text-ink-3">
              {t("exams.results.scheme", { name: data.scheme.name, pass: formatScore(data.scheme.passPercent) })}
            </span>
            <Link
              href={`/app/exams/report-cards?section=${encodeURIComponent(sectionId)}`}
              className="link no-print ml-auto inline-flex items-center gap-1 text-[13.5px]"
            >
              <FileText size={16} aria-hidden="true" />
              {t("exams.sections.reportCards")}
            </Link>
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("exams.marks.noStudents")}</p>
          ) : (
            <>
              <ResultsTable data={data} />
              <ul className="rowcards md:hidden" data-testid="results-cards">
                {data.rows.map((row) => (
                  <li key={row.studentId} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">
                        {row.rollNo !== null ? `${row.rollNo}. ` : ""}
                        {row.fullName}
                      </b>
                      <span className="block text-[12.5px] text-ink-3">
                        {[
                          row.obtained !== null && row.max !== null
                            ? `${formatScore(row.obtained)}/${formatScore(row.max)}`
                            : null,
                          formatPct(row.percent),
                          row.grade,
                          data.ranksEnabled && row.rank !== null ? t("exams.results.rankShort", { rank: row.rank }) : null,
                        ]
                          .filter(Boolean)
                          .join(" · ")}
                      </span>
                    </span>
                    <ResultPill result={row.result} />
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </>
  );
}

function ResultsTable({ data }: { data: SectionResults }) {
  const { t } = useI18n();
  const score = (row: ResultRow, subjectId: string) => row.subjects.find((s) => s.subjectId === subjectId);
  return (
    <div className="table-wrap hidden md:block">
      <table className="table report-table" data-testid="results-table">
        <caption className="sr-only">{t("exams.results.of", { section: data.sectionLabel })}</caption>
        <thead>
          <tr>
            <th scope="col">{t("exams.col.roll")}</th>
            <th scope="col">{t("exams.col.student")}</th>
            {data.subjects.map((s) => (
              <th key={s.subjectId} scope="col" className="r">
                {s.subjectName}
              </th>
            ))}
            <th scope="col" className="r">
              {t("exams.col.total")}
            </th>
            <th scope="col" className="r">
              {t("exams.col.percent")}
            </th>
            <th scope="col">{t("exams.col.grade")}</th>
            {data.scheme.gradePoints ? <th scope="col" className="r">{t("exams.col.gpa")}</th> : null}
            <th scope="col">{t("exams.col.result")}</th>
            {data.ranksEnabled ? <th scope="col" className="r">{t("exams.col.rank")}</th> : null}
          </tr>
        </thead>
        <tbody>
          {data.rows.map((row) => (
            <tr key={row.studentId}>
              <td className="num">{row.rollNo ?? "—"}</td>
              <th scope="row">{row.fullName}</th>
              {data.subjects.map((s) => {
                const cell = score(row, s.subjectId);
                return (
                  <td key={s.subjectId} className={`r num${cell?.passed === false ? " text-bad" : ""}`}>
                    {scoreText(cell)}
                    {cell?.grade && !cell.code ? <span className="block text-[11.5px] text-ink-3">{cell.grade}</span> : null}
                  </td>
                );
              })}
              <td className="r num">
                {row.obtained !== null && row.max !== null ? `${formatScore(row.obtained)}/${formatScore(row.max)}` : "—"}
              </td>
              <td className="r num">{formatPct(row.percent)}</td>
              <td className="font-semibold">{row.grade ?? "—"}</td>
              {data.scheme.gradePoints ? <td className="r num">{formatScore(row.gpa)}</td> : null}
              <td>
                <ResultPill result={row.result} />
              </td>
              {data.ranksEnabled ? <td className="r num">{row.rank ?? "—"}</td> : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
