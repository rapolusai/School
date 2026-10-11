"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { LESSONS_LIMITS, type LessonsOptions, type LessonsYear, type PlanDetail, type PlanRequest } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate, isWholeNumberInRange } from "@/lib/validation";
import { mondayOf, monthLabel, planChoices, plusDays } from "./lessons-shared";

export type PlanValues = {
  sectionId: string;
  subjectId: string;
  topicId: string;
  title: string;
  weekStart: string;
  planDate: string;
  periods: string;
  objectives: string;
  teachingMethod: string;
  resources: string;
  resourceLinks: string;
  homeworkIdea: string;
  assessmentNote: string;
};

const LINK = /^https?:\/\/\S+$/i;

/** Client checks matching the API's (docs/api/phase-2-lessons.md). Pure, exported for tests. */
export function validatePlan(values: PlanValues, year?: Pick<LessonsYear, "startsOn" | "endsOn"> | null): Problems {
  const problems: Problems = {};
  if (!values.sectionId) problems.sectionId = "lessons.v.section";
  if (!values.subjectId) problems.subjectId = "lessons.v.subject";
  if (!values.topicId && !values.title.trim()) problems.title = "lessons.v.titleOrTopic";
  else if (values.title.trim().length > LESSONS_LIMITS.title) problems.title = "lessons.v.titleLong";
  if (!isPlainDate(values.weekStart)) problems.weekStart = "lessons.v.date";
  else if (year && (plusDays(mondayOf(values.weekStart), 6) < year.startsOn || mondayOf(values.weekStart) > year.endsOn)) {
    // Like the API: the week (Monday to Sunday) must overlap the year, so the first and last weeks can be planned.
    problems.weekStart = "lessons.v.weekInYear";
  }
  if (values.planDate) {
    if (!isPlainDate(values.planDate)) problems.planDate = "lessons.v.date";
    else if (isPlainDate(values.weekStart)) {
      const monday = mondayOf(values.weekStart);
      if (values.planDate < monday || values.planDate > plusDays(monday, 6)) problems.planDate = "lessons.v.planDate";
    }
  }
  if (!isWholeNumberInRange(values.periods, 1, LESSONS_LIMITS.planPeriods)) problems.periods = "lessons.v.periods";
  for (const field of ["objectives", "teachingMethod", "resources"] as const) {
    if (values[field].trim().length > LESSONS_LIMITS.planText) problems[field] = "lessons.v.long2000";
  }
  for (const field of ["homeworkIdea", "assessmentNote"] as const) {
    if (values[field].trim().length > LESSONS_LIMITS.planShortText) problems[field] = "lessons.v.long1000";
  }
  const links = values.resourceLinks
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  if (values.resourceLinks.trim().length > LESSONS_LIMITS.planText || links.some((link) => !LINK.test(link))) {
    problems.resourceLinks = "lessons.v.links";
  }
  return problems;
}

/** The request body for a plan's values. Pure, exported for tests. */
export function planRequest(values: PlanValues, yearId: string | null, version?: number): PlanRequest {
  const text = (value: string) => value.trim() || null;
  return {
    academicYearId: yearId,
    sectionId: values.sectionId,
    subjectId: values.subjectId,
    topicId: values.topicId || null,
    title: text(values.title),
    weekStart: values.weekStart,
    planDate: values.planDate || null,
    periods: Number(values.periods),
    objectives: text(values.objectives),
    teachingMethod: text(values.teachingMethod),
    resources: text(values.resources),
    resourceLinks:
      values.resourceLinks
        .split("\n")
        .map((line) => line.trim())
        .filter(Boolean)
        .join("\n") || null,
    homeworkIdea: text(values.homeworkIdea),
    assessmentNote: text(values.assessmentNote),
    version: version ?? null,
  };
}

