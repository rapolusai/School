"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { AttachmentList, FilePicker } from "@/components/views/files/attachments";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { homeworkApi } from "@/lib/homework-api";
import { useI18n } from "@/lib/i18n";
import type { FileRef, HomeworkDetail, HomeworkOptions } from "@/lib/types";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";

export type HomeworkValues = {
  sectionIds: string[];
  subjectId: string;
  title: string;
  instructions: string;
  assignedOn: string;
  dueOn: string;
  onlineSubmission: boolean;
};

export const TITLE_MAX = 200;
export const INSTRUCTIONS_MAX = 5000;

/** Subjects the caller may set in every chosen section (all of their subjects when none is chosen). Pure. */
export function subjectsFor(options: HomeworkOptions, sectionIds: string[]): { id: string; name: string }[] {
  const chosen = options.sections.filter((s) => sectionIds.includes(s.id));
  if (chosen.length === 0) {
    const all = new Map<string, string>();
    for (const s of options.sections) for (const subject of s.subjects) all.set(subject.id, subject.name);
    return [...all].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name));
  }
  return chosen[0].subjects
    .filter((subject) => chosen.every((s) => s.subjects.some((x) => x.id === subject.id)))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** The same checks the API makes (docs/api/phase-1-timetable-homework.md). Pure. */
export function validateHomework(values: HomeworkValues, options: HomeworkOptions, editing: boolean): Problems {
  const problems: Problems = {};
  if (values.sectionIds.length === 0) problems.sectionIds = "homework.v.sections";
  if (!values.subjectId) problems.subjectId = "validation.choose";
  else if (values.sectionIds.length > 0 && !subjectsFor(options, values.sectionIds).some((s) => s.id === values.subjectId)) {
    problems.subjectId = "homework.v.subject";
  }
  if (!values.title.trim()) problems.title = "validation.required";
  else if (values.title.trim().length > TITLE_MAX) problems.title = "validation.tooLong";
  if (values.instructions.length > INSTRUCTIONS_MAX) problems.instructions = "validation.tooLong";
  if (!editing) {
    if (!isPlainDate(values.assignedOn)) problems.assignedOn = "validation.date";
    else if (values.assignedOn > options.today) problems.assignedOn = "homework.v.assignedFuture";
  }
  if (!isPlainDate(values.dueOn)) problems.dueOn = "validation.date";
  else if (values.dueOn < options.today) problems.dueOn = "homework.v.duePast";
  else if (isPlainDate(values.assignedOn) && values.dueOn < values.assignedOn) problems.dueOn = "homework.v.dueBeforeAssigned";
  else if (options.academicYear && values.dueOn > options.academicYear.endsOn) problems.dueOn = "homework.v.dueAfterYear";
  return problems;
}

function initialValues(options: HomeworkOptions, homework?: HomeworkDetail | null): HomeworkValues {
  if (homework) {
    return {
      sectionIds: homework.sections.map((s) => s.id),
      subjectId: homework.subjectId,
      title: homework.title,
      instructions: homework.instructions ?? "",
      assignedOn: homework.assignedOn,
      dueOn: homework.dueOn,
      onlineSubmission: homework.onlineSubmission,
    };
  }
  const only = options.sections.length === 1 ? [options.sections[0].id] : [];
  const subjects = subjectsFor(options, only);
  return {
    sectionIds: only,
    subjectId: only.length && subjects.length === 1 ? subjects[0].id : "",
    title: "",
    instructions: "",
    assignedOn: options.today,
    dueOn: "",
    onlineSubmission: true,
  };
}

/**
 * Sets or changes homework: sections, subject, title, plain-text instructions, dates, whether
 * students submit online, and up to five files (checked before upload). Files are uploaded after
 * the homework is saved; a refused upload keeps the dialog open on the saved homework.
 */
