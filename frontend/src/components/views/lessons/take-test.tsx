"use client";

import { ArrowLeft, Check, ChevronLeft, ChevronRight, Clock, Send, X } from "lucide-react";
import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { formatPct, formatScore } from "@/components/views/exams/exams-shared";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { LESSONS_LIMITS, type AnswerRequest, type LearnerAttempt, type LearnerQuestion } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { studyTestStatus } from "./family-study";
import { TestStatePill } from "./lessons-shared";

/** How long typing pauses before a written answer is saved. */
export const AUTOSAVE_DELAY_MS = 800;

/** 754 → "12:34"; 3725 → "1:02:05"; never below 0:00. Pure, exported for tests. */
export function clockText(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const rest = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${rest}` : `${m}:${rest}`;
}

type Draft = { optionIds: string[]; text: string };

/** Whether a question has an answer in the draft. Pure, exported for tests. */
export function isAnswered(draft: Draft | undefined): boolean {
  return Boolean(draft && (draft.optionIds.length > 0 || draft.text.trim() !== ""));
}

function draftsOf(attempt: LearnerAttempt): Record<string, Draft> {
  return Object.fromEntries(
    attempt.questions.map((q) => [q.answerId, { optionIds: q.selectedOptionIds, text: q.response ?? "" }]),
  );
}

type Run = { attempt: LearnerAttempt; deadline: number };

/** The run of an attempt with its deadline on this device's clock, from the server's seconds left. */
function runOf(attempt: LearnerAttempt): Run {
  return { attempt, deadline: Date.now() + attempt.secondsLeft * 1000 };
}

/**
 * /app/family/study/tests/[testId]: a student's online test. Before starting: what it is and the attempts so far;
 * while it runs: one question per screen with progress, the server's clock, autosave and submit; afterwards: the
 * answers are in, and once the teacher releases them the score (and the answers, if shown).
 */
export function TakeTestView({ testId }: { testId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const intro = useApiData(`lessons:intro:${testId}`, () => lessonsApi.myTest(testId));
  const [run, setRun] = useState<Run | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const open = async (action: () => Promise<LearnerAttempt>) => {
    setBusy(true);
    setError(null);
    try {
      setRun(runOf(await action()));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const back = (
    <Link href="/app/family/study" className="btn">
      <ArrowLeft size={18} aria-hidden="true" />
      {t("lessons.take.back")}
    </Link>
  );

  if (run && !run.attempt.submitted) {
    return (
      <AttemptRunner
        run={run}
        onUpdate={(attempt, resync) =>
          setRun((prev) => (resync || !prev ? runOf(attempt) : { attempt, deadline: prev.deadline }))
        }
      />
    );
  }
  if (run) {
    return (
      <>
        <PageHead eyebrow={run.attempt.subjectName} title={run.attempt.testTitle} actions={back} />
        <AttemptDone
          attempt={run.attempt}
          onBack={() => {
            setRun(null);
            intro.reload();
          }}
        />
      </>
    );
  }
  if (intro.error && !intro.data) {
    return (
      <>
        <PageHead title={t("lessons.take.title")} actions={back} />
        <section className="card">
          {intro.error.status === 404 ? <p className="empty">{t("lessons.take.notFound")}</p> : <ErrorState error={intro.error} onRetry={intro.reload} />}
        </section>
      </>
    );
  }
  if (!intro.data) {
    return (
      <>
        <PageHead title={t("lessons.take.title")} actions={back} />
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      </>
    );
  }
  const { test, instructions, attempts } = intro.data;
  return (
    <>
      <PageHead eyebrow={test.subjectName} title={test.title} actions={back} />
      <section className="card flex flex-col gap-3" data-testid="test-intro">
        <div className="flex flex-wrap items-center gap-2">
          <TestStatePill state={test.state} />
          <span className="text-[13.5px] font-semibold">{studyTestStatus(t, test)}</span>
        </div>
        <dl className="kv text-[14px]">
          <dt>{t("lessons.tests.window")}</dt>
          <dd>
            {formatDateTime(test.opensAt, locale)} – {formatDateTime(test.closesAt, locale)}
          </dd>
          <dt>{t("lessons.tests.duration")}</dt>
          <dd>{plural(t, "lessons.minutes", test.durationMinutes)}</dd>
          <dt>{t("lessons.tests.questions")}</dt>
          <dd>
            {plural(t, "lessons.tests.questionsCount", test.questions)} ·{" "}
            {plural(t, "lessons.marksCount", test.totalMarks, { marks: formatScore(test.totalMarks) })}
          </dd>
          <dt>{t("lessons.tests.attempts")}</dt>
          <dd className="num">{test.attemptsAllowed}</dd>
        </dl>
        {instructions ? <p className="whitespace-pre-line text-[14px] text-ink-2">{instructions}</p> : null}
        <FormAlert message={error} />
        {test.inProgressAttemptId ? (
          <button
            type="button"
            className="btn btn-primary btn-lg w-full sm:w-auto"
            disabled={busy}
            onClick={() => void open(() => lessonsApi.myAttempt(test.inProgressAttemptId!))}
          >
            {t("lessons.take.continue")}
          </button>
        ) : test.canStart ? (
          <>
            <p className="alert alert-info">{t("lessons.take.startHint", { minutes: test.durationMinutes })}</p>
            <button
              type="button"
              className="btn btn-primary btn-lg w-full sm:w-auto"
              disabled={busy}
              onClick={() => void open(() => lessonsApi.startAttempt(test.id))}
              data-testid="start-test"
            >
              {attempts.length > 0 ? t("lessons.take.again") : t("lessons.take.start")}
            </button>
          </>
        ) : null}
        {attempts.length > 0 ? (
          <div>
            <h2 className="text-base">{t("lessons.take.yourAttempts")}</h2>
            <ul className="rowcards mt-1">
              {attempts.map((a) => (
                <li key={a.id} className="rowcard">
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">{t("lessons.marking.attemptNo", { n: a.attemptNo })}</b>
                    <span className="block text-[12.5px] text-ink-3">
                      {a.submittedAt
                        ? t(a.autoSubmitted ? "lessons.marking.autoSubmitted" : "lessons.marking.submitted", {
                            date: formatDateTime(a.submittedAt, locale),
                          })
                        : t("lessons.marking.inProgress")}
                    </span>
                  </span>
                  {a.score !== null ? (
                    <b className="num">
                      {formatScore(a.score)} / {formatScore(a.maxScore)}
                    </b>
                  ) : null}
                  {a.submittedAt && test.released ? (
                    <button type="button" className="btn btn-sm" disabled={busy} onClick={() => void open(() => lessonsApi.myAttempt(a.id))}>
                      {t("lessons.take.review")}
                    </button>
                  ) : null}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </section>
    </>
  );
}

/** One question per screen, with progress, the clock, autosave and submitting. */
export function AttemptRunner({
  run,
  onUpdate,
}: {
  run: Run;
  /** a newer copy of the attempt; `resync` resets the clock from the server's seconds left */
  onUpdate: (attempt: LearnerAttempt, resync: boolean) => void;
}) {
  const { t } = useI18n();
  const groupName = useId();
  const { attempt } = run;
  const [index, setIndex] = useState(0);
  const [drafts, setDrafts] = useState<Record<string, Draft>>(() => draftsOf(attempt));
  const [now, setNow] = useState(() => Date.now());
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const timer = useRef<number | null>(null);
  const pending = useRef<{ answerId: string; body: AnswerRequest } | null>(null);
  const finishing = useRef(false);
  const total = attempt.questions.length;
  const question: LearnerQuestion | undefined = attempt.questions[index];
  const remaining = Math.max(0, Math.ceil((run.deadline - now) / 1000));
  const answered = attempt.questions.filter((q) => isAnswered(drafts[q.answerId])).length;

  /** After a 409 (time is up, or the attempt was submitted elsewhere): shows where the attempt stands. */
  const refresh = async () => {
    try {
      onUpdate(await lessonsApi.myAttempt(attempt.id), true);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    }
  };

  const send = async (answerId: string, body: AnswerRequest) => {
    setSaveState("saving");
    setError(null);
    try {
      onUpdate(await lessonsApi.saveAnswer(attempt.id, answerId, body), true);
      setSaveState("saved");
    } catch (caught) {
      const failure = toApiError(caught);
      if (failure.status === 409) {
        await refresh();
        return;
      }
      setSaveState("error");
      setError(t("lessons.take.saveFailed", { reason: errorMessage(failure, t) }));
    }
  };

  /** Saves a written answer that is still waiting for typing to pause. */
  const flush = async () => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
    const waiting = pending.current;
    pending.current = null;
    if (waiting) await send(waiting.answerId, waiting.body);
  };

  const finish = async () => {
    if (finishing.current) return;
    finishing.current = true;
    setSubmitting(true);
    setError(null);
    try {
      await flush();
      onUpdate(await lessonsApi.submitAttempt(attempt.id), true);
    } catch (caught) {
      const failure = toApiError(caught);
      if (failure.status === 409) {
        await refresh();
      } else {
        setError(errorMessage(failure, t));
        finishing.current = false;
      }
    } finally {
      setSubmitting(false);
      setConfirming(false);
    }
  };

  // The clock: ticks every second; when it reaches zero the saved answers are submitted.
  useEffect(() => {
    const id = window.setInterval(() => {
      const at = Date.now();
      setNow(at);
      if (at >= run.deadline) {
        window.clearInterval(id);
        void finish();
      }
    }, 1000);
    return () => window.clearInterval(id);
    // finish reads refs and the attempt id only; restarting on each render would reset the tick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run.deadline, attempt.id]);

  const choose = (q: LearnerQuestion, optionIds: string[]) => {
    setDrafts((prev) => ({ ...prev, [q.answerId]: { optionIds, text: "" } }));
    void send(q.answerId, { optionIds, text: null });
  };

  const choiceText = (q: LearnerQuestion, text: string) => {
    setDrafts((prev) => ({ ...prev, [q.answerId]: { optionIds: [], text } }));
    void send(q.answerId, { optionIds: null, text });
  };

  const write = (q: LearnerQuestion, text: string) => {
    setDrafts((prev) => ({ ...prev, [q.answerId]: { optionIds: [], text } }));
    pending.current = { answerId: q.answerId, body: { optionIds: null, text } };
    if (timer.current !== null) window.clearTimeout(timer.current);
    setSaveState("idle");
    timer.current = window.setTimeout(() => {
      timer.current = null;
      const waiting = pending.current;
      pending.current = null;
      if (waiting) void send(waiting.answerId, waiting.body);
    }, AUTOSAVE_DELAY_MS);
  };

  const go = (next: number) => {
    void flush();
    setIndex(Math.max(0, Math.min(total - 1, next)));
  };

  if (!question) return null;
  const draft = drafts[question.answerId] ?? { optionIds: [], text: "" };
  const low = remaining <= 60;

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-3" data-testid="attempt-runner">
      <div className="card sticky top-2 z-10 flex flex-col gap-2 py-3" data-testid="attempt-status">
        <div className="flex items-center gap-2">
          <b className="min-w-0 flex-1 truncate text-[14px]">{attempt.testTitle}</b>
          <span
            className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 font-semibold num ${low ? "bg-bad-soft text-bad" : "bg-surface-2"}`}
            role="timer"
            aria-live={low ? "polite" : "off"}
            aria-label={t("lessons.take.timeLeft", { time: clockText(remaining) })}
            data-testid="attempt-timer"
          >
            <Clock size={15} aria-hidden="true" />
            {clockText(remaining)}
          </span>
        </div>
        <div className="flex items-center gap-2 text-[12.5px] text-ink-3">
          <span data-testid="attempt-progress">{t("lessons.take.progress", { n: index + 1, total })}</span>
          <span>·</span>
          <span>{t("lessons.take.answered", { answered, total })}</span>
          <span className="flex-1" />
          <span aria-live="polite" data-testid="save-state">
            {saveState === "saving" ? t("lessons.take.saving") : saveState === "saved" ? t("lessons.take.saved") : ""}
          </span>
        </div>
        <span className="meter" aria-hidden="true">
          <span style={{ width: `${Math.round((answered / Math.max(1, total)) * 100)}%` }} />
        </span>
      </div>

      <FormAlert message={error} />

      <form
        method="post"
        className="card flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (index < total - 1) go(index + 1);
          else setConfirming(true);
        }}
        noValidate
        data-testid="attempt-question"
      >
        <fieldset className="flex flex-col gap-2.5 border-0 p-0" style={{ margin: 0 }}>
          <legend className="mb-1 flex w-full flex-col gap-1">
            <span className="text-[12.5px] text-ink-3">
              {t("lessons.tests.questionN", { n: index + 1 })} ·{" "}
              {plural(t, "lessons.marksCount", question.marks, { marks: formatScore(question.marks) })} ·{" "}
              {question.type === "MCQ_MULTI" ? t("lessons.take.pickAll") : translateOr(t, `lessons.type.${question.type}`, question.type)}
            </span>
            <span className="whitespace-pre-line text-[16px] font-semibold leading-snug">{question.text}</span>
          </legend>
          {question.type === "MCQ_SINGLE" || question.type === "MCQ_MULTI" ? (
            question.options.map((o) => {
              const multi = question.type === "MCQ_MULTI";
              const checked = draft.optionIds.includes(o.id);
              return (
                <label key={o.id} className="check min-h-11 text-[15px]">
                  <input
                    type={multi ? "checkbox" : "radio"}
                    name={`${groupName}-${question.answerId}`}
                    value={o.id}
                    checked={checked}
                    onChange={(e) =>
                      choose(
                        question,
                        multi
                          ? e.target.checked
                            ? question.options.filter((x) => x.id === o.id || draft.optionIds.includes(x.id)).map((x) => x.id)
                            : draft.optionIds.filter((x) => x !== o.id)
                          : [o.id],
                      )
                    }
                  />
                  <span>{o.text}</span>
                </label>
              )
            })
          ) : question.type === "TRUE_FALSE" ? (
            (["TRUE", "FALSE"] as const).map((v) => (
              <label key={v} className="check min-h-11 text-[15px]">
                <input
                  type="radio"
                  name={`${groupName}-${question.answerId}`}
                  value={v}
                  checked={draft.text === v}
                  onChange={() => choiceText(question, v)}
                />
                <span>{t(`lessons.tf.${v}`)}</span>
              </label>
            ))
          ) : question.type === "FILL_BLANK" ? (
            <input
              className="input text-[15px]"
              name="answer"
              value={draft.text}
              maxLength={LESSONS_LIMITS.answer}
              autoComplete="off"
              aria-label={t("lessons.take.yourAnswer")}
              placeholder={t("lessons.take.typeAnswer")}
              onChange={(e) => write(question, e.target.value)}
              onBlur={() => void flush()}
            />
          ) : (
            <textarea
              className="input text-[15px]"
              name="answer"
              rows={5}
              value={draft.text}
              maxLength={LESSONS_LIMITS.answer}
              aria-label={t("lessons.take.yourAnswer")}
              placeholder={t("lessons.take.typeAnswer")}
              onChange={(e) => write(question, e.target.value)}
              onBlur={() => void flush()}
            />
          )}
        </fieldset>
        <div className="flex gap-2 pt-1">
          <button type="button" className="btn flex-1 sm:flex-none" disabled={index === 0} onClick={() => go(index - 1)}>
            <ChevronLeft size={18} aria-hidden="true" />
            {t("common.previous")}
          </button>
          <span className="hidden flex-1 sm:block" />
          {index < total - 1 ? (
            <button type="submit" className="btn btn-primary flex-1 sm:flex-none">
              {t("common.next")}
              <ChevronRight size={18} aria-hidden="true" />
            </button>
          ) : (
            <button type="submit" className="btn btn-primary flex-1 sm:flex-none" disabled={submitting} data-testid="submit-test">
              <Send size={16} aria-hidden="true" />
              {t("lessons.take.submit")}
            </button>
          )}
        </div>
      </form>

      <nav className="card flex flex-wrap gap-1.5 py-3" aria-label={t("lessons.take.jump")}>
        {attempt.questions.map((q, i) => {
          const done = isAnswered(drafts[q.answerId]);
          return (
            <button
              key={q.answerId}
              type="button"
              className={`h-9 w-9 rounded-md border text-[13px] font-semibold num ${
                i === index ? "border-accent bg-accent text-accent-ink" : done ? "border-accent bg-accent-soft" : "border-line bg-surface"
              }`}
              aria-current={i === index ? "step" : undefined}
              aria-label={t(done ? "lessons.take.jumpAnswered" : "lessons.take.jumpOpen", { n: i + 1 })}
              onClick={() => go(i)}
            >
              {i + 1}
            </button>
          );
        })}
      </nav>

      <ConfirmDialog
        open={confirming}
        danger={false}
        title={t("lessons.take.submitTitle")}
        body={
          answered < total
            ? plural(t, "lessons.take.submitUnanswered", total - answered)
            : t("lessons.take.submitBody")
        }
        confirmLabel={t("lessons.take.submit")}
        onClose={() => setConfirming(false)}
        onConfirm={finish}
      />
    </div>
  );
}

