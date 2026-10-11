"use client";

import { translateOr, useI18n, type Translate } from "@/lib/i18n";
import type { CardCell, CardColumn, GradeBand, Hpc, ReportCard, ReportCards } from "@/lib/types";
import { CO_SCHOLASTIC_AREAS, HPC_DOMAINS, HPC_LEVELS } from "@/lib/types";
import { formatPct, formatScore, ResultPill } from "./exams-shared";
import { SchoolHead } from "./hall-tickets";

/* A4 report cards (term and year) with the NEP 2020 holistic progress card, for staff and families. */

/** A cell of the scholastic table: "37/40", "AB", "89.5%" or "—". Pure, exported for tests. */
export function cellText(cell: CardCell | undefined): string {
  if (!cell) return "—";
  if (cell.code) return cell.code;
  if (cell.obtained !== null && cell.max !== null) return `${formatScore(cell.obtained)}/${formatScore(cell.max)}`;
  if (cell.percent !== null) return formatPct(cell.percent);
  return "—";
}

/** "A1 91%+ · A2 81%+ …", best first. Pure, exported for tests. */
export function scaleText(bands: GradeBand[]): string {
  return [...bands]
    .sort((a, b) => b.minPercent - a.minPercent)
    .map((band) => `${band.grade} ${formatScore(band.minPercent)}%+`)
    .join(" · ");
}

function columnName(t: Translate, column: CardColumn): string {
  return column.weight === null ? column.name : t("exams.card.columnWeight", { name: column.name, weight: formatScore(column.weight) });
}

/** Every card of a set, one A4 page each. */
export function ReportCardPages({ cards }: { cards: ReportCards }) {
  return (
    <div className="exam-doc" data-testid="report-cards">
      {cards.cards.map((card) => (
        <ReportCardPage key={card.studentId} cards={cards} card={card} />
      ))}
    </div>
  );
}

/** A student's report card page and, for classes that have it, the holistic progress card as a page of its own. */
export function ReportCardPage({ cards, card }: { cards: ReportCards; card: ReportCard }) {
  return (
    <>
      <ScholasticPage cards={cards} card={card} />
      {cards.hpcEnabled && card.hpc ? <HpcPage cards={cards} card={card} hpc={card.hpc} /> : null}
    </>
  );
}

function periodName(t: Translate, cards: ReportCards): string {
  return cards.kind === "TERM" && cards.termName ? cards.termName : t("exams.reportCards.wholeYear");
}

/** Student, class, roll and admission number, and the class teacher. */
function StudentFacts({ cards, card }: { cards: ReportCards; card: ReportCard }) {
  const { t } = useI18n();
  return (
    <dl className="exam-facts">
      <div>
        <dt>{t("exams.card.student")}</dt>
        <dd>{card.fullName}</dd>
      </div>
      <div>
        <dt>{t("exams.card.class")}</dt>
        <dd>{card.classLabel}</dd>
      </div>
      <div>
        <dt>{t("exams.card.roll")}</dt>
        <dd className="num">{card.rollNo ?? "—"}</dd>
      </div>
      <div>
        <dt>{t("exams.card.admissionNo")}</dt>
        <dd>{card.admissionNo}</dd>
      </div>
      {cards.classTeacherName ? (
        <div>
          <dt>{t("exams.card.classTeacher")}</dt>
          <dd>{cards.classTeacherName}</dd>
        </div>
      ) : null}
    </dl>
  );
}