export function HomeworkFormDialog({
  open,
  options,
  homework,
  onClose,
  onSaved,
}: {
  open: boolean;
  options: HomeworkOptions;
  homework?: HomeworkDetail | null;
  onClose: () => void;
  onSaved: (homework: HomeworkDetail) => void;
}) {
  const { t } = useI18n();
  const [saved, setSaved] = useState<HomeworkDetail | null>(homework ?? null);
  const [attachments, setAttachments] = useState<FileRef[]>(homework?.attachments ?? []);
  const [files, setFiles] = useState<File[]>([]);
  const [fileError, setFileError] = useState<string | undefined>();
  const form = useForm<HomeworkValues>(initialValues(options, homework));
  const { values, set, errors } = form;
  const editing = saved !== null;
  const locked = (saved?.counts.submitted ?? 0) > 0;
  const subjects = subjectsFor(options, values.sectionIds);
  const room = Math.max(0, options.maxAttachments - attachments.length);

  const close = () => {
    form.reset(initialValues(options, homework));
    setFiles([]);
    setFileError(undefined);
    onClose();
  };

  const toggleSection = (id: string, on: boolean) => {
    const next = on ? [...values.sectionIds, id] : values.sectionIds.filter((s) => s !== id);
    set("sectionIds", next);
    if (values.subjectId && next.length > 0 && !subjectsFor(options, next).some((s) => s.id === values.subjectId)) {
      set("subjectId", "");
    }
  };

  const removeAttachment = async (file: FileRef) => {
    if (!saved) return;
    setFileError(undefined);
    try {
      await homeworkApi.removeAttachment(saved.id, file.id);
      setAttachments((prev) => prev.filter((f) => f.id !== file.id));
    } catch (caught) {
      setFileError(errorMessage(toApiError(caught), t));
    }
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateHomework(values, options, editing), t, event.currentTarget)) return;
    const body = {
      sectionIds: values.sectionIds,
      subjectId: values.subjectId,
      title: values.title.trim(),
      instructions: values.instructions.trim() || null,
      assignedOn: values.assignedOn || null,
      dueOn: values.dueOn,
      onlineSubmission: values.onlineSubmission,
    };
    let detail: HomeworkDetail | undefined;
    const ok = await form.submit(t, async () => {
      detail = saved ? await homeworkApi.update(saved.id, body) : await homeworkApi.create(body);
    });
    if (!ok || !detail) return;
    setSaved(detail);
    // Upload the new files one by one; stop at the first the API refuses and keep the rest.
    const uploaded: FileRef[] = [];
    for (let i = 0; i < files.length; i++) {
      try {
        uploaded.push(await homeworkApi.addAttachment(detail.id, files[i]));
      } catch (caught) {
        const error = toApiError(caught);
        setAttachments([...detail.attachments, ...uploaded]);
        setFiles(files.slice(i));
        setFileError(
          t("homework.form.uploadFailed", {
            name: files[i].name,
            reason: error.errors?.file ?? errorMessage(error, t),
          }),
        );
        return;
      }
    }
    setFiles([]);
    setFileError(undefined);
    onSaved({ ...detail, attachments: [...detail.attachments, ...uploaded] });
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={editing ? t("homework.form.editTitle") : t("homework.form.title")}
      description={t("homework.form.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <fieldset className="fieldset">
          <legend>{t("homework.form.sections")}</legend>
          <div className="hw-sections">
            {options.sections.map((s) => {
              const keep = locked && (homework?.sections.some((x) => x.id === s.id) ?? false);
              return (
                <label key={s.id} className="check">
                  <input
                    type="checkbox"
                    name="sectionIds"
                    value={s.id}
                    checked={values.sectionIds.includes(s.id)}
                    disabled={keep}
                    onChange={(e) => toggleSection(s.id, e.target.checked)}
                  />
                  {s.label}
                </label>
              );
            })}
          </div>
          {locked ? <p className="field-hint">{t("homework.form.lockedSections")}</p> : null}
          {errors.sectionIds ? <p className="field-error">{errors.sectionIds}</p> : null}
        </fieldset>
        <SelectField
          label={t("homework.subject")}
          name="subjectId"
          value={values.subjectId}
          onChange={(e) => set("subjectId", e.target.value)}
          error={errors.subjectId}
          disabled={locked}
          hint={locked ? t("homework.form.lockedSubject") : undefined}
          options={[
            { value: "", label: subjects.length ? t("common.choose") : t("homework.form.noSubjects") },
            ...subjects.map((s) => ({ value: s.id, label: s.name })),
          ]}
        />
        <TextField
          label={t("homework.form.titleField")}
          name="title"
          value={values.title}
          maxLength={TITLE_MAX}
          onChange={(e) => set("title", e.target.value)}
          error={errors.title}
          required
        />
        <TextAreaField
          label={t("homework.form.instructions")}
          name="instructions"
          rows={5}
          value={values.instructions}
          maxLength={INSTRUCTIONS_MAX}
          hint={t("homework.form.instructionsHint")}
          onChange={(e) => set("instructions", e.target.value)}
          error={errors.instructions}
        />
        <div className="grid2">
          <TextField
            label={t("homework.assignedOn")}
            name="assignedOn"
            type="date"
            value={values.assignedOn}
            max={options.today}
            min={options.academicYear?.startsOn}
            disabled={editing}
            onChange={(e) => set("assignedOn", e.target.value)}
            error={errors.assignedOn}
          />
          <TextField
            label={t("homework.dueOn")}
            name="dueOn"
            type="date"
            value={values.dueOn}
            min={options.today}
            max={options.academicYear?.endsOn}
            onChange={(e) => set("dueOn", e.target.value)}
            error={errors.dueOn}
            required
          />
        </div>
        <label className="check">
          <input
            type="checkbox"
            name="onlineSubmission"
            checked={values.onlineSubmission}
            onChange={(e) => set("onlineSubmission", e.target.checked)}
          />
          <span>
            <b className="block font-semibold">{t("homework.form.online")}</b>
            <span className="text-[12.5px] text-ink-3">{t("homework.form.onlineHint")}</span>
          </span>
        </label>
        <AttachmentList files={attachments} onRemove={editing ? removeAttachment : undefined} />
        <FilePicker
          label={t("homework.form.attachments")}
          files={files}
          onChange={(next) => {
            setFileError(undefined);
            setFiles(next);
          }}
          max={room}
          maxBytes={options.maxFileBytes}
          error={fileError}
          name="attachments"
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="homework-save">
            {form.submitting ? t("common.saving") : editing ? t("common.save") : t("homework.form.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