function initialValues(plan: PlanDetail | null, week: string): PlanValues {
  if (!plan) {
    return {
      sectionId: "",
      subjectId: "",
      topicId: "",
      title: "",
      weekStart: week,
      planDate: "",
      periods: "1",
      objectives: "",
      teachingMethod: "",
      resources: "",
      resourceLinks: "",
      homeworkIdea: "",
      assessmentNote: "",
    };
  }
  return {
    sectionId: plan.sectionId,
    subjectId: plan.subjectId,
    topicId: plan.topicId ?? "",
    title: plan.topicId && plan.title === plan.topicTitle ? "" : plan.title,
    weekStart: plan.weekStart,
    planDate: plan.planDate ?? "",
    periods: String(plan.periods),
    objectives: plan.objectives ?? "",
    teachingMethod: plan.teachingMethod ?? "",
    resources: plan.resources ?? "",
    resourceLinks: plan.resourceLinks.join("\n"),
    homeworkIdea: plan.homeworkIdea ?? "",
    assessmentNote: plan.assessmentNote ?? "",
  };
}

/**
 * Writing or changing a lesson plan: the section and subject (those the teacher teaches), a syllabus topic or a
 * title, the week (and optionally the day), periods, and what the lesson needs. Saved as a draft.
 */
export function PlanDialog({
  open,
  plan,
  options,
  week,
  onClose,
  onSaved,
}: {
  open: boolean;
  plan: PlanDetail | null;
  options: LessonsOptions;
  /** the week a new plan starts in ("2026-10-12") */
  week: string;
  onClose: () => void;
  onSaved: (plan: PlanDetail) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const initial = initialValues(plan, week);
  const form = useForm<PlanValues>(initial);
  const { values, set, errors } = form;
  const [more, setMore] = useState(Boolean(plan));
  const year = options.years.find((y) => y.id === options.yearId) ?? null;
  const choices = planChoices(options);
  const choice = choices.find((c) => c.sectionId === values.sectionId);
  const subjects = choice?.subjects ?? [];
  const classId = choice?.classId ?? "";
  const topics = useApiData(
    classId && values.subjectId ? `lessons:topics:${options.yearId}:${classId}:${values.subjectId}` : null,
    () => lessonsApi.topics(classId, values.subjectId, options.yearId),
  );
  const monday = isPlainDate(values.weekStart) ? mondayOf(values.weekStart) : "";

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validatePlan(values, year), t, event.currentTarget)) return;
    const body = planRequest(values, options.yearId, plan?.version);
    let saved: PlanDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = plan ? await lessonsApi.updatePlan(plan.id, body) : await lessonsApi.createPlan(body);
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={plan ? t("lessons.plan.edit") : t("lessons.plan.add")}
      description={t("lessons.plan.dialog")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="plan-editor">
        <FormAlert message={form.formError} />
        {choices.length === 0 ? <p className="alert alert-warn">{t("lessons.plan.noTeaching")}</p> : null}
        <div className="grid2">
          <SelectField
            label={t("lessons.section")}
            name="sectionId"
            value={values.sectionId}
            onChange={(e) => {
              const next = choices.find((c) => c.sectionId === e.target.value);
              set("sectionId", e.target.value);
              if (!next?.subjects.some((s) => s.id === values.subjectId)) set("subjectId", next?.subjects.length === 1 ? next.subjects[0].id : "");
              set("topicId", "");
            }}
            options={[{ value: "", label: t("lessons.pick") }, ...choices.map((c) => ({ value: c.sectionId, label: c.label }))]}
            error={errors.sectionId}
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
            options={[{ value: "", label: t("lessons.pick") }, ...subjects.map((s) => ({ value: s.id, label: s.name }))]}
            error={errors.subjectId}
            disabled={!values.sectionId}
            required
          />
        </div>
        <SelectField
          label={t("lessons.topic")}
          name="topicId"
          value={values.topicId}
          onChange={(e) => set("topicId", e.target.value)}
          options={[
            { value: "", label: topics.data && topics.data.length === 0 ? t("lessons.topic.noSyllabus") : t("lessons.topic.none") },
            ...(topics.data ?? []).map((topic) => ({
              value: topic.id,
              label: [topic.unitTitle, topic.title, monthLabel(topic.targetMonth, locale)].filter(Boolean).join(" · "),
            })),
          ]}
          hint={t("lessons.topic.hint")}
          error={errors.topicId}
          disabled={!values.subjectId}
        />
        <TextField
          label={t("lessons.plan.title")}
          name="title"
          value={values.title}
          maxLength={LESSONS_LIMITS.title}
          placeholder={values.topicId ? t("lessons.plan.titleFromTopic") : t("lessons.plan.titlePlaceholder")}
          onChange={(e) => set("title", e.target.value)}
          error={errors.title}
          required={!values.topicId}
        />
        <div className="grid2">
          <TextField
            label={t("lessons.plan.week")}
            name="weekStart"
            type="date"
            value={values.weekStart}
            min={year ? mondayOf(year.startsOn) : undefined}
            max={year?.endsOn}
            onChange={(e) => set("weekStart", e.target.value)}
            hint={monday ? t("lessons.plan.weekHint", { date: formatPlainDate(monday, locale) }) : undefined}
            error={errors.weekStart}
            required
          />
          <TextField
            label={t("lessons.plan.day")}
            name="planDate"
            type="date"
            value={values.planDate}
            min={monday || undefined}
            max={monday ? plusDays(monday, 6) : undefined}
            onChange={(e) => set("planDate", e.target.value)}
            hint={t("lessons.optional")}
            error={errors.planDate}
          />
        </div>
        <TextField
          label={t("lessons.plan.periods")}
          name="periods"
          type="number"
          inputMode="numeric"
          min={1}
          max={LESSONS_LIMITS.planPeriods}
          value={values.periods}
          onChange={(e) => set("periods", e.target.value)}
          error={errors.periods}
          required
        />
        <TextAreaField
          label={t("lessons.plan.objectives")}
          name="objectives"
          rows={3}
          maxLength={LESSONS_LIMITS.planText}
          value={values.objectives}
          onChange={(e) => set("objectives", e.target.value)}
          error={errors.objectives}
        />
        {more ? (
          <>
            <TextAreaField
              label={t("lessons.plan.method")}
              name="teachingMethod"
              rows={3}
              maxLength={LESSONS_LIMITS.planText}
              value={values.teachingMethod}
              onChange={(e) => set("teachingMethod", e.target.value)}
              error={errors.teachingMethod}
            />
            <TextAreaField
              label={t("lessons.plan.resources")}
              name="resources"
              rows={2}
              maxLength={LESSONS_LIMITS.planText}
              value={values.resources}
              onChange={(e) => set("resources", e.target.value)}
              error={errors.resources}
            />
            <TextAreaField
              label={t("lessons.plan.links")}
              name="resourceLinks"
              rows={2}
              value={values.resourceLinks}
              placeholder="https://"
              onChange={(e) => set("resourceLinks", e.target.value)}
              hint={t("lessons.plan.linksHint")}
              error={errors.resourceLinks}
            />
            <TextAreaField
              label={t("lessons.plan.homework")}
              name="homeworkIdea"
              rows={2}
              maxLength={LESSONS_LIMITS.planShortText}
              value={values.homeworkIdea}
              onChange={(e) => set("homeworkIdea", e.target.value)}
              error={errors.homeworkIdea}
            />
            <TextAreaField
              label={t("lessons.plan.assessment")}
              name="assessmentNote"
              rows={2}
              maxLength={LESSONS_LIMITS.planShortText}
              value={values.assessmentNote}
              onChange={(e) => set("assessmentNote", e.target.value)}
              error={errors.assessmentNote}
            />
          </>
        ) : (
          <button type="button" className="linkbtn self-start" onClick={() => setMore(true)}>
            {t("lessons.plan.more")}
          </button>
        )}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting || choices.length === 0}>
            {form.submitting ? t("common.saving") : t("lessons.plan.saveDraft")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
