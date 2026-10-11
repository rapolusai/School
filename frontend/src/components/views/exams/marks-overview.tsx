"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { useId, useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { examsApi } from "@/lib/exams-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { ExamMarks, MarkSheetRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ExamsNav, SheetPill, YearSelect } from "./exams-shared";

type Show = "todo" | "all";

/** The sheets still to finish: not started or draft. Pure, exported for tests. */
export function sheetsToShow(exam: ExamMarks, show: Show): MarkSheetRow[] {
  return show === "all" ? exam.sheets : exam.sheets.filter((s) => s.status !== "LOCKED");
}

export function sheetHref(row: Pick<MarkSheetRow, "paperId" | "sectionId">): string {
  return `/app/exams/marks/${encodeURIComponent(row.paperId)}/${encodeURIComponent(row.sectionId)}`;
}

/** /app/exams/marks: every mark sheet the person may see, exam by exam, with how far each has got. */
export function MarksOverviewView() {
  const { t } = useI18n();
  const name = useId();
  const [yearId, setYearId] = useState("");
  const [show, setShow] = useState<Show>("todo");
  const overview = useApiData(`exams:marks:${yearId}`, () => examsApi.marks(yearId || null));
  const data = overview.data && (!yearId || overview.data.year?.id === yearId) ? overview.data : undefined;

  return (
    <>
      <PageHead eyebrow={data?.year?.name} title={t("exams.marks.title")} />
      <ExamsNav />
      <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("exams.filters")}>
        {data ? <YearSelect years={data.years} value={yearId || data.year?.id || ""} onChange={setYearId} /> : null}
        <div className="seg self-end" role="radiogroup" aria-label={t("exams.marks.show")}>
          {(["todo", "all"] as const).map((value) => (
            <label key={value}>
              <input type="radio" name={name} value={value} checked={show === value} onChange={() => setShow(value)} />
              {t(value === "todo" ? "exams.marks.showTodo" : "exams.marks.showAll")}
            </label>
          ))}
        </div>
      </form>
      <p className="-mt-1 text-[13.5px] text-ink-2">
        {data?.wholeSchool ? t("exams.marks.introWhole") : t("exams.marks.introOwn")}
      </p>

      {overview.error && !data ? (
        <section className="card">
          <ErrorState error={overview.error} onRetry={overview.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : data.exams.length === 0 ? (
        <section className="card">
          <p className="empty">{data.wholeSchool ? t("exams.marks.emptyWhole") : t("exams.marks.emptyOwn")}</p>
        </section>
      ) : (
        data.exams.map((exam) => <ExamProgress key={exam.examId} exam={exam} show={show} />)
      )}
    </>
  );
}

function ExamProgress({ exam, show }: { exam: ExamMarks; show: Show }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const rows = sheetsToShow(exam, show);
  const lockedPercent = exam.total === 0 ? 0 : Math.round((exam.locked / exam.total) * 100);
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={`marks-${exam.examId}`} data-testid="marks-exam">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 id={`marks-${exam.examId}`}>{exam.examName}</h2>
          <p className="mt-1 text-[13px] text-ink-3">{exam.termName}</p>
        </div>
        <span className="text-[13.5px] font-semibold num" data-testid="marks-progress">
          {t("exams.marks.locked", { locked: exam.locked, total: exam.total })}
        </span>
      </div>
      <div className="meter" aria-hidden="true">
        <span style={{ width: `${lockedPercent}%` }} />
      </div>
      <p className="text-[13px] text-ink-2">
        {[
          plural(t, "exams.marks.draftCount", exam.draft),
          plural(t, "exams.marks.notStartedCount", exam.notStarted),
        ].join(" · ")}
      </p>
      {rows.length === 0 ? (
        <p className="text-sm text-good">{t("exams.marks.allLocked")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table">
              <caption className="sr-only">{exam.examName}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("exams.col.class")}</th>
                  <th scope="col">{t("exams.col.subject")}</th>
                  <th scope="col">{t("exams.col.date")}</th>
                  <th scope="col">{t("exams.col.status")}</th>
                  <th scope="col" className="r">
                    {t("exams.col.entered")}
                  </th>
                  <th scope="col">{t("exams.col.lastSaved")}</th>
                  <th scope="col">
                    <span className="sr-only">{t("common.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={`${row.paperId}:${row.sectionId}`}>
                    <td className="font-semibold">{row.sectionLabel}</td>
                    <td>{row.subjectName}</td>
                    <td>{row.paperDate ? formatPlainDate(row.paperDate, locale) : "—"}</td>
                    <td>
                      <SheetPill status={row.status} />
                    </td>
                    <td className="r num">
                      {row.entered}/{row.students}
                    </td>
                    <td className="text-[13px] text-ink-3">{row.updatedByName ?? "—"}</td>
                    <td className="r">
                      <Link href={sheetHref(row)} className="link inline-flex items-center gap-1 whitespace-nowrap">
                        {row.canEdit ? t("exams.marks.enter") : t("exams.marks.view")}
                        <ArrowRight size={16} aria-hidden="true" />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="marks-cards">
            {rows.map((row) => (
              <li key={`${row.paperId}:${row.sectionId}`}>
                <Link href={sheetHref(row)} className="rowcard" data-testid="marks-sheet-link">
                  <span className="min-w-0 flex-1">
                    <b className="block truncate font-semibold">
                      {row.sectionLabel} · {row.subjectName}
                    </b>
                    <span className="block text-[12.5px] text-ink-3">
                      {[
                        row.paperDate ? formatPlainDate(row.paperDate, locale) : null,
                        t("exams.marks.enteredOf", { entered: row.entered, total: row.students }),
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </span>
                  <SheetPill status={row.status} />
                  <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
