"use client";

import { ArrowRight, Pencil, Plus, Scale, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { examsApi } from "@/lib/exams-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { ExamClassRef, ExamStructure, ExamSummary, ExamTerm, ExamYearRef } from "@/lib/types";
import { INSTRUCTIONS_MAX } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { ExamsNav, formatPct, formatScore, parseDecimal, YearSelect } from "./exams-shared";
import { MarksOverviewView } from "./marks-overview";

const TERM_NAME_MAX = 60;
const EXAM_NAME_MAX = 100;

/** /app/exams: the year's terms and exams with their weightage (exams.read); marks entry for exams.manage alone. */
export function ExamsHomeView() {
  const { me } = useAuth();
  if (!hasPermission(me, PERMISSIONS.examsRead)) return <MarksOverviewView />;
  return <ExamsStructureView />;
}

function ExamsStructureView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [yearId, setYearId] = useState("");
  const structure = useApiData(`exams:structure:${yearId}`, () => examsApi.structure(yearId || null));
  const data = structure.data && (!yearId || structure.data.year?.id === yearId) ? structure.data : undefined;
  const [termDialog, setTermDialog] = useState<ExamTerm | "new" | null>(null);
  const [examDialog, setExamDialog] = useState<{ term: ExamTerm; exam: ExamSummary | null } | null>(null);
  const [weights, setWeights] = useState<{ title: string; items: WeightItem[]; save: SaveWeights } | null>(null);
  const [deleting, setDeleting] = useState<{ kind: "term"; term: ExamTerm } | { kind: "exam"; exam: ExamSummary } | null>(
    null,
  );

  const canSetup = Boolean(data?.canSetup);
  const year = data?.year ?? null;

  const saved = (message: string) => {
    toast(message);
    structure.reload();
  };

  const openTermWeights = (s: ExamStructure) =>
    setWeights({
      title: t("exams.weights.terms"),
      items: s.terms.map((term) => ({ id: term.id, name: term.name, weight: term.weight })),
      save: async (list) => {
        await examsApi.termWeights(s.year!.id, { weights: list });
        saved(t("exams.weights.saved"));
      },
    });

  const openExamWeights = (term: ExamTerm) =>
    setWeights({
      title: t("exams.weights.exams", { term: term.name }),
      items: term.exams.map((exam) => ({ id: exam.id, name: exam.name, weight: exam.weight })),
      save: async (list) => {
        await examsApi.examWeights(term.id, { weights: list });
        saved(t("exams.weights.saved"));
      },
    });

  return (
    <>
      <PageHead
        eyebrow={year ? year.name : undefined}
        title={t("exams.title")}
        actions={
          canSetup && year ? (
            <button type="button" className="btn btn-primary" onClick={() => setTermDialog("new")}>
              <Plus size={18} aria-hidden="true" />
              {t("exams.term.add")}
            </button>
          ) : null
        }
      />
      <ExamsNav />
      {data && data.years.length > 1 ? (
        <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("exams.filters")}>
          <YearSelect years={data.years} value={yearId || data.year?.id || ""} onChange={setYearId} />
        </form>
      ) : null}

      {structure.error && !data ? (
        <section className="card">
          <ErrorState error={structure.error} onRetry={structure.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : !year ? (
        <section className="card flex flex-col items-start gap-3">
          <p className="empty">{t("exams.noYear")}</p>
          <Link href="/app/setup" className="btn">
            {t("exams.noYear.link")}
          </Link>
        </section>
      ) : data.terms.length === 0 ? (
        <section className="card flex flex-col items-start gap-3" data-testid="exams-empty">
          <p className="empty">{canSetup ? t("exams.terms.emptySetup") : t("exams.terms.empty")}</p>
          {canSetup ? (
            <button type="button" className="btn btn-primary" onClick={() => setTermDialog("new")}>
              <Plus size={18} aria-hidden="true" />
              {t("exams.term.add")}
            </button>
          ) : null}
        </section>
      ) : (
        <>
          <WeightsSummary structure={data} onEdit={canSetup ? () => openTermWeights(data) : undefined} />
          {data.terms.map((term) => (
            <section key={term.id} className="card flex flex-col gap-3" aria-labelledby={`term-${term.id}`} data-testid="exam-term">
              <div className="card-head" style={{ marginBottom: 0 }}>
                <div className="min-w-0">
                  <h2 id={`term-${term.id}`}>{term.name}</h2>
                  <p className="mt-1 text-[13px] text-ink-3">
                    {t("exams.term.dates", {
                      from: formatPlainDate(term.startsOn, locale),
                      to: formatPlainDate(term.endsOn, locale),
                    })}
                    {term.weight !== null ? ` · ${t("exams.weight", { weight: formatScore(term.weight) })}` : ""}
                  </p>
                </div>
                {canSetup ? (
                  <div className="flex flex-wrap justify-end gap-1.5">
                    <button type="button" className="btn btn-sm" onClick={() => setExamDialog({ term, exam: null })}>
                      <Plus size={16} aria-hidden="true" />
                      {t("exams.exam.add")}
                    </button>
                    {term.exams.length > 0 ? (
                      <button type="button" className="btn btn-sm" onClick={() => openExamWeights(term)}>
                        <Scale size={16} aria-hidden="true" />
                        {t("exams.weights.edit")}
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className="iconbtn"
                      onClick={() => setTermDialog(term)}
                      aria-label={t("exams.term.editNamed", { name: term.name })}
                    >
                      <Pencil size={16} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="iconbtn"
                      onClick={() => setDeleting({ kind: "term", term })}
                      aria-label={t("exams.term.deleteNamed", { name: term.name })}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </button>
                  </div>
                ) : null}
              </div>
              {term.exams.length === 0 ? (
                <p className="text-sm text-ink-2">{canSetup ? t("exams.exams.emptySetup") : t("exams.exams.empty")}</p>
              ) : (
                <>
                  {term.examWeightTotal !== null && term.examWeightTotal !== 100 ? (
                    <div className="alert alert-warn" role="status">
                      <span>{t("exams.weights.examsOff", { total: formatScore(term.examWeightTotal) })}</span>
                    </div>
                  ) : null}
                  <ul className="flex flex-col gap-3">
                    {term.exams.map((exam) => (
                      <ExamBlock
                        key={exam.id}
                        exam={exam}
                        canSetup={canSetup}
                        onEdit={() => setExamDialog({ term, exam })}
                        onDelete={() => setDeleting({ kind: "exam", exam })}
                      />
                    ))}
                  </ul>
                </>
              )}
            </section>
          ))}
        </>
      )}

      {year ? (
        <TermDialog
          key={termDialog === "new" ? "new" : (termDialog?.id ?? "none")}
          open={termDialog !== null}
          term={termDialog === "new" ? null : termDialog}
          year={year}
          onClose={() => setTermDialog(null)}
          onSaved={(name) => {
            setTermDialog(null);
            saved(t("exams.term.saved", { name }));
          }}
        />
      ) : null}
      {examDialog && data ? (
        <ExamDialog
          key={examDialog.exam?.id ?? `new:${examDialog.term.id}`}
          open
          term={examDialog.term}
          exam={examDialog.exam}
          classes={data.classes}
          onClose={() => setExamDialog(null)}
          onSaved={(name) => {
            setExamDialog(null);
            saved(t("exams.exam.saved", { name }));
          }}
        />
      ) : null}
      {weights ? (
        <WeightsDialog
          title={weights.title}
          items={weights.items}
          onClose={() => setWeights(null)}
          onSave={async (list) => {
            await weights.save(list);
            setWeights(null);
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting !== null}
        title={
          deleting?.kind === "term"
            ? t("exams.term.deleteTitle", { name: deleting.term.name })
            : deleting?.kind === "exam"
              ? t("exams.exam.deleteTitle", { name: deleting.exam.name })
              : ""
        }
        body={deleting?.kind === "term" ? t("exams.term.deleteBody") : t("exams.exam.deleteBody")}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          if (deleting.kind === "term") await examsApi.deleteTerm(deleting.term.id);
          else await examsApi.deleteExam(deleting.exam.id);
          setDeleting(null);
          saved(t("common.deleted"));
        }}
      />
    </>
  );
}

/** How the terms add up to the year (they must total 100%). */
function WeightsSummary({ structure, onEdit }: { structure: ExamStructure; onEdit?: () => void }) {
  const { t } = useI18n();
  const total = structure.termWeightTotal;
  const complete = total === 100;
  return (
    <section className="card flex flex-wrap items-center gap-x-4 gap-y-2" data-testid="term-weights">
      <div className="min-w-0 flex-1 basis-full sm:basis-0">
        <h2 className="text-base">{t("exams.weights.title")}</h2>
        <p className="mt-1 text-[13.5px] text-ink-2">
          {structure.terms
            .map((term) => `${term.name} ${term.weight === null ? "—" : formatPct(term.weight)}`)
            .join(" · ")}
        </p>
      </div>
      {complete ? (
        <Pill tone="good" dot>
          {t("exams.weights.complete")}
        </Pill>
      ) : (
        <Pill tone="warn" dot>
          {total === null ? t("exams.weights.notSet") : t("exams.weights.total", { total: formatScore(total) })}
        </Pill>
      )}
      {onEdit ? (
        <button type="button" className="btn btn-sm" onClick={onEdit}>
          <Scale size={16} aria-hidden="true" />
          {t("exams.weights.edit")}
        </button>
      ) : null}
    </section>
  );
}

function ExamBlock({
  exam,
  canSetup,
  onEdit,
  onDelete,
}: {
  exam: ExamSummary;
  canSetup: boolean;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const dates =
    exam.firstPaperOn && exam.lastPaperOn
      ? exam.firstPaperOn === exam.lastPaperOn
        ? formatPlainDate(exam.firstPaperOn, locale)
        : t("exams.term.dates", {
            from: formatPlainDate(exam.firstPaperOn, locale),
            to: formatPlainDate(exam.lastPaperOn, locale),
          })
      : t("exams.exam.noDates");
  return (
    <li className="rounded-[10px] border border-line p-3" data-testid="exam-block">
      <div className="flex flex-wrap items-start gap-2">
        <div className="min-w-0 flex-1">
          <h3 className="font-display text-[15px] font-bold leading-tight">{exam.name}</h3>
          <p className="mt-0.5 text-[13px] text-ink-3">
            {dates}
            {exam.weight !== null ? ` · ${t("exams.weight", { weight: formatScore(exam.weight) })}` : ""}
          </p>
        </div>
        {canSetup ? (
          <div className="flex gap-1">
            <button type="button" className="iconbtn" onClick={onEdit} aria-label={t("exams.exam.editNamed", { name: exam.name })}>
              <Pencil size={16} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="iconbtn"
              onClick={onDelete}
              aria-label={t("exams.exam.deleteNamed", { name: exam.name })}
            >
              <Trash2 size={16} aria-hidden="true" />
            </button>
          </div>
        ) : null}
      </div>
      {exam.classes.length === 0 ? (
        <p className="mt-2 text-[13px] text-ink-3">{t("exams.exam.noClasses")}</p>
      ) : (
        <ul className="rowcards mt-1">
          {exam.classes.map((c) => (
            <li key={c.classId}>
              <Link
                href={`/app/exams/${encodeURIComponent(exam.id)}/${encodeURIComponent(c.classId)}`}
                className="rowcard"
                data-testid="exam-class-link"
              >
                <span className="min-w-0 flex-1">
                  <b className="block font-semibold">{c.className}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {c.papers === 0
                      ? t("exams.class.noPapers")
                      : t("exams.class.papers", { scheduled: c.papersScheduled, total: c.papers })}
                  </span>
                </span>
                <span className="flex flex-wrap justify-end gap-1.5">
                  {c.resultsPublishedAt ? (
                    <Pill tone="good" dot>
                      {t("exams.results.published")}
                    </Pill>
                  ) : c.dateSheetPublishedAt ? (
                    <Pill tone="info" dot>
                      {t("exams.dateSheet.published")}
                    </Pill>
                  ) : (
                    <Pill>{t("exams.dateSheet.draft")}</Pill>
                  )}
                </span>
                <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

/* ------------------------------------------------------------------ dialogs */

type TermValues = { name: string; startsOn: string; endsOn: string };

/** Client checks matching the API's (docs/api/phase-2-exams.md). Pure, exported for tests. */
export function validateTerm(values: TermValues, year: Pick<ExamYearRef, "startsOn" | "endsOn">): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "exams.v.name";
  else if (values.name.trim().length > TERM_NAME_MAX) problems.name = "exams.v.termNameLong";
  if (!isPlainDate(values.startsOn)) problems.startsOn = "exams.v.date";
  if (!isPlainDate(values.endsOn)) problems.endsOn = "exams.v.date";
  else if (isPlainDate(values.startsOn) && values.endsOn < values.startsOn) problems.endsOn = "exams.v.dateOrder";
  if (!problems.startsOn && !problems.endsOn && (values.startsOn < year.startsOn || values.endsOn > year.endsOn)) {
    problems.endsOn = "exams.v.termInYear";
  }
  return problems;
}

function TermDialog({
  open,
  term,
  year,
  onClose,
  onSaved,
}: {
  open: boolean;
  term: ExamTerm | null;
  year: ExamYearRef;
  onClose: () => void;
  onSaved: (name: string) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const initial: TermValues = term
    ? { name: term.name, startsOn: term.startsOn, endsOn: term.endsOn }
    : { name: "", startsOn: year.startsOn, endsOn: year.endsOn };
  const form = useForm<TermValues>(initial);
  const { values, set, errors } = form;

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateTerm(values, year), t, event.currentTarget)) return;
    const body = { academicYearId: year.id, name: values.name.trim(), startsOn: values.startsOn, endsOn: values.endsOn };
    const ok = await form.submit(t, async () => {
      if (term) await examsApi.updateTerm(term.id, body);
      else await examsApi.createTerm(body);
    });
    if (ok) onSaved(body.name);
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={term ? t("exams.term.edit") : t("exams.term.add")}
      description={t("exams.term.dialog", {
        year: year.name,
        from: formatPlainDate(year.startsOn, locale),
        to: formatPlainDate(year.endsOn, locale),
      })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("exams.term.name")}
          name="name"
          value={values.name}
          maxLength={TERM_NAME_MAX}
          placeholder={t("exams.term.namePlaceholder")}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
          required
          data-autofocus
        />
        <div className="grid2">
          <TextField
            label={t("exams.term.startsOn")}
            name="startsOn"
            type="date"
            value={values.startsOn}
            min={year.startsOn}
            max={year.endsOn}
            onChange={(e) => set("startsOn", e.target.value)}
            error={errors.startsOn}
            required
          />
          <TextField
            label={t("exams.term.endsOn")}
            name="endsOn"
            type="date"
            value={values.endsOn}
            min={values.startsOn || year.startsOn}
            max={year.endsOn}
            onChange={(e) => set("endsOn", e.target.value)}
            error={errors.endsOn}
            required
          />
        </div>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
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

type ExamValues = { name: string; instructions: string; classIds: string[] };

/** Client checks matching the API's. Pure, exported for tests. */
export function validateExam(values: ExamValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "exams.v.name";
  else if (values.name.trim().length > EXAM_NAME_MAX) problems.name = "exams.v.examNameLong";
  if (values.instructions.length > INSTRUCTIONS_MAX) problems.instructions = "exams.v.instructionsLong";
  return problems;
}

function ExamDialog({
  open,
  term,
  exam,
  classes,
  onClose,
  onSaved,
}: {
  open: boolean;
  term: ExamTerm;
  exam: ExamSummary | null;
  classes: ExamClassRef[];
  onClose: () => void;
  onSaved: (name: string) => void;
}) {
  const { t } = useI18n();
  const initial: ExamValues = exam
    ? { name: exam.name, instructions: exam.instructions ?? "", classIds: exam.classes.map((c) => c.classId) }
    : { name: "", instructions: "", classIds: [] };
  const form = useForm<ExamValues>(initial);
  const { values, set, errors } = form;
  const used = new Set(exam?.classes.filter((c) => c.papers > 0).map((c) => c.classId) ?? []);

  const toggle = (classId: string, on: boolean) =>
    set("classIds", on ? [...values.classIds, classId] : values.classIds.filter((id) => id !== classId));

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateExam(values), t, event.currentTarget)) return;
    const body = {
      termId: term.id,
      name: values.name.trim(),
      instructions: values.instructions.trim() || null,
      classIds: classes.map((c) => c.id).filter((id) => values.classIds.includes(id)),
    };
    const ok = await form.submit(t, async () => {
      if (exam) await examsApi.updateExam(exam.id, body);
      else await examsApi.createExam(body);
    });
    if (ok) onSaved(body.name);
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={exam ? t("exams.exam.edit") : t("exams.exam.add")}
      description={t("exams.exam.dialog", { term: term.name })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("exams.exam.name")}
          name="name"
          value={values.name}
          maxLength={EXAM_NAME_MAX}
          placeholder={t("exams.exam.namePlaceholder")}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
          required
          data-autofocus
        />
        <fieldset className="fieldset">
          <legend className="field-label">{t("exams.exam.classes")}</legend>
          {classes.length === 0 ? (
            <p className="text-sm text-ink-2">{t("exams.exam.noSchoolClasses")}</p>
          ) : (
            <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 sm:grid-cols-3">
              {classes.map((c) => (
                <label key={c.id} className="check">
                  <input
                    type="checkbox"
                    name="classIds"
                    value={c.id}
                    checked={values.classIds.includes(c.id)}
                    disabled={used.has(c.id) && values.classIds.includes(c.id)}
                    onChange={(e) => toggle(c.id, e.target.checked)}
                  />
                  <span>{c.name}</span>
                </label>
              ))}
            </div>
          )}
          {errors.classIds ? <p className="field-error">{errors.classIds}</p> : null}
          {used.size > 0 ? <p className="field-hint">{t("exams.exam.classesInUse")}</p> : null}
        </fieldset>
        <TextAreaField
          label={t("exams.exam.instructions")}
          name="instructions"
          value={values.instructions}
          maxLength={INSTRUCTIONS_MAX}
          hint={t("exams.exam.instructionsHint")}
          onChange={(e) => set("instructions", e.target.value)}
          error={errors.instructions}
        />
        <div className="flex justify-end gap-2 pt-1">
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

type WeightItem = { id: string; name: string; weight: number | null };
type SaveWeights = (weights: { id: string; weight: number }[]) => Promise<void>;

/** The weights as numbers, or the problem: each 0–100 with at most two decimals, adding up to 100. Pure. */
export function checkWeights(texts: string[]): { weights: number[] } | { problem: "exams.v.weight" | "exams.v.weightTotal" } {
  const weights = texts.map(parseDecimal);
  if (weights.some((w) => w === null || Number.isNaN(w) || w > 100)) return { problem: "exams.v.weight" };
  const total = Math.round((weights as number[]).reduce((sum, w) => sum + w, 0) * 100) / 100;
  if (total !== 100) return { problem: "exams.v.weightTotal" };
  return { weights: weights as number[] };
}

function WeightsDialog({
  title,
  items,
  onClose,
  onSave,
}: {
  title: string;
  items: WeightItem[];
  onClose: () => void;
  onSave: SaveWeights;
}) {
  const { t } = useI18n();
  const even = items.length > 0 ? Math.floor((100 / items.length) * 100) / 100 : 0;
  const [texts, setTexts] = useState<string[]>(() =>
    items.map((item, i) =>
      item.weight !== null
        ? formatScore(item.weight)
        : formatScore(i === items.length - 1 ? Math.round((100 - even * (items.length - 1)) * 100) / 100 : even),
    ),
  );
  const [error, setError] = useState<string | null>(null);
  const form = useForm<Record<string, never>>({});
  const parsed = texts.map(parseDecimal);
  const total = parsed.every((w) => w !== null && !Number.isNaN(w))
    ? Math.round((parsed as number[]).reduce((sum, w) => sum + w, 0) * 100) / 100
    : null;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const checked = checkWeights(texts);
    if ("problem" in checked) {
      setError(t(checked.problem));
      return;
    }
    setError(null);
    await form.submit(t, () => onSave(items.map((item, i) => ({ id: item.id, weight: checked.weights[i] }))));
  };

  return (
    <Dialog open onClose={onClose} title={title} description={t("exams.weights.dialog")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={error ?? form.formError} />
        {items.map((item, i) => (
          <TextField
            key={item.id}
            label={item.name}
            name={`weight-${i}`}
            inputMode="decimal"
            value={texts[i]}
            onChange={(e) => setTexts((prev) => prev.map((text, j) => (j === i ? e.target.value : text)))}
            hint={t("exams.weights.hint")}
            data-autofocus={i === 0 ? true : undefined}
          />
        ))}
        <p className={`text-[13.5px] font-semibold ${total === 100 ? "text-good" : "text-warn"}`} aria-live="polite">
          {total === null ? t("exams.weights.totalUnknown") : t("exams.weights.total", { total: formatScore(total) })}
        </p>
        <div className="flex justify-end gap-2 pt-1">
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