function ScholasticPage({ cards, card }: { cards: ReportCards; card: ReportCard }) {
  const { t } = useI18n();
  const title =
    cards.kind === "TERM" && cards.termName
      ? t("exams.card.termTitle", { term: cards.termName })
      : t("exams.card.yearTitle");
  const hasCoScholastic = card.coScholastic && CO_SCHOLASTIC_AREAS.some((area) => card.coScholastic?.[area]);
  return (
    <article className="card exam-page" aria-label={t("exams.card.for", { name: card.fullName })} data-testid="report-card">
      <SchoolHead school={cards.school} title={title} sub={t("exams.card.year", { year: cards.academicYearName })} />
      <StudentFacts cards={cards} card={card} />

      <div className="flex flex-col gap-2">
        <p className="exam-section-title">{t("exams.card.scholastic")}</p>
        {cards.columns.length === 0 ? (
          <p className="text-sm text-ink-2">{cards.publishedOnly ? t("exams.card.nothingPublished") : t("exams.card.noExams")}</p>
        ) : (
          <div className="table-wrap">
            <table className="table exam-table" data-testid="card-subjects">
              <caption className="sr-only">{t("exams.card.scholastic")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("exams.col.subject")}</th>
                  {cards.columns.map((column) => (
                    <th key={column.id} scope="col" className="c">
                      {columnName(t, column)}
                    </th>
                  ))}
                  <th scope="col" className="c">
                    {t("exams.col.percent")}
                  </th>
                  <th scope="col" className="c">
                    {t("exams.col.grade")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {card.subjects.map((subject) => (
                  <tr key={subject.subjectId}>
                    <th scope="row">{subject.subjectName}</th>
                    {cards.columns.map((column) => {
                      const cell = subject.cells.find((c) => c.columnId === column.id);
                      return (
                        <td key={column.id} className="c num">
                          {cellText(cell)}
                          {cell?.grade && !cell.code ? <span className="block text-[11.5px] text-ink-3">{cell.grade}</span> : null}
                        </td>
                      );
                    })}
                    <td className="c num">{formatPct(subject.percent)}</td>
                    <td className={`c font-bold${subject.passed === false ? " text-bad" : ""}`}>{subject.grade ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("exams.card.overall")}</th>
                  <td colSpan={cards.columns.length} />
                  <td className="c num" data-testid="card-percent">
                    {formatPct(card.percent)}
                  </td>
                  <td className="c">{card.grade ?? "—"}</td>
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </div>

      <dl className="exam-facts">
        <div>
          <dt>{t("exams.card.result")}</dt>
          <dd>
            <ResultPill result={card.result} />
          </dd>
        </div>
        {cards.scheme.gradePoints && card.gpa !== null ? (
          <div>
            <dt>{t("exams.card.gpa")}</dt>
            <dd className="num">{formatScore(card.gpa)}</dd>
          </div>
        ) : null}
        {cards.ranksEnabled && card.rank !== null ? (
          <div>
            <dt>{t("exams.card.rank")}</dt>
            <dd className="num">{card.rank}</dd>
          </div>
        ) : null}
        {card.attendance ? (
          <div>
            <dt>{t("exams.card.attendance")}</dt>
            <dd className="num">
              {card.attendance.daysMarked === 0
                ? "—"
                : t("exams.card.attendanceValue", {
                    present: card.attendance.present + card.attendance.late,
                    total: card.attendance.daysMarked,
                    percent: formatPct(card.attendance.presentPercent),
                  })}
            </dd>
          </div>
        ) : null}
      </dl>

      {hasCoScholastic ? (
        <div className="flex flex-col gap-2">
          <p className="exam-section-title">{t("exams.card.coScholastic")}</p>
          <dl className="exam-facts" data-testid="card-co-scholastic">
            {CO_SCHOLASTIC_AREAS.map((area) => (
              <div key={area}>
                <dt>{t(`exams.area.${area}`)}</dt>
                <dd>{card.coScholastic?.[area] ?? "—"}</dd>
              </div>
            ))}
          </dl>
        </div>
      ) : null}

      {card.remark ? (
        <div className="flex flex-col gap-1">
          <p className="exam-section-title">{t("exams.card.remark")}</p>
          <p className="text-[14px]">{card.remark}</p>
        </div>
      ) : null}

      <p className="text-[12px] text-ink-3">
        {t("exams.card.scale", {
          scheme: cards.scheme.name,
          bands: scaleText(cards.scheme.bands),
          pass: formatScore(cards.scheme.passPercent),
        })}
      </p>
      <div className="exam-signs">
        <span>{t("exams.sign.classTeacher")}</span>
        <span>{t("exams.sign.principal")}</span>
        <span>{t("exams.sign.parent")}</span>
      </div>
    </article>
  );
}

/** The NEP 2020 holistic progress card of a term or the year, printed as its own A4 card. */
function HpcPage({ cards, card, hpc }: { cards: ReportCards; card: ReportCard; hpc: Hpc }) {
  const { t } = useI18n();
  return (
    <article className="card exam-page" aria-label={t("exams.hpc.for", { name: card.fullName })} data-testid="hpc-card">
      <SchoolHead
        school={cards.school}
        title={t("exams.hpc.title")}
        sub={`${periodName(t, cards)} · ${t("exams.card.year", { year: cards.academicYearName })}`}
      />
      <StudentFacts cards={cards} card={card} />
      <HpcBlock hpc={hpc} />
      <div className="exam-signs">
        <span>{t("exams.sign.classTeacher")}</span>
        <span>{t("exams.sign.principal")}</span>
        <span>{t("exams.sign.parent")}</span>
      </div>
    </article>
  );
}

/** The holistic progress card: a level per domain on a four-step scale, and the three reflections. */
function HpcBlock({ hpc }: { hpc: Hpc }) {
  const { t } = useI18n();
  const reflections = [
    ["exams.hpc.teacherReflection", hpc.teacherReflection],
    ["exams.hpc.selfReflection", hpc.selfReflection],
    ["exams.hpc.parentReflection", hpc.parentReflection],
  ] as const;
  return (
    <div className="flex flex-col gap-2.5" data-testid="card-hpc">
      <p className="exam-section-title">{t("exams.hpc.domains")}</p>
      <div className="hpc-levels">
        {HPC_DOMAINS.map((domain) => {
          const level = hpc[domain];
          const step = level ? HPC_LEVELS.indexOf(level) : -1;
          return (
            <div key={domain} className="hpc-level">
              <span className="flex items-baseline justify-between gap-2 text-[13.5px]">
                <b className="font-semibold">{t(`exams.hpc.${domain}`)}</b>
                <span className="text-ink-2">{level ? translateOr(t, `exams.hpc.level.${level}`, level) : "—"}</span>
              </span>
              <span className="hpc-scale" aria-hidden="true">
                {HPC_LEVELS.map((l, i) => (
                  <span key={l} data-on={i <= step} />
                ))}
              </span>
            </div>
          );
        })}
      </div>
      {reflections.map(([key, text]) =>
        text ? (
          <div key={key} className="flex flex-col gap-0.5">
            <p className="text-[12.5px] font-semibold text-ink-3">{t(key)}</p>
            <p className="text-[13.5px]">{text}</p>
          </div>
        ) : null,
      )}
    </div>
  );
}