/** After submitting: the answers are in; once released, the score and (if shown) the answers. */
export function AttemptDone({ attempt, onBack }: { attempt: LearnerAttempt; onBack: () => void }) {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-3" data-testid="attempt-done">
      {attempt.released && attempt.score !== null ? (
        <div className="flex flex-wrap items-center gap-3">
          <b className="font-display text-[28px] leading-none num" data-testid="attempt-score">
            {formatScore(attempt.score)} / {formatScore(attempt.maxScore)}
          </b>
          <span className="text-ink-2">
            {attempt.maxScore ? formatPct((attempt.score / attempt.maxScore) * 100) : ""}
          </span>
        </div>
      ) : (
        <div className="alert alert-good" role="status">
          <Check size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>
            {attempt.autoSubmitted ? t("lessons.take.timeUp") : t("lessons.take.submittedDone")}{" "}
            {t("lessons.take.waitForResults")}
          </span>
        </div>
      )}
      <p className="text-[13px] text-ink-3">{t("lessons.take.answered", { answered: attempt.answered, total: attempt.total })}</p>
      {attempt.review.length > 0 ? (
        <ol className="flex flex-col gap-2.5" data-testid="attempt-review">
          {attempt.review.map((item) => (
            <li key={item.position} className="rounded-[10px] border border-line p-3">
              <p className="text-[12.5px] text-ink-3">
                {t("lessons.tests.questionN", { n: item.position })} ·{" "}
                {item.marks === null ? "—" : formatScore(item.marks)} / {formatScore(item.maxMarks)}
              </p>
              <p className="mt-0.5 whitespace-pre-line font-semibold">{item.text}</p>
              {item.options.length > 0 ? (
                <ul className="mt-1 flex flex-col gap-0.5 text-[13.5px]">
                  {item.options.map((o) => {
                    const picked = item.selectedOptionIds.includes(o.id);
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
                          {picked ? ` (${t("lessons.take.yourChoice")})` : ""}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <p className="mt-1 text-[13.5px]">
                  <b>{t("lessons.take.yourAnswer")}:</b>{" "}
                  {item.response ? (item.type === "TRUE_FALSE" ? translateOr(t, `lessons.tf.${item.response}`, item.response) : item.response) : "—"}
                </p>
              )}
              {item.correctAnswer && item.options.length === 0 ? (
                <p className="mt-0.5 text-[13.5px] text-good">
                  <b>{t("lessons.take.correctAnswer")}:</b>{" "}
                  {item.type === "TRUE_FALSE" ? translateOr(t, `lessons.tf.${item.correctAnswer}`, item.correctAnswer) : item.correctAnswer.split("\n").join(" | ")}
                </p>
              ) : null}
              {item.feedback ? <p className="mt-0.5 text-[13px] text-ink-2">{t("lessons.take.feedback", { feedback: item.feedback })}</p> : null}
              {item.explanation ? <p className="mt-0.5 text-[12.5px] text-ink-3">{item.explanation}</p> : null}
            </li>
          ))}
        </ol>
      ) : attempt.released ? (
        <Pill>{t("lessons.take.answersHidden")}</Pill>
      ) : null}
      <button type="button" className="btn self-start" onClick={onBack}>
        {t("lessons.take.done")}
      </button>
    </section>
  );
}
