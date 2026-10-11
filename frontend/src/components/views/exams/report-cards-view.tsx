"use client";

import { NotebookPen, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { examsApi } from "@/lib/exams-api";
import { classLabel } from "@/lib/format";
import { plural, useI18n } from "@/lib/i18n";
import type { CardPeriod } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ExamsNav, YearSelect } from "./exams-shared";
import { ReportCardPages } from "./report-card";

/** The period choice as one value: "term:<id>" or "year:<id>". Pure, exported for tests. */
export function periodOf(value: string): CardPeriod | null {
  const [kind, id] = value.split(":");
  if (!id) return null;
  if (kind === "term") return { termId: id };
  if (kind === "year") return { yearId: id };
  return null;
}

/** /app/exams/report-cards: a section's report cards for a term or the year, printed one A4 page each. */
export function ReportCardsView({
  initialSectionId,
  initialTermId,
  initialYearId,
}: {
  initialSectionId?: string;
  initialTermId?: string;
  initialYearId?: string;
}) {
  const { t } = useI18n();
  const [yearId, setYearId] = useState(initialYearId ?? "");
  const structure = useApiData(`exams:structure:${yearId}`, () => examsApi.structure(yearId || null));
  const classes = useApiData("exams:school-classes", api.listClasses);
  const [sectionId, setSectionId] = useState(initialSectionId ?? "");
  const [period, setPeriod] = useState(
    initialTermId ? `term:${initialTermId}` : initialYearId ? `year:${initialYearId}` : "",
  );
  const year = structure.data?.year ?? null;
  const terms = structure.data?.terms ?? [];
  const chosen = periodOf(period) ?? (terms[0] ? { termId: terms[0].id } : year ? { yearId: year.id } : null);
  const periodValue = chosen ? ("termId" in chosen ? `term:${chosen.termId}` : `year:${chosen.yearId}`) : "";
  const cardsKey = sectionId && chosen ? `exams:cards:${sectionId}:${periodValue}` : null;
  const cards = useApiData(cardsKey, () => examsApi.reportCards(sectionId, chosen!));
  const sections = (classes.data ?? []).flatMap((c) =>
    c.sections.map((s) => ({ id: s.id, label: classLabel(c.name, s.name) })),
  );
  const termId = chosen && "termId" in chosen ? chosen.termId : null;
  const data = cards.data && cards.data.sectionId === sectionId ? cards.data : undefined;

  return (
    <>
      <PageHead
        eyebrow={year?.name}
        title={t("exams.reportCards.title")}
        actions={
          <>
            {termId && sectionId ? (
              <Link
                href={`/app/exams/records/${encodeURIComponent(termId)}/${encodeURIComponent(sectionId)}`}
                className="btn"
              >
                <NotebookPen size={18} aria-hidden="true" />
                {t("exams.records.link")}
              </Link>
            ) : null}
            <button
              type="button"
              className="btn btn-primary"
              disabled={!data || data.cards.length === 0}
              onClick={() => window.print()}
            >
              <Printer size={18} aria-hidden="true" />
              {t("exams.print")}
            </button>
          </>
        }
      />
      <ExamsNav />
      <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("exams.filters")}>
        {structure.data ? (
          <YearSelect
            years={structure.data.years}
            value={yearId || year?.id || ""}
            onChange={(next) => {
              setYearId(next);
              setPeriod("");
            }}
          />
        ) : null}
        <label className="field w-full sm:w-auto sm:min-w-44">
          <span className="field-label">{t("exams.section")}</span>
          <select className="input" name="sectionId" value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
            <option value="">{t("exams.chooseSection")}</option>
            {sections.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="field w-full sm:w-auto sm:min-w-44">
          <span className="field-label">{t("exams.reportCards.period")}</span>
          <select className="input" name="period" value={periodValue} onChange={(e) => setPeriod(e.target.value)}>
            {terms.map((term) => (
              <option key={term.id} value={`term:${term.id}`}>
                {term.name}
              </option>
            ))}
            {year ? <option value={`year:${year.id}`}>{t("exams.reportCards.wholeYear")}</option> : null}
          </select>
        </label>
        {data ? <p className="self-end text-[13.5px] text-ink-2">{plural(t, "exams.reportCards.count", data.cards.length)}</p> : null}
      </form>

      {structure.error && !structure.data ? (
        <section className="card">
          <ErrorState error={structure.error} onRetry={structure.reload} />
        </section>
      ) : !sectionId ? (
        <section className="card">
          <p className="empty">{t("exams.reportCards.pick")}</p>
        </section>
      ) : !chosen ? (
        <section className="card">
          <p className="empty">{t("exams.terms.empty")}</p>
        </section>
      ) : cards.error && !data ? (
        <section className="card">
          <ErrorState error={cards.error} onRetry={cards.reload} />
        </section>
      ) : !data || cards.loading ? (
        <section className="card">
          <LoadingRows rows={8} />
        </section>
      ) : data.cards.length === 0 ? (
        <section className="card">
          <p className="empty">{t("exams.reportCards.empty")}</p>
        </section>
      ) : (
        <ReportCardPages cards={data} />
      )}
    </>
  );
}
