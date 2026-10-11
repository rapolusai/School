"use client";

import { Check, ChevronDown, ChevronUp } from "lucide-react";
import { Fragment, useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { formatPct } from "@/components/views/exams/exams-shared";
import { Figure, ReportFrame } from "@/components/views/reports/report-frame";
import { PercentBar } from "@/components/views/reports/sections-report";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { lessonsApi, lessonsPaths } from "@/lib/lessons-api";
import type { CoverageQuery, CoverageRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { monthLabel, useLessonsOptions } from "./lessons-shared";

/** "8 of 12 topics". */
function taughtText(t: ReturnType<typeof useI18n>["t"], row: Pick<CoverageRow, "taught" | "topics">): string {
  return t("lessons.coverage.taughtOf", { taught: row.taught, topics: row.topics });
}

const rowKey = (row: Pick<CoverageRow, "sectionId" | "subjectId">) => `${row.sectionId}:${row.subjectId}`;

/**
 * Syllabus coverage (reports hub, Academics): each section and subject's topics taught against the syllabus, the
 * share expected by now from the topics' target months, and a behind-schedule flag; filters, CSV and print.
 */
export function SyllabusCoverageReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [yearId, setYearId] = useState("");
  const [classId, setClassId] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [sectionId, setSectionId] = useState("");
  const [behindOnly, setBehindOnly] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const options = useLessonsOptions(yearId);
  const classes = options.data?.classes ?? [];
  const klass = classes.find((c) => c.id === classId);
  const query: CoverageQuery = {
    yearId: yearId || undefined,
    classId: classId || undefined,
    subjectId: subjectId || undefined,
    sectionId: sectionId || undefined,
    behindOnly,
  };
  const report = useApiData(`lessons:coverage:${JSON.stringify(query)}`, () => lessonsApi.coverage(query));
  const data = report.data;
  const years = options.data?.years ?? [];
  const summary = [
    data?.yearName ?? null,
    klass ? klass.name : t("reports.filter.allClasses"),
    klass?.sections.find((s) => s.id === sectionId)?.label ?? null,
    klass?.subjects.find((s) => s.id === subjectId)?.name ?? null,
    behindOnly ? t("lessons.coverage.behindOnly") : null,
  ].filter((x): x is string => Boolean(x));

  return (
    <ReportFrame
      title={t("reports.syllabusCoverage.title")}
      testId="report-syllabus-coverage"
      filterSummary={summary}
      problem={null}
      excel={{ path: lessonsPaths.coverageCsv(query), fileName: "syllabus-coverage.csv" }}
      downloadLabel={t("lessons.coverage.csv")}
      ready={Boolean(data)}
      filters={
        <>
          {years.length > 1 ? (
            <label className="field w-full sm:w-auto sm:min-w-40">
              <span className="field-label">{t("exams.year")}</span>
              <select className="input" name="yearId" value={yearId || options.data?.yearId || ""} onChange={(e) => setYearId(e.target.value)}>
                {years.map((y) => (
                  <option key={y.id} value={y.id}>
                    {y.current ? t("exams.yearCurrent", { name: y.name }) : y.name}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("reports.filter.class")}</span>
            <select
              className="input"
              name="classId"
              value={classId}
              onChange={(e) => {
                setClassId(e.target.value);
                setSectionId("");
                setSubjectId("");
              }}
            >
              <option value="">{t("reports.filter.allClasses")}</option>
              {classes.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-[calc(50%-4px)] sm:w-auto sm:min-w-32">
            <span className="field-label">{t("lessons.section")}</span>
            <select className="input" name="sectionId" value={sectionId} disabled={!klass} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">{t("lessons.allSections")}</option>
              {(klass?.sections ?? []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-[calc(50%-4px)] sm:w-auto sm:min-w-36">
            <span className="field-label">{t("lessons.subject")}</span>
            <select className="input" name="subjectId" value={subjectId} disabled={!klass} onChange={(e) => setSubjectId(e.target.value)}>
              <option value="">{t("lessons.allSubjects")}</option>
              {(klass?.subjects ?? []).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="check self-end">
            <input type="checkbox" name="behindOnly" checked={behindOnly} onChange={(e) => setBehindOnly(e.target.checked)} />
            <span>{t("lessons.coverage.behindOnly")}</span>
          </label>
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : !data.yearId ? (
        <p className="empty">{t("lessons.noYear")}</p>
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("lessons.coverage.sections")} value={data.sections} />
            <Figure label={t("lessons.coverage.average")} value={formatPct(data.averagePercent)} testId="coverage-average" />
            <Figure label={t("lessons.coverage.behind")} value={data.behind} testId="coverage-behind" />
            <Figure label={t("lessons.coverage.month")} value={monthLabel(data.month, locale)} />
          </div>
          <p className="text-[13px] text-ink-3">{t("lessons.coverage.explain")}</p>
          {data.rows.length === 0 ? (
            <p className="empty">{behindOnly ? t("lessons.coverage.noneBehind") : t("lessons.coverage.empty")}</p>
          ) : (
            <>
              <div className="table-wrap hidden md:block">
                <table className="table report-table" data-testid="coverage-table">
                  <caption className="sr-only">{t("reports.syllabusCoverage.title")}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("lessons.section")}</th>
                      <th scope="col">{t("lessons.subject")}</th>
                      <th scope="col">{t("lessons.coverage.teacher")}</th>
                      <th scope="col" className="r">
                        {t("lessons.coverage.taught")}
                      </th>
                      <th scope="col" className="r">
                        {t("lessons.coverage.percent")}
                      </th>
                      <th scope="col" className="r">
                        {t("lessons.coverage.expected")}
                      </th>
                      <th scope="col" className="r">
                        {t("lessons.coverage.overdue")}
                      </th>
                      <th scope="col">{t("lessons.coverage.lastTaught")}</th>
                      <th scope="col" className="no-print">
                        <span className="sr-only">{t("lessons.coverage.topics")}</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((row) => (
                      <Fragment key={rowKey(row)}>
                        <tr data-testid="coverage-row" data-behind={row.behind || undefined}>
                          <th scope="row">{row.sectionLabel}</th>
                          <td>{row.subjectName}</td>
                          <td>{row.teacherName ?? "—"}</td>
                          <td className="r num">
                            {row.taught} / {row.topics}
                          </td>
                          <td className="r">
                            <span className="inline-flex items-center gap-2">
                              <PercentBar value={row.percent} />
                              <span className="num">{formatPct(row.percent)}</span>
                            </span>
                          </td>
                          <td className="r num">{formatPct(row.expectedPercent)}</td>
                          <td className="r">
                            {row.behind ? (
                              <Pill tone="warn" dot>
                                {plural(t, "lessons.coverage.overdueTopics", row.overdue)}
                              </Pill>
                            ) : (
                              <span className="num">{row.overdue}</span>
                            )}
                          </td>
                          <td>{row.lastTaughtOn ? formatPlainDate(row.lastTaughtOn, locale) : "—"}</td>
                          <td className="no-print">
                            <button
                              type="button"
                              className="iconbtn"
                              aria-expanded={open === rowKey(row)}
                              aria-label={t("lessons.coverage.showTopics", { section: row.sectionLabel, subject: row.subjectName })}
                              onClick={() => setOpen(open === rowKey(row) ? null : rowKey(row))}
                            >
                              {open === rowKey(row) ? <ChevronUp size={16} aria-hidden="true" /> : <ChevronDown size={16} aria-hidden="true" />}
                            </button>
                          </td>
                        </tr>
                        {open === rowKey(row) ? (
                          <tr>
                            <td colSpan={9}>
                              <CoverageTopics row={row} yearId={data.yearId} />
                            </td>
                          </tr>
                        ) : null}
                      </Fragment>
                    ))}
                  </tbody>
                </table>
              </div>
              <ul className="rowcards md:hidden" data-testid="coverage-cards">
                {data.rows.map((row) => (
                  <li key={rowKey(row)} className="rowcard flex-col items-stretch">
                    <button
                      type="button"
                      className="flex w-full items-start gap-2 text-left"
                      aria-expanded={open === rowKey(row)}
                      onClick={() => setOpen(open === rowKey(row) ? null : rowKey(row))}
                    >
                      <span className="min-w-0 flex-1">
                        <b className="block font-semibold">
                          {row.sectionLabel} · {row.subjectName}
                        </b>
                        <span className="block text-[12.5px] text-ink-3">
                          {[taughtText(t, row), t("lessons.coverage.expectedShort", { pct: formatPct(row.expectedPercent) }), row.teacherName]
                            .filter(Boolean)
                            .join(" · ")}
                        </span>
                        <PercentBar value={row.percent} />
                      </span>
                      <span className="flex flex-col items-end gap-1">
                        <b className="num">{formatPct(row.percent)}</b>
                        {row.behind ? (
                          <Pill tone="warn" dot>
                            {t("lessons.coverage.behindFlag")}
                          </Pill>
                        ) : null}
                      </span>
                    </button>
                    {open === rowKey(row) ? <CoverageTopics row={row} yearId={data.yearId} /> : null}
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/** One section and subject's topics: taught (with the day), overdue, or still to come. */
function CoverageTopics({ row, yearId }: { row: CoverageRow; yearId: string | null }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const detail = useApiData(`lessons:coverage-detail:${yearId}:${row.sectionId}:${row.subjectId}`, () =>
    lessonsApi.coverageDetail(row.sectionId, row.subjectId, yearId),
  );
  if (detail.error && !detail.data) return <ErrorState error={detail.error} onRetry={detail.reload} />;
  if (!detail.data) return <LoadingRows rows={2} />;
  return (
    <div className="flex flex-col gap-2 py-1" data-testid="coverage-topics">
      {detail.data.units.map((unit) => (
        <div key={unit.id}>
          <p className="text-[13px] font-semibold">{unit.title}</p>
          <ul className="mt-0.5 flex flex-col gap-0.5 text-[13px]">
            {unit.topics.map((topic) => (
              <li key={topic.id} className="flex flex-wrap items-baseline gap-x-2">
                {topic.taught ? (
                  <Check size={14} aria-label={t("lessons.coverage.taughtMark")} className="flex-none text-good" />
                ) : (
                  <span className="w-[14px] flex-none" />
                )}
                <span className={`min-w-0 flex-1 ${topic.overdue ? "text-warn" : ""}`}>{topic.title}</span>
                <span className="text-[12px] text-ink-3">
                  {topic.taught && topic.taughtOn
                    ? t("lessons.plan.taughtOn", { date: formatPlainDate(topic.taughtOn, locale) })
                    : topic.overdue
                      ? t("lessons.coverage.overdueBy", { month: monthLabel(topic.targetMonth, locale) })
                      : topic.targetMonth
                        ? t("lessons.coverage.by", { month: monthLabel(topic.targetMonth, locale) })
                        : ""}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}
