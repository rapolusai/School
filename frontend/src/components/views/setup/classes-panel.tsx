"use client";

import { BookOpen, Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { plural, useI18n } from "@/lib/i18n";
import type { ClassView, SectionView, Subject, TeacherRef } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isWholeNumberInRange } from "@/lib/validation";

type ClassValues = { name: string; displayOrder: string };
type SectionValues = { name: string; capacity: string; classTeacherId: string };

export function validateClass(values: ClassValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 40) problems.name = "validation.tooLong";
  if (values.displayOrder.trim() && !isWholeNumberInRange(values.displayOrder, 0, 999)) {
    problems.displayOrder = "setup.classes.orderRange";
  }
  return problems;
}

export function validateSection(values: SectionValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 20) problems.name = "validation.tooLong";
  if (values.capacity.trim() && !isWholeNumberInRange(values.capacity, 1, 500)) {
    problems.capacity = "setup.sections.capacityRange";
  }
  return problems;
}

/** Seats used, e.g. "32 of 40" or "32 students" when there is no limit. */
function SeatMeter({ section }: { section: SectionView }) {
  const { t } = useI18n();
  if (section.capacity === null) {
    return (
      <span className="mt-1 block text-[12.5px] text-ink-3">
        {plural(t, "setup.sections.students", section.studentCount)}
      </span>
    );
  }
  const ratio = Math.min(1, section.studentCount / section.capacity);
  return (
    <span className="mt-1 flex max-w-[180px] flex-col gap-1">
      <span className="text-[12.5px] text-ink-3 num">
        {t("setup.sections.seats", { used: section.studentCount, capacity: section.capacity })}
      </span>
      <span className={`meter${ratio >= 1 ? " meter-full" : ""}`} aria-hidden="true">
        <span style={{ width: `${Math.round(ratio * 100)}%` }} />
      </span>
    </span>
  );
}

