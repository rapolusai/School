"use client";

import { Check, FileUp, Lock, Pencil, Plus, Search, Trash2, X } from "lucide-react";
import { useId, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatScore, parseDecimal } from "@/components/views/exams/exams-shared";
import { plural, translateOr, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import {
  BLOOM_LEVELS,
  DIFFICULTIES,
  LESSONS_LIMITS,
  QUESTION_TYPES,
  type BloomLevel,
  type Difficulty,
  type LessonsOptions,
  type QuestionPreviewRow,
  type QuestionQuery,
  type QuestionRequest,
  type QuestionType,
  type QuestionView,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { CsvImportDialog } from "./csv-import-dialog";
import { difficultyLabel, LessonsNav, typeLabel, useLessonsOptions } from "./lessons-shared";

const DIFFICULTY_TONES = { EASY: "good", MEDIUM: "info", HARD: "warn" } as const;

export type OptionValue = { text: string; correct: boolean };

export type QuestionValues = {
  classId: string;
  subjectId: string;
  topicId: string;
  type: QuestionType;
  difficulty: Difficulty;
  bloomLevel: BloomLevel | "";
  text: string;
  answerText: string;
  explanation: string;
  marks: string;
  shared: boolean;
  options: OptionValue[];
};

const hasOptions = (type: QuestionType) => type === "MCQ_SINGLE" || type === "MCQ_MULTI";

/** Client checks matching the API's (docs/api/phase-2-lessons.md). Pure, exported for tests. */
export function validateQuestion(values: QuestionValues): Problems {
  const problems: Problems = {};
  if (!values.classId) problems.classId = "lessons.v.class";
  if (!values.subjectId) problems.subjectId = "lessons.v.subject";
  if (!values.text.trim()) problems.text = "lessons.v.question";
  else if (values.text.trim().length > LESSONS_LIMITS.questionText) problems.text = "lessons.v.long2000";
  const marks = parseDecimal(values.marks);
  if (marks === null || Number.isNaN(marks) || marks < 0.25 || marks > 100) problems.marks = "lessons.v.marks";
  if (values.explanation.trim().length > LESSONS_LIMITS.explanation) problems.explanation = "lessons.v.long2000";
  if (hasOptions(values.type)) {
    const filled = values.options.map((o) => o.text.trim());
    const correct = values.options.filter((o) => o.correct).length;
    if (values.options.length < 2 || values.options.length > LESSONS_LIMITS.options) problems.options = "lessons.v.optionCount";
    else if (filled.some((text) => !text)) problems.options = "lessons.v.optionEmpty";
    else if (filled.some((text) => text.length > LESSONS_LIMITS.option)) problems.options = "lessons.v.optionLong";
    else if (new Set(filled.map((text) => text.toLowerCase())).size !== filled.length) problems.options = "lessons.v.optionTwice";
    else if (values.type === "MCQ_SINGLE" && correct !== 1) problems.options = "lessons.v.oneCorrect";
    else if (values.type === "MCQ_MULTI" && correct < 1) problems.options = "lessons.v.someCorrect";
  } else if (values.type === "TRUE_FALSE") {
    if (values.answerText !== "TRUE" && values.answerText !== "FALSE") problems.answerText = "lessons.v.trueFalse";
  } else if (values.type === "FILL_BLANK") {
    if (!values.answerText.trim()) problems.answerText = "lessons.v.accepted";
  }
  if (!problems.answerText && values.answerText.trim().length > LESSONS_LIMITS.answerText) problems.answerText = "lessons.v.long1000";
  return problems;
}

/** The request body for a question's values. Pure, exported for tests. */
export function questionRequest(values: QuestionValues): QuestionRequest {
  const withOptions = hasOptions(values.type);
  return {
    classId: values.classId,
    subjectId: values.subjectId,
    topicId: values.topicId || null,
    type: values.type,
    difficulty: values.difficulty,
    bloomLevel: values.bloomLevel || null,
    text: values.text.trim(),
    answerText: withOptions ? null : values.answerText.trim() || null,
    explanation: values.explanation.trim() || null,
    marks: Number(values.marks),
    shared: values.shared,
    options: withOptions ? values.options.map((o) => ({ text: o.text.trim(), correct: o.correct })) : null,
  };
}

const BLANK_OPTIONS: OptionValue[] = [
  { text: "", correct: false },
  { text: "", correct: false },
  { text: "", correct: false },
  { text: "", correct: false },
];

/** /app/lessons/questions: the school's question bank with search, filters, adding, editing and CSV import. */
export function QuestionBankView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const options = useLessonsOptions();
  const [classId, setClassId] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [type, setType] = useState<QuestionType | "">("");
  const [difficulty, setDifficulty] = useState<Difficulty | "">("");
  const [mine, setMine] = useState(false);
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [editing, setEditing] = useState<QuestionView | "new" | null>(null);
  const [dialogKey, setDialogKey] = useState(0);
  const [importing, setImporting] = useState(false);
  const [deleting, setDeleting] = useState<QuestionView | null>(null);
  const query: QuestionQuery = {
    classId: classId || undefined,
    subjectId: subjectId || undefined,
    type: type || undefined,
    difficulty: difficulty || undefined,
    q: q || undefined,
    mine,
  };
  const list = useApiData(`lessons:questions:${JSON.stringify(query)}`, () => lessonsApi.questions(query));
  const classes = options.data?.classes ?? [];
  const subjects = classes.find((c) => c.id === classId)?.subjects ?? [];

  const open = (value: QuestionView | "new") => {
    setDialogKey((k) => k + 1);
    setEditing(value);
  };

  return (
    <>
      <PageHead
        title={t("lessons.questions.title")}
        actions={
          <>
            <button type="button" className="btn" onClick={() => setImporting(true)}>
              <FileUp size={18} aria-hidden="true" />
              {t("lessons.questions.import")}
            </button>
            <button type="button" className="btn btn-primary" disabled={!options.data} onClick={() => open("new")}>
              <Plus size={18} aria-hidden="true" />
              {t("lessons.questions.add")}
            </button>
          </>
        }
      />
      <LessonsNav />
      <section className="card flex flex-col gap-3">
        <form
          method="post"
          className="toolbar"
          aria-label={t("lessons.filters")}
          onSubmit={(e) => {
            e.preventDefault();
            setQ(search.trim());
          }}
        >
          <label className="field w-full sm:w-auto sm:min-w-56 sm:flex-1">
            <span className="field-label">{t("lessons.questions.search")}</span>
            <span className="flex gap-1.5">
              <input
                className="input"
                type="search"
                name="q"
                value={search}
                maxLength={100}
                placeholder={t("lessons.questions.searchPlaceholder")}
                onChange={(e) => setSearch(e.target.value)}
              />
              <button type="submit" className="iconbtn" aria-label={t("lessons.questions.searchRun")}>
                <Search size={18} aria-hidden="true" />
              </button>
            </span>
          </label>
          <label className="field w-[calc(50%-4px)] sm:w-auto sm:min-w-36">
            <span className="field-label">{t("lessons.class")}</span>
            <select
              className="input"
              name="classId"
              value={classId}
              onChange={(e) => {
                setClassId(e.target.value);
                setSubjectId("");
              }}
            >
              <option value="">{t("lessons.allClasses")}</option>
              {classes.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-[calc(50%-4px)] sm:w-auto sm:min-w-36">
            <span className="field-label">{t("lessons.subject")}</span>
            <select className="input" name="subjectId" value={subjectId} disabled={!classId} onChange={(e) => setSubjectId(e.target.value)}>
              <option value="">{t("lessons.allSubjects")}</option>
              {subjects.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-[calc(50%-4px)] sm:w-auto sm:min-w-36">
            <span className="field-label">{t("lessons.questions.type")}</span>
            <select className="input" name="type" value={type} onChange={(e) => setType(e.target.value as QuestionType | "")}>
              <option value="">{t("lessons.questions.anyType")}</option>
              {QUESTION_TYPES.map((x) => (
                <option key={x} value={x}>
                  {typeLabel(t, x)}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-[calc(50%-4px)] sm:w-auto sm:min-w-32">
            <span className="field-label">{t("lessons.questions.difficulty")}</span>
            <select
              className="input"
              name="difficulty"
              value={difficulty}
              onChange={(e) => setDifficulty(e.target.value as Difficulty | "")}
            >
              <option value="">{t("lessons.questions.anyDifficulty")}</option>
              {DIFFICULTIES.map((x) => (
                <option key={x} value={x}>
                  {difficultyLabel(t, x)}
                </option>
              ))}
            </select>
          </label>
          <label className="check self-end">
            <input type="checkbox" name="mine" checked={mine} onChange={(e) => setMine(e.target.checked)} />
            <span>{t("lessons.questions.mine")}</span>
          </label>
        </form>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={5} />
        ) : list.data.length === 0 ? (
          <p className="empty">{q || classId || type || difficulty || mine ? t("lessons.questions.noMatch") : t("lessons.questions.empty")}</p>
        ) : (
          <>
            <p className="text-[13px] text-ink-3">{plural(t, "lessons.questions.count", list.data.length)}</p>
            <ul className="flex flex-col gap-2.5" data-testid="question-list">
              {list.data.map((question) => (
                <QuestionCard
                  key={question.id}
                  question={question}
                  onEdit={question.canEdit && !question.locked ? () => open(question) : undefined}
                  onDelete={question.canEdit && !question.locked ? () => setDeleting(question) : undefined}
                />
              ))}
            </ul>
          </>
        )}
      </section>

      {editing && options.data ? (
        <QuestionDialog
          key={dialogKey}
          question={editing === "new" ? null : editing}
          options={options.data}
          defaults={{ classId, subjectId }}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("lessons.questions.saved"));
            list.reload();
          }}
        />
      ) : null}
      {importing ? (
        <CsvImportDialog<QuestionPreviewRow>
          title={t("lessons.questions.import")}
          description={t("lessons.questions.importHelp")}
          columns="class, subject, type, difficulty, marks, question, option_1 … option_6, answer, explanation, topic, bloom, shared"
          check={(csv) => lessonsApi.importQuestions(csv, true)}
          commit={(csv) => lessonsApi.importQuestions(csv, false)}
          previewHead={[t("lessons.class"), t("lessons.subject"), t("lessons.questions.type"), t("lessons.questions.marks"), t("lessons.questions.text")]}
          previewCells={(row) => [row.className, row.subjectName, typeLabel(t, row.type), formatScore(row.marks), row.text]}
          onClose={() => setImporting(false)}
          onImported={(result) => {
            setImporting(false);
            toast(plural(t, "lessons.questions.imported", result.validRows));
            list.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting !== null}
        title={t("lessons.questions.deleteTitle")}
        body={deleting?.text ?? ""}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          await lessonsApi.deleteQuestion(deleting.id);
          setDeleting(null);
          toast(t("common.deleted"));
          list.reload();
        }}
      />
    </>
  );
}

/** One bank question: its text, options (the correct ones ticked) or answer, and its labels. */
export function QuestionCard({
  question,
  onEdit,
  onDelete,
  action,
}: {
  question: QuestionView;
  onEdit?: () => void;
  onDelete?: () => void;
  /** e.g. an "Add to test" button */
  action?: React.ReactNode;
}) {
  const { t } = useI18n();
  return (
    <li className="rounded-[10px] border border-line p-3" data-testid="question-card">
      <div className="flex flex-wrap items-start gap-2">
        <p className="min-w-0 flex-1 whitespace-pre-line text-[14px] font-semibold">{question.text}</p>
        {question.locked ? (
          <span title={t("lessons.questions.lockedHint")}>
            <Pill>
              <Lock size={12} aria-hidden="true" />
              {t("lessons.questions.locked")}
            </Pill>
          </span>
        ) : null}
        {onEdit ? (
          <button type="button" className="iconbtn" onClick={onEdit} aria-label={t("lessons.questions.edit")}>
            <Pencil size={16} aria-hidden="true" />
          </button>
        ) : null}
        {onDelete ? (
          <button type="button" className="iconbtn" onClick={onDelete} aria-label={t("lessons.questions.delete")}>
            <Trash2 size={16} aria-hidden="true" />
          </button>
        ) : null}
        {action}
      </div>
      {question.options.length > 0 ? (
        <ul className="mt-1.5 flex flex-col gap-0.5 text-[13.5px]">
          {question.options.map((o) => (
            <li key={o.id} className={`flex items-start gap-1.5 ${o.correct ? "font-semibold text-good" : "text-ink-2"}`}>
              {o.correct ? <Check size={15} aria-label={t("lessons.questions.correct")} className="mt-0.5 flex-none" /> : <X size={15} aria-hidden="true" className="mt-0.5 flex-none text-ink-3" />}
              <span>{o.text}</span>
            </li>
          ))}
        </ul>
      ) : question.answerText ? (
        <p className="mt-1.5 text-[13.5px] text-ink-2">
          <b>{t("lessons.questions.answer")}:</b>{" "}
          {question.type === "TRUE_FALSE"
            ? translateOr(t, `lessons.tf.${question.answerText}`, question.answerText)
            : question.answerText.split("\n").join(" | ")}
        </p>
      ) : null}
      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[12.5px] text-ink-3">
        <Pill tone="accent">{typeLabel(t, question.type)}</Pill>
        <Pill tone={DIFFICULTY_TONES[question.difficulty]}>{difficultyLabel(t, question.difficulty)}</Pill>
        <span className="num">{plural(t, "lessons.marksCount", question.marks, { marks: formatScore(question.marks) })}</span>
        <span>· {[question.className, question.subjectName, question.topicTitle].filter(Boolean).join(" · ")}</span>
        {question.bloomLevel ? <span>· {translateOr(t, `lessons.bloom.${question.bloomLevel}`, question.bloomLevel)}</span> : null}
        {question.createdByName ? <span>· {question.createdByName}</span> : null}
        {!question.shared ? <span>· {t("lessons.questions.private")}</span> : null}
        {question.usedInTests > 0 ? <span>· {plural(t, "lessons.questions.usedIn", question.usedInTests)}</span> : null}
      </div>
    </li>
  );
}

function initialQuestion(question: QuestionView | null, defaults: { classId: string; subjectId: string }): QuestionValues {
  if (!question) {
    return {
      classId: defaults.classId,
      subjectId: defaults.subjectId,
      topicId: "",
      type: "MCQ_SINGLE",
      difficulty: "MEDIUM",
      bloomLevel: "",
      text: "",
      answerText: "",
      explanation: "",
      marks: "1",
      shared: true,
      options: BLANK_OPTIONS,
    };
  }
  return {
    classId: question.classId,
    subjectId: question.subjectId,
    topicId: question.topicId ?? "",
    type: question.type,
    difficulty: question.difficulty,
    bloomLevel: question.bloomLevel ?? "",
    text: question.text,
    answerText: question.answerText ?? "",
    explanation: question.explanation ?? "",
    marks: formatScore(question.marks),
    shared: question.shared,
    options: question.options.length ? question.options.map((o) => ({ text: o.text, correct: o.correct })) : BLANK_OPTIONS,
  };
}

/** Adding or changing a bank question of any kind. */
export function QuestionDialog({
  question,
  options,
  defaults,
  onClose,
  onSaved,
}: {
  question: QuestionView | null;
  options: LessonsOptions;
  defaults: { classId: string; subjectId: string };
  onClose: () => void;
  onSaved: (question: QuestionView) => void;
}) {
  const { t } = useI18n();
  const tfName = useId();
  const form = useForm<QuestionValues>(initialQuestion(question, defaults));
  const { values, set, errors } = form;
  const classes = options.classes.filter((c) => c.subjects.length > 0);
  const chosen = classes.find((c) => c.id === values.classId);
  const topics = useApiData(
    values.classId && values.subjectId ? `lessons:topics::${values.classId}:${values.subjectId}` : null,
    () => lessonsApi.topics(values.classId, values.subjectId),
  );
  const setOption = (index: number, patch: Partial<OptionValue>) =>
    set(
      "options",
      values.options.map((o, i) =>
        i === index ? { ...o, ...patch } : patch.correct && values.type === "MCQ_SINGLE" ? { ...o, correct: false } : o,
      ),
    );

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateQuestion(values), t, event.currentTarget)) return;
    const body = questionRequest(values);
    let saved: QuestionView | undefined;
    const ok = await form.submit(t, async () => {
      saved = question ? await lessonsApi.updateQuestion(question.id, body) : await lessonsApi.createQuestion(body);
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={question ? t("lessons.questions.edit") : t("lessons.questions.add")}
      description={t("lessons.questions.dialog")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="question-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <SelectField
            label={t("lessons.class")}
            name="classId"
            value={values.classId}
            onChange={(e) => {
              set("classId", e.target.value);
              set("subjectId", "");
              set("topicId", "");
            }}
            options={[{ value: "", label: t("lessons.pick") }, ...classes.map((c) => ({ value: c.id, label: c.name }))]}
            error={errors.classId}
            required
            data-autofocus
          />
          <SelectField
            label={t("lessons.subject")}
            name="subjectId"
            value={values.subjectId}
            onChange={(e) => {
              set("subjectId", e.target.value);
              set("topicId", "");
            }}
            options={[{ value: "", label: t("lessons.pick") }, ...(chosen?.subjects ?? []).map((s) => ({ value: s.id, label: s.name }))]}
            error={errors.subjectId}
            disabled={!values.classId}
            required
          />
        </div>
        <SelectField
          label={t("lessons.topic")}
          name="topicId"
          value={values.topicId}
          onChange={(e) => set("topicId", e.target.value)}
          options={[
            { value: "", label: t("lessons.topic.none") },
            ...(topics.data ?? []).map((topic) => ({ value: topic.id, label: `${topic.unitTitle} · ${topic.title}` })),
          ]}
          error={errors.topicId}
          disabled={!values.subjectId}
        />
        <div className="grid2">
          <SelectField
            label={t("lessons.questions.type")}
            name="type"
            value={values.type}
            onChange={(e) => {
              const next = e.target.value as QuestionType;
              set("type", next);
              set("answerText", "");
              if (next === "MCQ_SINGLE") set("options", values.options.map((o, i) => ({ ...o, correct: o.correct && values.options.findIndex((x) => x.correct) === i })));
            }}
            options={QUESTION_TYPES.map((x) => ({ value: x, label: typeLabel(t, x) }))}
            required
          />
          <SelectField
            label={t("lessons.questions.difficulty")}
            name="difficulty"
            value={values.difficulty}
            onChange={(e) => set("difficulty", e.target.value as Difficulty)}
            options={DIFFICULTIES.map((x) => ({ value: x, label: difficultyLabel(t, x) }))}
            required
          />
        </div>
        <TextAreaField
          label={t("lessons.questions.text")}
          name="text"
          rows={3}
          maxLength={LESSONS_LIMITS.questionText}
          value={values.text}
          onChange={(e) => set("text", e.target.value)}
          hint={values.type === "FILL_BLANK" ? t("lessons.questions.blankHint") : undefined}
          error={errors.text}
          required
        />
        {hasOptions(values.type) ? (
          <fieldset className="fieldset" aria-describedby={errors.options ? `${tfName}-options-error` : undefined}>
            <legend>{values.type === "MCQ_SINGLE" ? t("lessons.questions.optionsOne") : t("lessons.questions.optionsMany")}</legend>
            <ol className="flex flex-col gap-2">
              {values.options.map((o, i) => (
                <li key={i} className="flex items-center gap-2">
                  <label className="flex flex-none items-center" title={t("lessons.questions.correct")}>
                    <input
                      type={values.type === "MCQ_SINGLE" ? "radio" : "checkbox"}
                      name={values.type === "MCQ_SINGLE" ? `${tfName}-correct` : `correct-${i}`}
                      className="h-[18px] w-[18px] accent-[var(--accent)]"
                      checked={o.correct}
                      onChange={(e) => setOption(i, { correct: e.target.checked })}
                      aria-label={t("lessons.questions.optionCorrect", { n: i + 1 })}
                    />
                  </label>
                  <input
                    className="input min-w-0 flex-1"
                    name={i === 0 ? "options" : `option-${i}`}
                    value={o.text}
                    maxLength={LESSONS_LIMITS.option}
                    aria-label={t("lessons.questions.optionN", { n: i + 1 })}
                    aria-invalid={errors.options ? true : undefined}
                    onChange={(e) => setOption(i, { text: e.target.value })}
                  />
                  <button
                    type="button"
                    className="iconbtn"
                    disabled={values.options.length <= 2}
                    onClick={() => set("options", values.options.filter((_, j) => j !== i))}
                    aria-label={t("lessons.questions.removeOption", { n: i + 1 })}
                  >
                    <X size={16} aria-hidden="true" />
                  </button>
                </li>
              ))}
            </ol>
            {values.options.length < LESSONS_LIMITS.options ? (
              <button type="button" className="btn btn-sm mt-2" onClick={() => set("options", [...values.options, { text: "", correct: false }])}>
                <Plus size={16} aria-hidden="true" />
                {t("lessons.questions.addOption")}
              </button>
            ) : null}
            {errors.options ? (
              <p id={`${tfName}-options-error`} className="field-error">
                {errors.options}
              </p>
            ) : null}
          </fieldset>
        ) : values.type === "TRUE_FALSE" ? (
          <fieldset className="fieldset">
            <legend>{t("lessons.questions.answer")}</legend>
            <div className="seg" role="radiogroup" aria-label={t("lessons.questions.answer")}>
              {(["TRUE", "FALSE"] as const).map((v) => (
                <label key={v}>
                  <input type="radio" name="answerText" value={v} checked={values.answerText === v} onChange={() => set("answerText", v)} />
                  {t(`lessons.tf.${v}`)}
                </label>
              ))}
            </div>
            {errors.answerText ? <p className="field-error">{errors.answerText}</p> : null}
          </fieldset>
        ) : (
          <TextAreaField
            label={values.type === "FILL_BLANK" ? t("lessons.questions.accepted") : t("lessons.questions.model")}
            name="answerText"
            rows={2}
            maxLength={LESSONS_LIMITS.answerText}
            value={values.answerText}
            onChange={(e) => set("answerText", e.target.value)}
            hint={values.type === "FILL_BLANK" ? t("lessons.questions.acceptedHint") : t("lessons.questions.modelHint")}
            error={errors.answerText}
            required={values.type === "FILL_BLANK"}
          />
        )}
        <div className="grid2">
          <TextField
            label={t("lessons.questions.marks")}
            name="marks"
            inputMode="decimal"
            value={values.marks}
            onChange={(e) => set("marks", e.target.value)}
            error={errors.marks}
            required
          />
          <SelectField
            label={t("lessons.questions.bloom")}
            name="bloomLevel"
            value={values.bloomLevel}
            onChange={(e) => set("bloomLevel", e.target.value as BloomLevel | "")}
            options={[
              { value: "", label: t("lessons.questions.bloomNone") },
              ...BLOOM_LEVELS.map((b) => ({ value: b, label: t(`lessons.bloom.${b}`) })),
            ]}
          />
        </div>
        <TextAreaField
          label={t("lessons.questions.explanation")}
          name="explanation"
          rows={2}
          maxLength={LESSONS_LIMITS.explanation}
          value={values.explanation}
          onChange={(e) => set("explanation", e.target.value)}
          hint={t("lessons.questions.explanationHint")}
          error={errors.explanation}
        />
        <label className="check">
          <input type="checkbox" name="shared" checked={values.shared} onChange={(e) => set("shared", e.target.checked)} />
          <span>
            <b className="block font-semibold">{t("lessons.questions.shared")}</b>
            <span className="text-[12.5px] text-ink-3">{t("lessons.questions.sharedHint")}</span>
          </span>
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
