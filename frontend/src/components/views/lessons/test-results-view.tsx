"use client";

import { ArrowLeft, Check, Megaphone, X } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPct, formatScore, parseDecimal } from "@/components/views/exams/exams-shared";
import { Figure } from "@/components/views/reports/report-frame";
import { PercentBar } from "@/components/views/reports/sections-report";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { LESSONS_LIMITS, type AnswerReview, type AttemptReview, type MarkRequest, type StudentTestStatus, type TestResults } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { LessonsNav, TestStatePill, difficultyLabel, typeLabel } from "./lessons-shared";

const STATUS_TONES: Record<StudentTestStatus, PillTone> = {
  NOT_STARTED: "neutral",
  IN_PROGRESS: "info",
  SUBMITTED: "good",
  MARKING: "warn",
};

function StudentStatusPill({ status }: { status: StudentTestStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={STATUS_TONES[status] ?? "neutral"} dot>
      {translateOr(t, `lessons.results.status.${status}`, status)}
    </Pill>
  );
}

/** /app/lessons/tests/[testId]/results: each student's score, the question analysis and releasing results. */
export function TestResultsView({ testId }: { testId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const loaded = useApiData(`lessons:results:${testId}`, () => lessonsApi.results(testId));
  const [current, setCurrent] = useState<TestResults | null>(null);
  const results = current && current.testId === testId ? current : loaded.data;
  const [releasing, setReleasing] = useState(false);
  const testHref = `/app/lessons/tests/${encodeURIComponent(testId)}`;

  if (loaded.error && !results) {
    return (
      <>
        <PageHead title={t("lessons.results.title")} />
        <section className="card">
          <ErrorState error={loaded.error} onRetry={loaded.reload} />
        </section>
      </>
    );
  }
  if (!results) {
    return (
      <>
        <PageHead title={t("lessons.results.title")} />
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  return (
    <>
      <PageHead
        eyebrow={t("lessons.results.title")}
        title={results.title}
        actions={
          <>
            <Link href={testHref} className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("lessons.results.backToTest")}
            </Link>
            {results.canEdit && !results.released ? (
              <button
                type="button"
                className="btn btn-primary"
                disabled={results.releaseBlocker !== null}
                onClick={() => setReleasing(true)}
              >
                <Megaphone size={18} aria-hidden="true" />
                {t("lessons.results.release")}
              </button>
            ) : null}
          </>
        }
      />
      <LessonsNav />
      <section className="card flex flex-col gap-3" data-testid="test-results">
        <div className="flex flex-wrap items-center gap-2">
          <TestStatePill state={results.state} />
          {results.released && results.resultsReleasedAt ? (
            <Pill tone="good" dot>
              {t("lessons.results.releasedOn", { date: formatDateTime(results.resultsReleasedAt, locale) })}
            </Pill>
          ) : results.canEdit && results.releaseBlocker ? (
            <span className="text-[13px] text-ink-3" data-testid="release-blocker">
              {results.releaseBlocker}
            </span>
          ) : null}
        </div>
        <div className="report-figures">
          <Figure label={t("lessons.results.students")} value={results.studentCount} />
          <Figure label={t("lessons.results.attempted")} value={results.attempted} />
          <Figure label={t("lessons.results.toMark")} value={results.pendingMarking} testId="results-pending" />
          <Figure label={t("lessons.results.average")} value={formatPct(results.averagePercent)} />
          <Figure label={t("lessons.results.highest")} value={formatPct(results.highestPercent)} />
          <Figure label={t("lessons.results.lowest")} value={formatPct(results.lowestPercent)} />
        </div>

        <h2 className="text-base">{t("lessons.results.byStudent")}</h2>
        {results.students.length === 0 ? (
          <p className="empty">{t("lessons.results.noStudents")}</p>
        ) : (
          <ul className="rowcards" data-testid="results-students">
            {results.students.map((s) => {
              const attempt = s.bestAttemptId ?? s.attempts[s.attempts.length - 1]?.id ?? null;
              const body = (
                <>
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">{s.studentName}</b>
                    <span className="block text-[12.5px] text-ink-3">
                      {[s.sectionLabel, s.rollNo ? t("lessons.results.roll", { roll: s.rollNo }) : null, plural(t, "lessons.results.attempts", s.attempts.length)]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </span>
                  <span className="flex flex-col items-end gap-1">
                    <StudentStatusPill status={s.status} />
                    {s.score !== null ? (
                      <span className="num text-[13px]">
                        {formatScore(s.score)} / {formatScore(s.maxScore)} · {formatPct(s.percent)}
                      </span>
                    ) : null}
                    {s.pendingMarking > 0 ? (
                      <span className="text-[12px] text-warn">{plural(t, "lessons.tests.toMark", s.pendingMarking)}</span>
                    ) : null}
                  </span>
                </>
              );
              return (
                <li key={s.studentId}>
                  {attempt && s.status !== "IN_PROGRESS" ? (
                    <Link
                      href={`${testHref}/attempts/${encodeURIComponent(attempt)}`}
                      className="rowcard"
                      data-testid="result-row"
                      aria-label={t("lessons.results.open", { name: s.studentName })}
                    >
                      {body}
                    </Link>
                  ) : (
                    <div className="rowcard" data-testid="result-row">
                      {body}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}

        <h2 className="text-base">{t("lessons.results.byQuestion")}</h2>
        <div className="table-wrap">
          <table className="table report-table" data-testid="results-questions">
            <caption className="sr-only">{t("lessons.results.byQuestion")}</caption>
            <thead>
              <tr>
                <th scope="col" className="r">
                  #
                </th>
                <th scope="col">{t("lessons.questions.text")}</th>
                <th scope="col" className="r">
                  {t("lessons.results.answered")}
                </th>
                <th scope="col" className="r">
                  {t("lessons.results.correct")}
                </th>
                <th scope="col" className="r">
                  {t("lessons.results.avgMarks")}
                </th>
              </tr>
            </thead>
            <tbody>
              {results.questions.map((q) => (
                <tr key={q.testQuestionId}>
                  <td className="r num">{q.position}</td>
                  <th scope="row" className="font-normal">
                    <span className="line-clamp-2 block">{q.text}</span>
                    <span className="block text-[12px] text-ink-3">
                      {[typeLabel(t, q.type), difficultyLabel(t, q.difficulty)].join(" · ")}
                      {q.pendingMarking > 0 ? ` · ${plural(t, "lessons.tests.toMark", q.pendingMarking)}` : ""}
                    </span>
                  </th>
                  <td className="r num">{q.answered}</td>
                  <td className="r">
                    <span className="inline-flex items-center gap-2">
                      <PercentBar value={q.percentCorrect} />
                      <span className="num">{formatPct(q.percentCorrect)}</span>
                    </span>
                  </td>
                  <td className="r num">
                    {formatScore(q.averageMarks)} / {formatScore(q.marks)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <ConfirmDialog
        open={releasing}
        danger={false}
        title={t("lessons.results.releaseTitle")}
        body={t("lessons.results.releaseBody")}
        confirmLabel={t("lessons.results.release")}
        onClose={() => setReleasing(false)}
        onConfirm={async () => {
          setCurrent(await lessonsApi.release(testId));
          setReleasing(false);
          toast(t("lessons.results.releasedDone"));
        }}
      />
    </>
  );
}

type MarkDraft = { marks: string; feedback: string };

/** The marks to send: short answers not marked yet and every answer whose marks or feedback changed. Pure. */
export function changedMarks(answers: AnswerReview[], drafts: Record<string, MarkDraft>): MarkRequest[] | "invalid" {
  const out: MarkRequest[] = [];
  for (const a of answers) {
    const draft = drafts[a.answerId];
    if (!draft) continue;
    const marks = parseDecimal(draft.marks);
    const feedback = draft.feedback.trim() || null;
    if (marks === null) {
      if (a.marks === null) continue;
      return "invalid";
    }
    if (Number.isNaN(marks) || marks < 0 || marks > a.maxMarks || (feedback?.length ?? 0) > LESSONS_LIMITS.feedback) return "invalid";
    if (marks !== a.marks || feedback !== (a.feedback ?? null)) out.push({ answerId: a.answerId, marks, feedback });
  }
  return out;
}

/** /app/lessons/tests/[testId]/attempts/[attemptId]: one student's answers, auto marks, and marking short answers. */
export function AttemptMarkingView({ testId, attemptId }: { testId: string; attemptId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const loaded = useApiData(`lessons:attempt:${testId}:${attemptId}`, () => lessonsApi.attempt(testId, attemptId));
  const [current, setCurrent] = useState<AttemptReview | null>(null);
  const review = current && current.attemptId === attemptId ? current : loaded.data;

  if (loaded.error && !review) {
    return (
      <>
        <PageHead title={t("lessons.marking.title")} />
        <section className="card">
          <ErrorState error={loaded.error} onRetry={loaded.reload} />
        </section>
      </>
    );
  }
  if (!review) {
    return (
      <>
        <PageHead title={t("lessons.marking.title")} />
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }
  return (
    <>
      <PageHead
        eyebrow={review.testTitle}
        title={review.studentName}
        actions={
          <Link href={`/app/lessons/tests/${encodeURIComponent(testId)}/results`} className="btn">
            <ArrowLeft size={18} aria-hidden="true" />
            {t("lessons.marking.back")}
          </Link>
        }
      />
      <LessonsNav />
      <MarkingForm
        key={`${review.attemptId}:${review.score}:${review.pendingMarking}`}
        review={review}
        locale={locale}
        onSaved={(saved) => {
          setCurrent(saved);
          toast(t("lessons.marking.saved"));
        }}
      />
    </>
  );
}

function MarkingForm({
  review,
  locale,
  onSaved,
}: {
  review: AttemptReview;
  locale: string;
  onSaved: (review: AttemptReview) => void;
}) {
  const { t } = useI18n();
  const [drafts, setDrafts] = useState<Record<string, MarkDraft>>(() =>
    Object.fromEntries(
      review.answers.map((a) => [a.answerId, { marks: a.marks === null ? "" : formatScore(a.marks), feedback: a.feedback ?? "" }]),
    ),
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const marks = changedMarks(review.answers, drafts);
    setError(null);
    if (marks === "invalid") {
      setError(t("lessons.marking.invalid"));
      return;
    }
    if (marks.length === 0) {
      setError(t("lessons.marking.nothing"));
      return;
    }
    setSaving(true);
    try {
      onSaved(await lessonsApi.mark(review.testId, review.attemptId, marks));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form method="post" className="card flex flex-col gap-4" onSubmit={save} noValidate data-testid="attempt-marking">
      <p className="text-[13.5px] text-ink-2">
        {[
          review.sectionLabel,
          t("lessons.marking.attemptNo", { n: review.attemptNo }),
          review.submittedAt
            ? t(review.autoSubmitted ? "lessons.marking.autoSubmitted" : "lessons.marking.submitted", {
                date: formatDateTime(review.submittedAt, locale),
              })
            : t("lessons.marking.inProgress"),
        ].join(" · ")}
      </p>
      <div className="report-figures">
        <Figure
          label={t("lessons.marking.score")}
          value={review.score === null ? "—" : `${formatScore(review.score)} / ${formatScore(review.maxScore)}`}
          testId="attempt-score"
        />
        <Figure label={t("lessons.results.toMark")} value={review.pendingMarking} />
      </div>
      <FormAlert message={error} />
      <ol className="flex flex-col gap-3">
        {review.answers.map((a) => {
          const draft = drafts[a.answerId] ?? { marks: "", feedback: "" };
          const needsMarks = a.marks === null;
          return (
            <li key={a.answerId} className={`rounded-[10px] border p-3 ${needsMarks ? "border-warn" : "border-line"}`} data-testid="answer-review">
              <div className="flex flex-wrap items-start gap-2">
                <b className="text-[13px]">{t("lessons.tests.questionN", { n: a.position })}</b>
                <span className="text-[12.5px] text-ink-3">{typeLabel(t, a.type)}</span>
                <span className="flex-1" />
                {needsMarks ? (
                  <Pill tone="warn" dot>
                    {t("lessons.marking.toMark")}
                  </Pill>
                ) : a.autoMarked ? (
                  <Pill>{t("lessons.marking.auto")}</Pill>
                ) : null}
              </div>
              <p className="mt-1 whitespace-pre-line text-[14px] font-semibold">{a.text}</p>
              {a.options.length > 0 ? (
                <ul className="mt-1.5 flex flex-col gap-0.5 text-[13.5px]">
                  {a.options.map((o) => {
                    const picked = a.selectedOptionIds.includes(o.id);
                    return (
                      <li key={o.id} className={`flex items-start gap-1.5 ${o.correct ? "text-good" : picked ? "text-bad" : "text-ink-2"}`}>
                        {o.correct ? (
                          <Check size={15} aria-label={t("lessons.questions.correct")} className="mt-0.5 flex-none" />
                        ) : picked ? (
                          <X size={15} aria-label={t("lessons.marking.wrong")} className="mt-0.5 flex-none" />
                        ) : (
                          <span className="w-[15px] flex-none" />
                        )}
                        <span className={picked ? "font-semibold" : undefined}>
                          {o.text}
                          {picked ? ` (${t("lessons.marking.picked")})` : ""}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <div className="mt-1.5 flex flex-col gap-1 text-[13.5px]">
                  <p>
                    <b>{t("lessons.marking.response")}:</b>{" "}
                    {a.response
                      ? a.type === "TRUE_FALSE"
                        ? translateOr(t, `lessons.tf.${a.response}`, a.response)
                        : a.response
                      : <span className="text-ink-3">{t("lessons.marking.noAnswer")}</span>}
                  </p>
                  {a.answerText ? (
                    <p className="text-ink-2">
                      <b>{a.type === "SHORT_ANSWER" ? t("lessons.questions.model") : t("lessons.questions.answer")}:</b>{" "}
                      {a.type === "TRUE_FALSE" ? translateOr(t, `lessons.tf.${a.answerText}`, a.answerText) : a.answerText.split("\n").join(" | ")}
                    </p>
                  ) : null}
                </div>
              )}
              {a.explanation ? <p className="mt-1 text-[12.5px] text-ink-3">{a.explanation}</p> : null}
              {review.canMark ? (
                <div className="mt-2 flex flex-wrap items-start gap-2">
                  <label className="field w-28">
                    <span className="field-label">{t("lessons.marking.marksOf", { max: formatScore(a.maxMarks) })}</span>
                    <input
                      className="input"
                      inputMode="decimal"
                      name={`marks-${a.position}`}
                      value={draft.marks}
                      onChange={(e) => setDrafts({ ...drafts, [a.answerId]: { ...draft, marks: e.target.value } })}
                    />
                  </label>
                  <label className="field min-w-0 flex-[1_1_14rem]">
                    <span className="field-label">{t("lessons.marking.feedback")}</span>
                    <input
                      className="input"
                      name={`feedback-${a.position}`}
                      maxLength={LESSONS_LIMITS.feedback}
                      value={draft.feedback}
                      onChange={(e) => setDrafts({ ...drafts, [a.answerId]: { ...draft, feedback: e.target.value } })}
                    />
                  </label>
                </div>
              ) : (
                <p className="mt-2 text-[13px]">
                  <b className="num">{a.marks === null ? "—" : formatScore(a.marks)}</b> / {formatScore(a.maxMarks)}
                  {a.feedback ? ` · ${a.feedback}` : ""}
                </p>
              )}
            </li>
          );
        })}
      </ol>
      {review.canMark ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? t("common.saving") : t("lessons.marking.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}
