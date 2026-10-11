"use client";

import { ArrowRight, Award, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { ChildSwitcher, useFamilyChildren } from "@/components/views/portal/family-shared";
import { useAuth } from "@/lib/auth";
import { examsApi } from "@/lib/exams-api";
import { isParent } from "@/lib/family";
import { classLabel, formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import type { CardPeriod, FamilyResults, ReportCards } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { formatPct, ResultPill, shortTime } from "./exams-shared";
import { ReportCardPage } from "./report-card";

/** The report card to open first: the latest term with published results, else none. Pure, exported for tests. */
export function defaultPeriod(results: FamilyResults): CardPeriod | null {
  const published = results.terms.filter((term) => term.published);
  const latest = published[published.length - 1];
  return latest ? { termId: latest.id } : null;
}

/** /app/family/results: a child's (or the student's own) published results, report card and upcoming exams. */
export function FamilyResultsView({ initialChildId }: { initialChildId?: string }) {
  const { me } = useAuth();
  return isParent(me) ? <ParentResults initialChildId={initialChildId} /> : <StudentResults />;
}

function ParentResults({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  return (
    <>
      <PageHead
        eyebrow={child ? [child.fullName, classLabel(child.className, child.sectionName)].filter(Boolean).join(" · ") : undefined}
        title={t("nav.results")}
      />
      {children.error && !children.data ? (
        <section className="card">
          <ErrorState error={children.error} onRetry={children.reload} />
        </section>
      ) : !children.data ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : !child ? (
        <section className="card">
          <p className="empty">{t("children.empty")}</p>
        </section>
      ) : (
        <>
          <ChildSwitcher list={list} value={child.id} onChange={choose} />
          <ResultsBody
            key={child.id}
            cacheKey={`exams:family:${child.id}`}
            loadResults={() => examsApi.childResults(child.id)}
            loadCard={(period) => examsApi.childCard(child.id, period)}
          />
        </>
      )}
    </>
  );
}

function StudentResults() {
  const { t } = useI18n();
  return (
    <>
      <PageHead title={t("nav.results")} />
      <ResultsBody cacheKey="exams:family:me" loadResults={() => examsApi.myResults()} loadCard={examsApi.myCard} />
    </>
  );
}

function ResultsBody({
  cacheKey,
  loadResults,
  loadCard,
}: {
  cacheKey: string;
  loadResults: () => Promise<FamilyResults>;
  loadCard: (period: CardPeriod) => Promise<ReportCards>;
}) {
  const { t } = useI18n();
  const results = useApiData(cacheKey, loadResults);
  const data = results.data;
  if (results.error?.status === 404) {
    return (
      <section className="card">
        <p className="empty">{t("children.notLinked")}</p>
      </section>
    );
  }
  if (results.error && !data) {
    return (
      <section className="card">
        <ErrorState error={results.error} onRetry={results.reload} />
      </section>
    );
  }
  if (!data) {
    return (
      <section className="card">
        <LoadingRows rows={4} />
      </section>
    );
  }
  return (
    <>
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <PublishedList data={data} />
        <Upcoming data={data} />
      </div>
      <CardSection key={data.studentId} data={data} cacheKey={cacheKey} loadCard={loadCard} />
    </>
  );
}

function PublishedList({ data }: { data: FamilyResults }) {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-results-list" data-testid="family-results">
      <h2 id="family-results-list">{t("exams.family.published")}</h2>
      {data.results.length === 0 ? (
        <p className="text-sm text-ink-2">{t("exams.family.none")}</p>
      ) : (
        <ul className="rowcards">
          {data.results.map((r) => (
            <li key={r.examId} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block font-semibold">{r.examName}</b>
                <span className="block text-[12.5px] text-ink-3">
                  {[r.termName, formatPct(r.percent), r.grade].filter(Boolean).join(" · ")}
                </span>
              </span>
              <ResultPill result={r.result} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Upcoming({ data }: { data: FamilyResults }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-upcoming" data-testid="family-date-sheet">
      <h2 id="family-upcoming">{t("exams.family.upcoming")}</h2>
      {data.upcoming.length === 0 ? (
        <p className="text-sm text-ink-2">{t("exams.family.noUpcoming")}</p>
      ) : (
        data.upcoming.map((exam) => (
          <div key={exam.examId} className="flex flex-col gap-1">
            <p className="font-semibold">{exam.examName}</p>
            <ul className="rowcards">
              {exam.papers.map((p) => (
                <li key={p.id} className="rowcard">
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">{p.subjectName}</b>
                    <span className="block text-[12.5px] text-ink-3">
                      {[
                        p.date ? formatPlainDate(p.date, locale) : null,
                        p.startTime ? `${shortTime(p.startTime)}–${shortTime(p.endTime)}` : null,
                        p.room,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ))
      )}
    </section>
  );
}

function CardSection({
  data,
  cacheKey,
  loadCard,
}: {
  data: FamilyResults;
  cacheKey: string;
  loadCard: (period: CardPeriod) => Promise<ReportCards>;
}) {
  const { t } = useI18n();
  const first = defaultPeriod(data);
  const [value, setValue] = useState(first && "termId" in first ? `term:${first.termId}` : "");
  const published = data.terms.filter((term) => term.published);
  const period: CardPeriod | null = value.startsWith("term:")
    ? { termId: value.slice(5) }
    : value === "year" && data.year
      ? { yearId: data.year.id }
      : null;
  const card = useApiData(period ? `${cacheKey}:card:${value}` : null, () => loadCard(period!));
  if (published.length === 0) return null;
  return (
    <section className="flex flex-col gap-3" aria-labelledby="family-card">
      <div className="flex flex-wrap items-end gap-3">
        <h2 id="family-card" className="mr-auto">
          {t("exams.family.reportCard")}
        </h2>
        <label className="field w-full sm:w-auto sm:min-w-44">
          <span className="field-label">{t("exams.reportCards.period")}</span>
          <select className="input" name="period" value={value} onChange={(e) => setValue(e.target.value)}>
            {published.map((term) => (
              <option key={term.id} value={`term:${term.id}`}>
                {term.name}
              </option>
            ))}
            {data.year ? <option value="year">{t("exams.reportCards.wholeYear")}</option> : null}
          </select>
        </label>
        <button type="button" className="btn" disabled={!card.data} onClick={() => window.print()}>
          <Printer size={18} aria-hidden="true" />
          {t("exams.print")}
        </button>
      </div>
      <CardBody card={card} />
    </section>
  );
}

function CardBody({ card }: { card: ApiData<ReportCards> }) {
  if (card.error && !card.data) {
    return (
      <section className="card">
        <ErrorState error={card.error} onRetry={card.reload} />
      </section>
    );
  }
  if (!card.data || card.loading) {
    return (
      <section className="card">
        <LoadingRows rows={6} />
      </section>
    );
  }
  const cards = card.data;
  return (
    <div className="exam-doc">
      {cards.cards.map((c) => (
        <ReportCardPage key={c.studentId} cards={cards} card={c} />
      ))}
    </div>
  );
}

/** The family home's results card: the latest published result and the next exam, with a link to the page. */
export function FamilyResultsCard({ data, href }: { data: ApiData<FamilyResults>; href: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const view = data.data;
  const latest = view?.results[view.results.length - 1];
  const next = view?.upcoming[0];
  const firstPaper = next?.papers.find((p) => p.date);
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-results-card" data-testid="family-results-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="family-results-card">{t("nav.results")}</h2>
        <Award size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {data.error?.status === 404 ? (
        <p className="text-sm text-ink-2">{t("children.notLinked")}</p>
      ) : data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={2} />
      ) : (
        <>
          {latest ? (
            <div className="flex items-center gap-2">
              <span className="min-w-0 flex-1">
                <b className="block font-semibold">{latest.examName}</b>
                <span className="block text-[13px] text-ink-3">
                  {[formatPct(latest.percent), latest.grade].filter(Boolean).join(" · ")}
                </span>
              </span>
              <ResultPill result={latest.result} />
            </div>
          ) : (
            <p className="text-sm text-ink-2">{t("exams.family.none")}</p>
          )}
          {next && firstPaper?.date ? (
            <p className="text-[13px] text-ink-2">
              {t("exams.family.nextExam", { exam: next.examName, date: formatPlainDate(firstPaper.date, locale) })}
            </p>
          ) : null}
        </>
      )}
      <Link href={href} className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
        {t("exams.family.open")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </section>
  );
}
