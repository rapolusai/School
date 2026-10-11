"use client";

import { useState } from "react";
import { formatPct, formatScore } from "@/components/views/exams/exams-shared";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { examPaths, examsApi } from "@/lib/exams-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { ExamAnalysis, ExamStructure, WeakSubject } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { BarChart } from "./charts";
import { Figure, ReportFrame } from "./report-frame";
import { PercentBar } from "./sections-report";

/** "+4.5", "-2", "0" or "—": the change in average percentage since the previous exam. Pure, exported for tests. */
export function changeText(change: number | null | undefined): string {
  if (change === null || change === undefined) return "—";
  const rounded = Math.round(change * 100) / 100;
  return rounded > 0 ? `+${formatScore(rounded)}` : formatScore(rounded);
}

/** "Mathematics 25% · Hindi AB": the subjects a student fell short in. Pure, exported for tests. */
export function weakText(subjects: WeakSubject[]): string {
  return subjects.map((w) => `${w.subjectName} ${w.code ?? formatPct(w.percent)}`).join(" · ");
}

/** Every exam of the year with its classes, in term order, for the filters. Pure, exported for tests. */
export function examOptions(structure: ExamStructure | undefined) {
  return (structure?.terms ?? []).flatMap((term) =>
    term.exams.map((exam) => ({
      id: exam.id,
      name: exam.name,
      termName: term.name,
      classes: exam.classes.map((c) => ({ id: c.classId, name: c.className })),
    })),
  );
}