function ClassDialog({
  open,
  schoolClass,
  onClose,
  onSaved,
}: {
  open: boolean;
  schoolClass: ClassView | null;
  onClose: () => void;
  onSaved: (saved: ClassView) => void;
}) {
  const { t } = useI18n();
  const initial: ClassValues = {
    name: schoolClass?.name ?? "",
    displayOrder: schoolClass ? String(schoolClass.displayOrder) : "",
  };
  const form = useForm<ClassValues>(initial);
  const close = () => {
    form.reset(initial);
    onClose();
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateClass(form.values), t, event.currentTarget)) return;
    const body = {
      name: form.values.name.trim(),
      displayOrder: form.values.displayOrder.trim() ? Number(form.values.displayOrder) : null,
    };
    let saved: ClassView | undefined;
    const ok = await form.submit(t, async () => {
      saved = schoolClass ? await api.updateClass(schoolClass.id, body) : await api.createClass(body);
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog
      open={open}
      onClose={close}
      title={schoolClass ? t("setup.classes.edit") : t("setup.classes.add")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("setup.classes.name")}
          name="name"
          value={form.values.name}
          maxLength={40}
          placeholder={t("setup.classes.name.placeholder")}
          onChange={(e) => form.set("name", e.target.value)}
          error={form.errors.name}
          required
          data-autofocus
        />
        <TextField
          label={t("setup.classes.order")}
          name="displayOrder"
          inputMode="numeric"
          value={form.values.displayOrder}
          hint={t("setup.classes.order.hint")}
          onChange={(e) => form.set("displayOrder", e.target.value)}
          error={form.errors.displayOrder}
        />
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

function SectionDialog({
  open,
  schoolClass,
  section,
  teachers,
  onClose,
  onSaved,
}: {
  open: boolean;
  schoolClass: ClassView | null;
  section: SectionView | null;
  teachers: TeacherRef[];
  onClose: () => void;
  onSaved: (saved: SectionView) => void;
}) {
  const { t } = useI18n();
  const initial: SectionValues = {
    name: section?.name ?? "",
    capacity: section?.capacity == null ? "" : String(section.capacity),
    classTeacherId: section?.classTeacher?.id ?? "",
  };
  const form = useForm<SectionValues>(initial);
  const close = () => {
    form.reset(initial);
    onClose();
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!schoolClass) return;
    if (!form.check(validateSection(form.values), t, event.currentTarget)) return;
    const body = {
      name: form.values.name.trim(),
      capacity: form.values.capacity.trim() ? Number(form.values.capacity) : null,
      classTeacherId: form.values.classTeacherId || null,
    };
    let saved: SectionView | undefined;
    const ok = await form.submit(t, async () => {
      saved = section ? await api.updateSection(section.id, body) : await api.createSection(schoolClass.id, body);
    });
    if (ok && saved) onSaved(saved);
  };
  const teacherOptions = [
    { value: "", label: t("setup.sections.noTeacher") },
    ...teachers.map((teacher) => ({ value: teacher.id, label: teacher.name })),
  ];
  if (section?.classTeacher && !teachers.some((teacher) => teacher.id === section.classTeacher?.id)) {
    teacherOptions.push({ value: section.classTeacher.id, label: section.classTeacher.name });
  }
  return (
    <Dialog
      open={open}
      onClose={close}
      title={
        section
          ? t("setup.sections.editNamed", { name: `${schoolClass?.name ?? ""} ${section.name}` })
          : t("setup.sections.addTo", { name: schoolClass?.name ?? "" })
      }
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("setup.sections.name")}
            name="name"
            value={form.values.name}
            maxLength={20}
            placeholder={t("setup.sections.name.placeholder")}
            onChange={(e) => form.set("name", e.target.value)}
            error={form.errors.name}
            required
            data-autofocus
          />
          <TextField
            label={t("setup.sections.capacity")}
            name="capacity"
            inputMode="numeric"
            value={form.values.capacity}
            hint={t("setup.sections.capacity.hint")}
            onChange={(e) => form.set("capacity", e.target.value)}
            error={form.errors.capacity}
          />
        </div>
        <SelectField
          label={t("setup.sections.classTeacher")}
          name="classTeacherId"
          value={form.values.classTeacherId}
          options={teacherOptions}
          hint={teachers.length === 0 ? t("setup.sections.noTeachers") : undefined}
          onChange={(e) => form.set("classTeacherId", e.target.value)}
          error={form.errors.classTeacherId}
        />
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

function ClassSubjectsDialog({
  open,
  schoolClass,
  subjects,
  onClose,
  onSaved,
}: {
  open: boolean;
  schoolClass: ClassView | null;
  subjects: Subject[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const initial = { subjectIds: schoolClass?.subjects.map((s) => s.id) ?? [] };
  const form = useForm<{ subjectIds: string[] }>(initial);
  const close = () => {
    form.reset(initial);
    onClose();
  };
  const toggle = (subjectId: string, on: boolean) =>
    form.set(
      "subjectIds",
      on ? [...form.values.subjectIds, subjectId] : form.values.subjectIds.filter((s) => s !== subjectId),
    );
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!schoolClass) return;
    const ok = await form.submit(t, async () => {
      await api.setClassSubjects(schoolClass.id, form.values.subjectIds);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("setup.classSubjects.title", { name: schoolClass?.name ?? "" })}
      description={t("setup.classSubjects.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError ?? form.errors.subjectIds ?? null} />
        {subjects.length === 0 ? (
          <p className="field-hint">{t("setup.classSubjects.none")}</p>
        ) : (
          <fieldset className="m-0 grid grid-cols-1 gap-2 border-0 p-0 sm:grid-cols-2">
            <legend className="sr-only">{t("setup.subjects.title")}</legend>
            {subjects.map((subject) => (
              <label key={subject.id} className="check">
                <input
                  type="checkbox"
                  name="subjectIds"
                  value={subject.id}
                  checked={form.values.subjectIds.includes(subject.id)}
                  onChange={(e) => toggle(subject.id, e.target.checked)}
                />
                <span>
                  {subject.name}
                  {subject.code ? <span className="mono ml-1.5 text-ink-3">{subject.code}</span> : null}
                </span>
              </label>
            ))}
          </fieldset>
        )}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting || subjects.length === 0}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type Editing =
  | { kind: "class"; schoolClass: ClassView | null }
  | { kind: "section"; schoolClass: ClassView; section: SectionView | null }
  | { kind: "subjects"; schoolClass: ClassView }
  | null;

type Deleting = { kind: "class"; schoolClass: ClassView } | { kind: "section"; section: SectionView } | null;

export function ClassesPanel({ classes, canManage }: { classes: ApiData<ClassView[]>; canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [editing, setEditing] = useState<Editing>(null);
  const [deleting, setDeleting] = useState<Deleting>(null);
  const teachers = useApiData(canManage ? "academics:teachers" : null, api.listTeachers);
  const subjects = useApiData(canManage ? "academics:subjects" : null, api.listSubjects);
  const list = classes.data ?? [];

  const editingKey =
    editing === null
      ? "closed"
      : editing.kind === "section"
        ? `section:${editing.schoolClass.id}:${editing.section?.id ?? "new"}`
        : `${editing.kind}:${editing.schoolClass?.id ?? "new"}`;

  const saved = (message: string) => {
    setEditing(null);
    classes.reload();
    subjects.reload();
    toast(message);
  };

  return (
    <section className="card" aria-labelledby="classes-title">
      <div className="card-head">
        <div>
          <h2 id="classes-title">{t("setup.classes.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("setup.classes.sub")}</p>
        </div>
        {canManage ? (
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => setEditing({ kind: "class", schoolClass: null })}
          >
            <Plus size={18} aria-hidden="true" />
            {t("setup.classes.add")}
          </button>
        ) : null}
      </div>

      {classes.error && !classes.data ? (
        <ErrorState error={classes.error} onRetry={classes.reload} />
      ) : classes.loading && !classes.data ? (
        <LoadingRows rows={3} />
      ) : list.length === 0 ? (
        <p className="empty">{canManage ? t("setup.classes.empty") : t("setup.classes.emptyReadOnly")}</p>
      ) : (
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-2" data-testid="classes-list">
          {list.map((schoolClass) => (
            <article key={schoolClass.id} className="rounded-[10px] border border-line p-3.5" aria-label={schoolClass.name}>
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <h3 className="font-display text-base font-bold">{schoolClass.name}</h3>
                  <p className="text-[12.5px] text-ink-3">
                    {plural(t, "setup.classes.sectionCount", schoolClass.sections.length)} ·{" "}
                    {plural(t, "setup.classes.subjectCount", schoolClass.subjects.length)}
                  </p>
                </div>
                {canManage ? (
                  <div className="flex flex-none gap-0.5">
                    <button
                      type="button"
                      className="iconbtn"
                      aria-label={t("setup.classSubjects.titleShort", { name: schoolClass.name })}
                      onClick={() => setEditing({ kind: "subjects", schoolClass })}
                    >
                      <BookOpen size={17} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="iconbtn"
                      aria-label={t("setup.classes.editNamed", { name: schoolClass.name })}
                      onClick={() => setEditing({ kind: "class", schoolClass })}
                    >
                      <Pencil size={17} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="iconbtn"
                      aria-label={t("setup.classes.deleteNamed", { name: schoolClass.name })}
                      onClick={() => setDeleting({ kind: "class", schoolClass })}
                    >
                      <Trash2 size={17} aria-hidden="true" />
                    </button>
                  </div>
                ) : null}
              </div>

              {schoolClass.sections.length === 0 ? (
                <p className="mt-2 text-[13px] text-ink-3">{t("setup.sections.empty")}</p>
              ) : (
                <ul className="mt-2 flex flex-col">
                  {schoolClass.sections.map((section) => (
                    <li key={section.id} className="flex items-center gap-3 border-t border-line py-2">
                      <span className="avatar avatar-school" aria-hidden="true">
                        {section.name}
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="font-semibold leading-tight">
                          {t("setup.sections.label", { name: section.name })}
                        </p>
                        <p className="truncate text-[12.5px] text-ink-3">
                          {section.classTeacher
                            ? t("setup.sections.teacher", { name: section.classTeacher.name })
                            : t("setup.sections.noTeacherShort")}
                        </p>
                        <SeatMeter section={section} />
                      </div>
                      {canManage ? (
                        <div className="flex flex-none gap-0.5">
                          <button
                            type="button"
                            className="iconbtn"
                            aria-label={t("setup.sections.editNamed", { name: `${schoolClass.name} ${section.name}` })}
                            onClick={() => setEditing({ kind: "section", schoolClass, section })}
                          >
                            <Pencil size={16} aria-hidden="true" />
                          </button>
                          <button
                            type="button"
                            className="iconbtn"
                            aria-label={t("setup.sections.deleteNamed", { name: `${schoolClass.name} ${section.name}` })}
                            onClick={() => setDeleting({ kind: "section", section })}
                          >
                            <Trash2 size={16} aria-hidden="true" />
                          </button>
                        </div>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}

              {schoolClass.subjects.length ? (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {schoolClass.subjects.map((subject) => (
                    <span key={subject.id} className="chip">
                      {subject.name}
                    </span>
                  ))}
                </div>
              ) : null}

              {canManage ? (
                <button
                  type="button"
                  className="btn btn-sm mt-3"
                  onClick={() => setEditing({ kind: "section", schoolClass, section: null })}
                >
                  <Plus size={16} aria-hidden="true" />
                  {t("setup.sections.add")}
                </button>
              ) : null}
            </article>
          ))}
        </div>
      )}

      {canManage ? (
        <>
          <ClassDialog
            key={`class-${editingKey}`}
            open={editing?.kind === "class"}
            schoolClass={editing?.kind === "class" ? editing.schoolClass : null}
            onClose={() => setEditing(null)}
            onSaved={(c) => saved(t("setup.classes.saved", { name: c.name }))}
          />
          <SectionDialog
            key={`section-${editingKey}`}
            open={editing?.kind === "section"}
            schoolClass={editing?.kind === "section" ? editing.schoolClass : null}
            section={editing?.kind === "section" ? editing.section : null}
            teachers={teachers.data ?? []}
            onClose={() => setEditing(null)}
            onSaved={(s) => saved(t("setup.sections.saved", { name: `${s.className} ${s.name}` }))}
          />
          <ClassSubjectsDialog
            key={`subjects-${editingKey}`}
            open={editing?.kind === "subjects"}
            schoolClass={editing?.kind === "subjects" ? editing.schoolClass : null}
            subjects={subjects.data ?? []}
            onClose={() => setEditing(null)}
            onSaved={() =>
              saved(
                t("setup.classSubjects.saved", {
                  name: editing?.kind === "subjects" ? editing.schoolClass.name : "",
                }),
              )
            }
          />
          <ConfirmDialog
            open={deleting !== null}
            title={
              deleting?.kind === "class"
                ? t("setup.classes.deleteTitle", { name: deleting.schoolClass.name })
                : deleting?.kind === "section"
                  ? t("setup.sections.deleteTitle", { name: `${deleting.section.className} ${deleting.section.name}` })
                  : ""
            }
            body={deleting?.kind === "class" ? t("setup.classes.deleteBody") : t("setup.sections.deleteBody")}
            confirmLabel={t("common.delete")}
            onClose={() => setDeleting(null)}
            onConfirm={async () => {
              if (!deleting) return;
              if (deleting.kind === "class") await api.deleteClass(deleting.schoolClass.id);
              else await api.deleteSection(deleting.section.id);
              setDeleting(null);
              classes.reload();
              toast(t("common.deleted"));
            }}
          />
        </>
      ) : null}
    </section>
  );
}
