"use client";

import { ArrowDown, ArrowLeft, ArrowUp, BarChart3, Dices, Lock, Pencil, Plus, Send, Trash2, Undo2, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatScore, parseDecimal } from "@/components/views/exams/exams-shared";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import {
  DIFFICULTIES,
  LESSONS_LIMITS,
  QUESTION_TYPES,
  type Difficulty,
  type QuestionType,
  type TestDetail,
  type TestQuestionRequest,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { isWholeNumberInRange } from "@/lib/validation";
import { difficultyLabel, LessonsNav, TestStatePill, typeLabel, useLessonsOptions } from "./lessons-shared";
import { QuestionCard } from "./question-bank-view";
import { TestSettingsDialog, testWindow } from "./tests-view";

/** The test's questions as the API takes them, keeping each one's marks in the test. Pure, exported for tests. */
export function questionList(test: TestDetail): TestQuestionRequest[] {
  return test.questions.map((q) => ({ questionId: q.questionId, marks: q.marks }));
}

/** /app/lessons/tests/[testId]: a test's settings and questions, built from the bank, with publishing. */
export function TestBuilderView({ testId }: { testId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const router = useRouter();
  const { toast } = useToast();
  const loaded = useApiData(`lessons:test:${testId}`, () => lessonsApi.test(testId));
  const [current, setCurrent] = useState<TestDetail | null>(null);
  const test = current && current.id === testId ? current : loaded.data;
  const options = useLessonsOptions(test?.yearId ?? "");
  const [dialog, setDialog] = useState<"settings" | "bank" | "random" | "delete" | "close" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<TestDetail>, message?: MessageKey) => {
    setBusy(true);
    setError(null);
    try {
      setCurrent(await action());
      if (message) toast(t(message));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  if (loaded.error && !test) {
    return (
      <>
        <PageHead title={t("lessons.tests.title")} />
        <section className="card">
          <ErrorState error={loaded.error} onRetry={loaded.reload} />
        </section>
      </>
    );
  }
  if (!test) {
    return (
      <>
        <PageHead title={t("lessons.tests.title")} />
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  const draft = test.status === "DRAFT";
  const editable = test.canEdit && draft;
  const saveQuestions = (list: TestQuestionRequest[]) => run(() => lessonsApi.setTestQuestions(test.id, list));

  return (
    <>
      <PageHead
        eyebrow={[test.className, test.subjectName].join(" · ")}
        title={test.title}
        actions={
          <>
            <Link href="/app/lessons/tests" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("lessons.tests.back")}
            </Link>
            {!draft ? (
              <Link href={`/app/lessons/tests/${encodeURIComponent(test.id)}/results`} className="btn btn-primary">
                <BarChart3 size={18} aria-hidden="true" />
                {t("lessons.tests.results")}
              </Link>
            ) : null}
          </>
        }
      />
      <LessonsNav />
      <section className="card flex flex-col gap-3" data-testid="test-settings">
        <div className="flex flex-wrap items-center gap-2">
          <TestStatePill state={test.state} />
          <span className="text-[13px] text-ink-3">
            {test.publishedAt ? t("lessons.tests.publishedOn", { date: formatDateTime(test.publishedAt, locale) }) : t("lessons.tests.draftHint")}
          </span>
        </div>
        <FormAlert message={error} />
        {test.canEdit ? (
          <div className="flex flex-wrap gap-2 no-print">
            {draft ? (
              <>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={busy || test.questions.length === 0}
                  onClick={() => void run(() => lessonsApi.publishTest(test.id), "lessons.tests.publishedDone")}
                >
                  <Send size={16} aria-hidden="true" />
                  {t("lessons.tests.publish")}
                </button>
                <button type="button" className="btn" disabled={busy || !options.data} onClick={() => setDialog("settings")}>
                  <Pencil size={16} aria-hidden="true" />
                  {t("lessons.tests.editSettings")}
                </button>
              </>
            ) : (
              <>
                {test.attempts === 0 ? (
                  <button
                    type="button"
                    className="btn"
                    disabled={busy}
                    onClick={() => void run(() => lessonsApi.unpublishTest(test.id), "lessons.tests.unpublishedDone")}
                  >
                    <Undo2 size={16} aria-hidden="true" />
                    {t("lessons.tests.unpublish")}
                  </button>
                ) : null}
                {test.state !== "CLOSED" ? (
                  <button type="button" className="btn" disabled={busy} onClick={() => setDialog("close")}>
                    <Lock size={16} aria-hidden="true" />
                    {t("lessons.tests.closeNow")}
                  </button>
                ) : null}
              </>
            )}
            {test.attempts === 0 ? (
              <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => setDialog("delete")}>
                <Trash2 size={16} aria-hidden="true" />
                {t("common.delete")}
              </button>
            ) : null}
          </div>
        ) : null}
        <dl className="kv text-[14px]">
          <dt>{t("lessons.tests.sections")}</dt>
          <dd>{test.sections.map((s) => s.label).join(", ")}</dd>
          <dt>{t("lessons.tests.window")}</dt>
          <dd>{testWindow(test.opensAt, test.closesAt, locale)}</dd>
          <dt>{t("lessons.tests.duration")}</dt>
          <dd>{plural(t, "lessons.minutes", test.durationMinutes)}</dd>
          <dt>{t("lessons.tests.attempts")}</dt>
          <dd className="num">{test.attemptsAllowed}</dd>
          <dt>{t("lessons.tests.showAnswers")}</dt>
          <dd>{t(`lessons.tests.showAnswers.${test.showAnswers}`)}</dd>
          <dt>{t("lessons.tests.shuffle")}</dt>
          <dd>
            {[
              test.shuffleQuestions ? t("lessons.tests.shuffleQuestions") : null,
              test.shuffleOptions ? t("lessons.tests.shuffleOptions") : null,
            ]
              .filter(Boolean)
              .join(" · ") || t("lessons.tests.noShuffle")}
          </dd>
        </dl>
        {test.instructions ? <p className="whitespace-pre-line text-[14px] text-ink-2">{test.instructions}</p> : null}
      </section>

      <section className="card flex flex-col gap-3" aria-labelledby="test-questions" data-testid="test-questions">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <div className="min-w-0">
            <h2 id="test-questions">{t("lessons.tests.questions")}</h2>
            <p className="mt-1 text-[13px] text-ink-3">
              {plural(t, "lessons.tests.questionsCount", test.questions.length)} ·{" "}
              {plural(t, "lessons.marksCount", test.totalMarks, { marks: formatScore(test.totalMarks) })}
            </p>
          </div>
          {editable ? (
            <div className="flex flex-wrap justify-end gap-1.5">
              <button type="button" className="btn btn-sm" onClick={() => setDialog("bank")}>
                <Plus size={16} aria-hidden="true" />
                {t("lessons.tests.fromBank")}
              </button>
              <button type="button" className="btn btn-sm" onClick={() => setDialog("random")}>
                <Dices size={16} aria-hidden="true" />
                {t("lessons.tests.random")}
              </button>
            </div>
          ) : null}
        </div>
        {test.questions.length === 0 ? (
          <p className="empty">{editable ? t("lessons.tests.noQuestionsEdit") : t("lessons.tests.noQuestions")}</p>
        ) : (
          <ol className="flex flex-col gap-2.5">
            {test.questions.map((q, i) => (
              <li key={q.id} className="flex flex-col gap-1.5" data-testid="test-question">
                <div className="flex flex-wrap items-center gap-2 text-[13px]">
                  <b>{t("lessons.tests.questionN", { n: i + 1 })}</b>
                  {editable ? (
                    <>
                      <MarksInput
                        value={q.marks}
                        label={t("lessons.tests.marksFor", { n: i + 1 })}
                        onCommit={(marks) =>
                          void saveQuestions(questionList(test).map((x, j) => (j === i ? { ...x, marks } : x)))
                        }
                      />
                      <span className="flex-1" />
                      <button
                        type="button"
                        className="iconbtn"
                        disabled={busy || i === 0}
                        onClick={() => {
                          const list = questionList(test);
                          [list[i - 1], list[i]] = [list[i], list[i - 1]];
                          void saveQuestions(list);
                        }}
                        aria-label={t("lessons.tests.moveUp", { n: i + 1 })}
                      >
                        <ArrowUp size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="iconbtn"
                        disabled={busy || i === test.questions.length - 1}
                        onClick={() => {
                          const list = questionList(test);
                          [list[i + 1], list[i]] = [list[i], list[i + 1]];
                          void saveQuestions(list);
                        }}
                        aria-label={t("lessons.tests.moveDown", { n: i + 1 })}
                      >
                        <ArrowDown size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="iconbtn"
                        disabled={busy}
                        onClick={() => void saveQuestions(questionList(test).filter((_, j) => j !== i))}
                        aria-label={t("lessons.tests.removeQuestion", { n: i + 1 })}
                      >
                        <X size={16} aria-hidden="true" />
                      </button>
                    </>
                  ) : (
                    <span className="text-ink-3">{plural(t, "lessons.marksCount", q.marks, { marks: formatScore(q.marks) })}</span>
                  )}
                </div>
                <ul>
                  <QuestionCard question={q.question} />
                </ul>
              </li>
            ))}
          </ol>
        )}
      </section>

      {dialog === "settings" && options.data ? (
        <TestSettingsDialog
          test={test}
          options={options.data}
          onClose={() => setDialog(null)}
          onSaved={(saved) => {
            setDialog(null);
            setCurrent(saved);
            toast(t("lessons.tests.saved"));
          }}
        />
      ) : null}
      {dialog === "bank" ? (
        <BankPicker
          test={test}
          onClose={() => setDialog(null)}
          onAdd={async (ids) => {
            const updated = await lessonsApi.setTestQuestions(test.id, [
              ...questionList(test),
              ...ids.map((questionId) => ({ questionId, marks: null })),
            ]);
            setCurrent(updated);
            setDialog(null);
            toast(plural(t, "lessons.tests.added", ids.length));
          }}
        />
      ) : null}
      {dialog === "random" ? (
        <RandomDialog
          test={test}
          onClose={() => setDialog(null)}
          onDone={(updated, added) => {
            setCurrent(updated);
            setDialog(null);
            toast(plural(t, "lessons.tests.added", added));
          }}
        />
      ) : null}
      <ConfirmDialog
        open={dialog === "close"}
        title={t("lessons.tests.closeTitle")}
        body={t("lessons.tests.closeBody")}
        confirmLabel={t("lessons.tests.closeNow")}
        onClose={() => setDialog(null)}
        onConfirm={async () => {
          setCurrent(await lessonsApi.closeTest(test.id));
          setDialog(null);
          toast(t("lessons.tests.closedDone"));
        }}
      />
      <ConfirmDialog
        open={dialog === "delete"}
        title={t("lessons.tests.deleteTitle", { title: test.title })}
        body={t("lessons.tests.deleteBody")}
        confirmLabel={t("common.delete")}
        onClose={() => setDialog(null)}
        onConfirm={async () => {
          await lessonsApi.deleteTest(test.id);
          setDialog(null);
          toast(t("common.deleted"));
          router.push("/app/lessons/tests");
        }}
      />
    </>
  );
}

/** A question's marks in the test, saved when the field is left with a changed, valid value. */
function MarksInput({ value, label, onCommit }: { value: number; label: string; onCommit: (marks: number) => void }) {
  const { t } = useI18n();
  const [text, setText] = useState(formatScore(value));
  const parsed = parseDecimal(text);
  const invalid = parsed === null || Number.isNaN(parsed) || parsed < 0.25 || parsed > 100;
  return (
    <label className="inline-flex items-center gap-1.5">
      <input
        className="input w-20"
        inputMode="decimal"
        name="marks"
        value={text}
        aria-label={label}
        aria-invalid={invalid || undefined}
        title={invalid ? t("lessons.v.marks") : undefined}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => {
          if (parsed !== null && !invalid && parsed !== value) onCommit(parsed);
        }}
      />
      <span className="text-ink-3">{t("lessons.marks")}</span>
    </label>
  );
}

/** Picking bank questions of the test's class and subject, with search and filters. */
function BankPicker({
  test,
  onClose,
  onAdd,
}: {
  test: TestDetail;
  onClose: () => void;
  onAdd: (questionIds: string[]) => Promise<void>;
}) {
  const { t } = useI18n();
  const [type, setType] = useState<QuestionType | "">("");
  const [difficulty, setDifficulty] = useState<Difficulty | "">("");
  const [topicId, setTopicId] = useState("");
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [chosen, setChosen] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const topics = useApiData(`lessons:topics:${test.yearId}:${test.classId}:${test.subjectId}`, () =>
    lessonsApi.topics(test.classId, test.subjectId, test.yearId),
  );
  const query = {
    classId: test.classId,
    subjectId: test.subjectId,
    topicId: topicId || undefined,
    type: type || undefined,
    difficulty: difficulty || undefined,
    q: q || undefined,
  };
  const bank = useApiData(`lessons:questions:${JSON.stringify(query)}`, () => lessonsApi.questions(query));
  const inTest = new Set(test.questions.map((x) => x.questionId));
  const available = (bank.data ?? []).filter((x) => !inTest.has(x.id));
  const room = LESSONS_LIMITS.testQuestions - test.questions.length;

  const add = async () => {
    setBusy(true);
    setError(null);
    try {
      await onAdd(chosen);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} title={t("lessons.tests.fromBank")} description={t("lessons.tests.fromBankHelp", { name: `${test.className} · ${test.subjectName}` })} closeLabel={t("common.close")}>
      <div className="flex flex-col gap-3" data-testid="bank-picker">
        <form
          method="post"
          className="flex flex-wrap gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            setQ(search.trim());
          }}
        >
          <input
            className="input min-w-0 flex-[1_1_12rem]"
            type="search"
            name="q"
            value={search}
            placeholder={t("lessons.questions.searchPlaceholder")}
            aria-label={t("lessons.questions.search")}
            onChange={(e) => setSearch(e.target.value)}
          />
          <select className="input w-auto" name="topicId" value={topicId} aria-label={t("lessons.topic")} onChange={(e) => setTopicId(e.target.value)}>
            <option value="">{t("lessons.allTopics")}</option>
            {(topics.data ?? []).map((x) => (
              <option key={x.id} value={x.id}>
                {x.title}
              </option>
            ))}
          </select>
          <select className="input w-auto" name="type" value={type} aria-label={t("lessons.questions.type")} onChange={(e) => setType(e.target.value as QuestionType | "")}>
            <option value="">{t("lessons.questions.anyType")}</option>
            {QUESTION_TYPES.map((x) => (
              <option key={x} value={x}>
                {typeLabel(t, x)}
              </option>
            ))}
          </select>
          <select
            className="input w-auto"
            name="difficulty"
            value={difficulty}
            aria-label={t("lessons.questions.difficulty")}
            onChange={(e) => setDifficulty(e.target.value as Difficulty | "")}
          >
            <option value="">{t("lessons.questions.anyDifficulty")}</option>
            {DIFFICULTIES.map((x) => (
              <option key={x} value={x}>
                {difficultyLabel(t, x)}
              </option>
            ))}
          </select>
          <button type="submit" className="btn btn-sm">
            {t("lessons.questions.searchRun")}
          </button>
        </form>
        <FormAlert message={error} />
        {bank.error && !bank.data ? (
          <ErrorState error={bank.error} onRetry={bank.reload} />
        ) : !bank.data ? (
          <LoadingRows rows={3} />
        ) : available.length === 0 ? (
          <p className="empty">{t("lessons.tests.bankEmpty")}</p>
        ) : (
          <ul className="flex max-h-[50vh] flex-col gap-2 overflow-y-auto">
            {available.map((question) => (
              <QuestionCard
                key={question.id}
                question={question}
                action={
                  <label className="check py-1">
                    <input
                      type="checkbox"
                      name="questionIds"
                      checked={chosen.includes(question.id)}
                      disabled={!chosen.includes(question.id) && chosen.length >= room}
                      onChange={(e) =>
                        setChosen(e.target.checked ? [...chosen, question.id] : chosen.filter((x) => x !== question.id))
                      }
                      aria-label={t("lessons.tests.pickQuestion", { text: question.text.slice(0, 60) })}
                    />
                    <span>{t("lessons.tests.pick")}</span>
                  </label>
                }
              />
            ))}
          </ul>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="button" className="btn btn-primary" disabled={chosen.length === 0 || busy} onClick={() => void add()}>
            {busy ? t("common.working") : plural(t, "lessons.tests.addChosen", chosen.length)}
          </button>
        </div>
      </div>
    </Dialog>
  );
}

/** Adding a number of random bank questions, optionally of one topic, difficulty or type. */
function RandomDialog({
  test,
  onClose,
  onDone,
}: {
  test: TestDetail;
  onClose: () => void;
  onDone: (test: TestDetail, added: number) => void;
}) {
  const { t } = useI18n();
  const topics = useApiData(`lessons:topics:${test.yearId}:${test.classId}:${test.subjectId}`, () =>
    lessonsApi.topics(test.classId, test.subjectId, test.yearId),
  );
  const form = useForm({ count: "5", topicId: "", difficulty: "", type: "" });
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(isWholeNumberInRange(values.count, 1, LESSONS_LIMITS.randomCount) ? {} : { count: "lessons.v.count" }, t, event.currentTarget)) {
      return;
    }
    let updated: TestDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = await lessonsApi.addRandomQuestions(test.id, {
        count: Number(values.count),
        topicId: values.topicId || null,
        difficulty: (values.difficulty || null) as Difficulty | null,
        type: (values.type || null) as QuestionType | null,
      });
    });
    if (ok && updated) onDone(updated, updated.questions.length - test.questions.length);
  };
  return (
    <Dialog open onClose={onClose} title={t("lessons.tests.random")} description={t("lessons.tests.randomHelp")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("lessons.tests.randomCount")}
          name="count"
          type="number"
          inputMode="numeric"
          min={1}
          max={LESSONS_LIMITS.randomCount}
          value={values.count}
          onChange={(e) => set("count", e.target.value)}
          error={errors.count}
          required
          data-autofocus
        />
        <SelectField
          label={t("lessons.topic")}
          name="topicId"
          value={values.topicId}
          onChange={(e) => set("topicId", e.target.value)}
          options={[{ value: "", label: t("lessons.allTopics") }, ...(topics.data ?? []).map((x) => ({ value: x.id, label: x.title }))]}
        />
        <div className="grid2">
          <SelectField
            label={t("lessons.questions.difficulty")}
            name="difficulty"
            value={values.difficulty}
            onChange={(e) => set("difficulty", e.target.value)}
            options={[{ value: "", label: t("lessons.questions.anyDifficulty") }, ...DIFFICULTIES.map((x) => ({ value: x, label: difficultyLabel(t, x) }))]}
          />
          <SelectField
            label={t("lessons.questions.type")}
            name="type"
            value={values.type}
            onChange={(e) => set("type", e.target.value)}
            options={[{ value: "", label: t("lessons.questions.anyType") }, ...QUESTION_TYPES.map((x) => ({ value: x, label: typeLabel(t, x) }))]}
          />
        </div>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("lessons.tests.randomRun")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