/** Result analysis of an exam for a class: averages, pass rates, grade spread, sections and who needs help. */
export function ExamAnalysisReportView({ initialExamId, initialClassId }: { initialExamId?: string; initialClassId?: string }) {
  const { t } = useI18n();
  const structure = useApiData("exams:structure:", () => examsApi.structure(null));
  const exams = examOptions(structure.data);
  const [examId, setExamId] = useState(initialExamId ?? "");
  const [classId, setClassId] = useState(initialClassId ?? "");
  const exam = exams.find((e) => e.id === examId) ?? (examId ? undefined : exams[0]);
  const classOption = exam?.classes.find((c) => c.id === classId) ?? (exam?.classes[0] && !classId ? exam.classes[0] : undefined);
  const chosenExam = exam?.id ?? "";
  const chosenClass = classOption?.id ?? "";
  const report = useApiData(chosenExam && chosenClass ? `exams:analysis:${chosenExam}:${chosenClass}` : null, () =>
    examsApi.analysis(chosenExam, chosenClass),
  );
  const data =
    report.data && report.data.examId === chosenExam && report.data.classId === chosenClass ? report.data : undefined;
  const problem = structure.data && exams.length === 0 ? t("exams.analysis.noExams") : null;

  return (
    <ReportFrame
      title={t("reports.examAnalysis.title")}
      testId="report-exam-analysis"
      filterSummary={exam && classOption ? [`${exam.name} · ${exam.termName}`, classOption.name] : []}
      problem={problem}
      excel={
        chosenExam && chosenClass
          ? { path: examPaths.analysisCsv(chosenExam, chosenClass), fileName: "result-analysis.csv" }
          : null
      }
      downloadLabel={t("exams.analysis.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-full sm:w-auto sm:min-w-52">
            <span className="field-label">{t("exams.analysis.exam")}</span>
            <select
              className="input"
              name="examId"
              value={chosenExam}
              onChange={(e) => {
                setExamId(e.target.value);
                setClassId("");
              }}
            >
              {exams.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.name} · {e.termName}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("reports.filter.class")}</span>
            <select className="input" name="classId" value={chosenClass} onChange={(e) => setClassId(e.target.value)}>
              {(exam?.classes ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
        </>
      }
    >
      {problem ? null : structure.error && !structure.data ? (
        <ErrorState error={structure.error} onRetry={structure.reload} />
      ) : !structure.data ? (
        <LoadingRows rows={5} />
      ) : !chosenExam || !chosenClass ? (
        <p className="empty">{t("exams.analysis.pick")}</p>
      ) : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <AnalysisBody report={data} />
      )}
    </ReportFrame>
  );
}

function AnalysisBody({ report }: { report: ExamAnalysis }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <>
      <p className="text-[13.5px] text-ink-2" data-testid="analysis-status">
        {report.resultsPublishedAt
          ? t("exams.analysis.published", { date: formatDateTime(report.resultsPublishedAt, locale) })
          : report.sheetsPending > 0
            ? plural(t, "exams.analysis.pending", report.sheetsPending)
            : t("exams.analysis.notPublished")}
      </p>
      <div className="report-figures">
        <Figure label={t("exams.analysis.students")} value={report.students} />
        <Figure label={t("exams.analysis.appeared")} value={report.appeared} />
        <Figure label={t("exams.analysis.average")} value={formatPct(report.average)} testId="analysis-average" />
        <Figure label={t("exams.analysis.passPercent")} value={formatPct(report.passPercent)} testId="analysis-pass" />
        {report.previousExamName ? (
          <Figure
            label={t("exams.analysis.previous", { exam: report.previousExamName })}
            value={formatPct(report.previousAverage)}
          />
        ) : null}
      </div>

      {report.grades.length > 0 ? (
        <div className="flex flex-col gap-1">
          <h2 className="text-base">{t("exams.analysis.grades")}</h2>
          <BarChart
            bars={report.grades.map((g) => ({ key: g.grade, label: g.grade, tick: g.grade, value: g.count }))}
            caption={t("exams.analysis.grades")}
            head={[t("exams.col.grade"), t("exams.analysis.studentsCol")]}
            formatValue={(value) => String(value)}
            formatAxis={(value) => String(Math.round(value))}
            testId="analysis-grades"
          />
        </div>
      ) : null}

      <h2 className="text-base">{t("exams.analysis.subjects")}</h2>
      {report.subjects.length === 0 ? (
        <p className="empty">{t("exams.analysis.noSubjects")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="analysis-subjects">
              <caption className="sr-only">{t("exams.analysis.subjects")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("exams.col.subject")}</th>
                  <th scope="col" className="r">{t("exams.col.maxMarks")}</th>
                  <th scope="col" className="r">{t("exams.analysis.appearedCol")}</th>
                  <th scope="col" className="r">{t("exams.analysis.absentCol")}</th>
                  <th scope="col" className="r">{t("exams.analysis.avgMarks")}</th>
                  <th scope="col" className="r">{t("exams.analysis.highest")}</th>
                  <th scope="col" className="r">{t("exams.analysis.lowest")}</th>
                  <th scope="col" className="r">{t("exams.analysis.avgPercent")}</th>
                  <th scope="col" className="r">{t("exams.analysis.passCol")}</th>
                  <th scope="col" className="r">{t("exams.analysis.change")}</th>
                </tr>
              </thead>
              <tbody>
                {report.subjects.map((s) => (
                  <tr key={s.subjectId}>
                    <th scope="row">{s.subjectName}</th>
                    <td className="r num">{formatScore(s.max)}</td>
                    <td className="r num">{s.appeared}</td>
                    <td className="r num">{s.absent}</td>
                    <td className="r num">{formatScore(s.averageMarks)}</td>
                    <td className="r num">{formatScore(s.highest)}</td>
                    <td className="r num">{formatScore(s.lowest)}</td>
                    <td className="r">
                      <span className="inline-flex items-center gap-2">
                        <PercentBar value={s.average} />
                        <span className="num">{formatPct(s.average)}</span>
                      </span>
                    </td>
                    <td className="r num">{formatPct(s.passPercent)}</td>
                    <td className={`r num${s.change !== null && s.change < 0 ? " text-bad" : ""}`}>{changeText(s.change)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="analysis-subject-cards">
            {report.subjects.map((s) => (
              <li key={s.subjectId} className="rowcard">
                <div className="min-w-0 flex-1">
                  <b className="block truncate font-semibold">{s.subjectName}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {t("exams.analysis.subjectLine", {
                      appeared: s.appeared,
                      absent: s.absent,
                      high: formatScore(s.highest),
                      low: formatScore(s.lowest),
                      pass: formatPct(s.passPercent),
                    })}
                  </span>
                  <PercentBar value={s.average} />
                </div>
                <b className="num">{formatPct(s.average)}</b>
              </li>
            ))}
          </ul>
        </>
      )}

      {report.sections.length > 1 ? (
        <>
          <h2 className="text-base">{t("exams.analysis.sections")}</h2>
          <div className="table-wrap">
            <table className="table report-table" data-testid="analysis-sections">
              <caption className="sr-only">{t("exams.analysis.sections")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("exams.section")}</th>
                  <th scope="col" className="r">{t("exams.analysis.students")}</th>
                  <th scope="col" className="r">{t("exams.analysis.avgPercent")}</th>
                  <th scope="col" className="r">{t("exams.analysis.passCol")}</th>
                </tr>
              </thead>
              <tbody>
                {report.sections.map((s) => (
                  <tr key={s.sectionId}>
                    <th scope="row">{s.label}</th>
                    <td className="r num">{s.students}</td>
                    <td className="r num">{formatPct(s.average)}</td>
                    <td className="r num">{formatPct(s.passPercent)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : null}

      <h2 className="text-base">{t("exams.analysis.belowPass")}</h2>
      {report.belowPass.length === 0 ? (
        <p className="text-sm text-good">{t("exams.analysis.noneBelow")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="analysis-below-pass">
              <caption className="sr-only">{t("exams.analysis.belowPass")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("exams.col.student")}</th>
                  <th scope="col">{t("exams.section")}</th>
                  <th scope="col">{t("exams.analysis.weakSubjects")}</th>
                  <th scope="col" className="r">
                    {t("exams.col.percent")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {report.belowPass.map((s) => (
                  <tr key={s.studentId}>
                    <th scope="row">{s.fullName}</th>
                    <td>{s.sectionLabel}</td>
                    <td>{weakText(s.subjects)}</td>
                    <td className="r num">{formatPct(s.percent)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="analysis-below-pass-cards">
            {report.belowPass.map((s) => (
              <li key={s.studentId} className="rowcard">
                <div className="min-w-0 flex-1">
                  <b className="block font-semibold">{s.fullName}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {[s.sectionLabel, weakText(s.subjects)].join(" · ")}
                  </span>
                </div>
                <b className="num">{formatPct(s.percent)}</b>
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="text-[12.5px] text-ink-3">
        {t("exams.analysis.rule", {
          scheme: report.scheme.name,
          pass: formatScore(report.scheme.passPercent),
        })}
      </p>
    </>
  );
}
