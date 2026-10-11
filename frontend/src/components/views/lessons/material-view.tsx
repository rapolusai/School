"use client";

import { ExternalLink, Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { YearSelect } from "@/components/views/exams/exams-shared";
import { AttachmentList, FilePicker } from "@/components/views/files/attachments";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { LESSONS_LIMITS, type LessonsOptions, type LessonsYear, type MaterialRequest, type MaterialView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import { classChoices, LessonsNav, useLessonsOptions } from "./lessons-shared";

type MaterialValues = {
  classId: string;
  sectionId: string;
  subjectId: string;
  topicId: string;
  title: string;
  notes: string;
  linkUrl: string;
  publishOn: string;
};

/** Client checks matching the API's. Pure, exported for tests. */
export function validateMaterial(values: MaterialValues, year?: Pick<LessonsYear, "startsOn" | "endsOn"> | null): Problems {
  const problems: Problems = {};
  if (!values.classId) problems.classId = "lessons.v.class";
  if (!values.subjectId) problems.subjectId = "lessons.v.subject";
  if (!values.title.trim()) problems.title = "lessons.v.title";
  else if (values.title.trim().length > LESSONS_LIMITS.title) problems.title = "lessons.v.titleLong";
  if (values.notes.trim().length > LESSONS_LIMITS.materialNotes) problems.notes = "lessons.v.long5000";
  const link = values.linkUrl.trim();
  if (link && (!/^https?:\/\/\S+$/i.test(link) || link.length > LESSONS_LIMITS.link)) problems.linkUrl = "lessons.v.link";
  if (values.publishOn) {
    if (!isPlainDate(values.publishOn)) problems.publishOn = "lessons.v.date";
    else if (year && (values.publishOn < year.startsOn || values.publishOn > year.endsOn)) problems.publishOn = "lessons.v.dateInYear";
  }
  return problems;
}

/** /app/lessons/material: notes, files and links published to a class (or one section) on a date. */
export function MaterialListView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.lessonsManage);
  const [yearId, setYearId] = useState("");
  const [classId, setClassId] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [mine, setMine] = useState(false);
  const [editing, setEditing] = useState<MaterialView | "new" | null>(null);
  const [dialogKey, setDialogKey] = useState(0);
  const [deleting, setDeleting] = useState<MaterialView | null>(null);
  const options = useLessonsOptions(yearId);
  const query = { yearId: yearId || undefined, classId: classId || undefined, subjectId: subjectId || undefined, mine };
  const list = useApiData(`lessons:material:${JSON.stringify(query)}`, () => lessonsApi.material(query));
  const classes = options.data?.classes ?? [];
  const subjects = classes.find((c) => c.id === classId)?.subjects ?? [];
  const canAdd = Boolean(canManage && options.data?.yearId && options.data && classChoices(options.data).length > 0);
  const year = options.data?.years.find((y) => y.id === options.data?.yearId);

  const open = (value: MaterialView | "new") => {
    setDialogKey((k) => k + 1);
    setEditing(value);
  };

  return (
    <>
      <PageHead
        eyebrow={year?.name}
        title={t("lessons.material.title")}
        actions={
          canAdd ? (
            <button type="button" className="btn btn-primary" onClick={() => open("new")}>
              <Plus size={18} aria-hidden="true" />
              {t("lessons.material.add")}
            </button>
          ) : null
        }
      />
      <LessonsNav />
      <section className="card flex flex-col gap-3">
        <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("lessons.filters")}>
          {options.data ? (
            <YearSelect years={options.data.years} value={yearId || options.data.yearId || ""} onChange={setYearId} />
          ) : null}
          <label className="field w-full sm:w-auto sm:min-w-40">
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
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("lessons.subject")}</span>
            <select
              className="input"
              name="subjectId"
              value={subjectId}
              disabled={!classId}
              onChange={(e) => setSubjectId(e.target.value)}
            >
              <option value="">{t("lessons.allSubjects")}</option>
              {subjects.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          {canManage ? (
            <label className="check self-end">
              <input type="checkbox" name="mine" checked={mine} onChange={(e) => setMine(e.target.checked)} />
              <span>{t("lessons.material.mine")}</span>
            </label>
          ) : null}
        </form>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={4} />
        ) : list.data.length === 0 ? (
          <p className="empty">{canAdd ? t("lessons.material.emptyAdd") : t("lessons.material.empty")}</p>
        ) : (
          <ul className="flex flex-col gap-3" data-testid="material-list">
            {list.data.map((m) => (
              <li key={m.id} className="rounded-[10px] border border-line p-3" data-testid="material-item">
                <div className="flex flex-wrap items-start gap-2">
                  <div className="min-w-0 flex-1">
                    <h2 className="text-[15px]">{m.title}</h2>
                    <p className="mt-0.5 text-[12.5px] text-ink-3">
                      {[
                        m.sectionLabel ?? t("lessons.material.wholeClass", { name: m.className }),
                        m.subjectName,
                        m.topicTitle,
                        m.createdByName,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </p>
                  </div>
                  {m.published ? (
                    <Pill tone="good" dot>
                      {t("lessons.material.publishedOn", { date: formatPlainDate(m.publishOn, locale) })}
                    </Pill>
                  ) : (
                    <Pill tone="info" dot>
                      {t("lessons.material.scheduled", { date: formatPlainDate(m.publishOn, locale) })}
                    </Pill>
                  )}
                  {m.canEdit ? (
                    <span className="flex gap-1">
                      <button type="button" className="iconbtn" onClick={() => open(m)} aria-label={t("lessons.material.editNamed", { title: m.title })}>
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button type="button" className="iconbtn" onClick={() => setDeleting(m)} aria-label={t("lessons.material.deleteNamed", { title: m.title })}>
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </span>
                  ) : null}
                </div>
                <MaterialBody notes={m.notes} linkUrl={m.linkUrl} />
                <AttachmentList files={m.files} />
              </li>
            ))}
          </ul>
        )}
      </section>

      {editing && options.data ? (
        <MaterialDialog
          key={dialogKey}
          material={editing === "new" ? null : editing}
          options={options.data}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("lessons.material.saved"));
            list.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting !== null}
        title={t("lessons.material.deleteTitle", { title: deleting?.title ?? "" })}
        body={t("lessons.material.deleteBody")}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          await lessonsApi.deleteMaterial(deleting.id);
          setDeleting(null);
          toast(t("common.deleted"));
          list.reload();
        }}
      />
    </>
  );
}

/** A piece of material's notes and web link. */
export function MaterialBody({ notes, linkUrl }: { notes: string | null; linkUrl: string | null }) {
  const { t } = useI18n();
  if (!notes && !linkUrl) return null;
  return (
    <div className="mt-2 flex flex-col gap-1.5">
      {notes ? <p className="whitespace-pre-line text-[14px] text-ink-2">{notes}</p> : null}
      {linkUrl ? (
        <a className="link inline-flex min-w-0 items-center gap-1 text-[13.5px]" href={linkUrl} target="_blank" rel="noopener noreferrer">
          <ExternalLink size={15} aria-hidden="true" className="flex-none" />
          <span className="truncate">{t("lessons.material.openLink")}</span>
        </a>
      ) : null}
    </div>
  );
}

function MaterialDialog({
  material,
  options,
  onClose,
  onSaved,
}: {
  material: MaterialView | null;
  options: LessonsOptions;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const classes = classChoices(options);
  const form = useForm<MaterialValues>({
    classId: material?.classId ?? "",
    sectionId: material?.sectionId ?? "",
    subjectId: material?.subjectId ?? "",
    topicId: material?.topicId ?? "",
    title: material?.title ?? "",
    notes: material?.notes ?? "",
    linkUrl: material?.linkUrl ?? "",
    publishOn: material?.publishOn ?? today,
  });
  const { values, set, errors } = form;
  const [saved, setSaved] = useState<MaterialView | null>(material);
  const [files, setFiles] = useState<File[]>([]);
  const [fileError, setFileError] = useState<string | undefined>();
  const year = options.years.find((y) => y.id === options.yearId) ?? null;
  const chosen = classes.find((c) => c.id === values.classId);
  const topics = useApiData(
    values.classId && values.subjectId ? `lessons:topics:${options.yearId}:${values.classId}:${values.subjectId}` : null,
    () => lessonsApi.topics(values.classId, values.subjectId, options.yearId),
  );
  const room = LESSONS_LIMITS.materialFiles - (saved?.files.length ?? 0);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateMaterial(values, year), t, event.currentTarget)) return;
    const body: MaterialRequest = {
      academicYearId: options.yearId,
      classId: values.classId,
      sectionId: values.sectionId || null,
      subjectId: values.subjectId,
      topicId: values.topicId || null,
      title: values.title.trim(),
      notes: values.notes.trim() || null,
      linkUrl: values.linkUrl.trim() || null,
      publishOn: values.publishOn || null,
    };
    let detail: MaterialView | undefined;
    const ok = await form.submit(t, async () => {
      detail = saved ? await lessonsApi.updateMaterial(saved.id, body) : await lessonsApi.createMaterial(body);
    });
    if (!ok || !detail) return;
    setSaved(detail);
    // Upload the new files one by one; stop at the first the API refuses and keep the rest.
    for (let i = 0; i < files.length; i++) {
      try {
        detail = await lessonsApi.addMaterialFile(detail.id, files[i]);
        setSaved(detail);
      } catch (caught) {
        const error = toApiError(caught);
        setFiles(files.slice(i));
        setFileError(t("lessons.material.uploadFailed", { name: files[i].name, reason: error.errors?.file ?? errorMessage(error, t) }));
        return;
      }
    }
    onSaved();
  };

  const removeFile = async (fileId: string) => {
    if (!saved) return;
    setFileError(undefined);
    try {
      setSaved(await lessonsApi.removeMaterialFile(saved.id, fileId));
    } catch (caught) {
      setFileError(errorMessage(toApiError(caught), t));
    }
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={material ? t("lessons.material.edit") : t("lessons.material.add")}
      description={t("lessons.material.dialog")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="material-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <SelectField
            label={t("lessons.class")}
            name="classId"
            value={values.classId}
            onChange={(e) => {
              set("classId", e.target.value);
              set("sectionId", "");
              set("subjectId", "");
              set("topicId", "");
            }}
            options={[{ value: "", label: t("lessons.pick") }, ...classes.map((c) => ({ value: c.id, label: c.name }))]}
            error={errors.classId}
            required
            data-autofocus
          />
          <SelectField
            label={t("lessons.section")}
            name="sectionId"
            value={values.sectionId}
            onChange={(e) => set("sectionId", e.target.value)}
            options={[
              { value: "", label: t("lessons.material.allSections") },
              ...(chosen?.sections ?? []).map((s) => ({ value: s.id, label: s.label })),
            ]}
            error={errors.sectionId}
            disabled={!values.classId}
          />
        </div>
        <div className="grid2">
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
        </div>
        <TextField
          label={t("lessons.material.titleLabel")}
          name="title"
          value={values.title}
          maxLength={LESSONS_LIMITS.title}
          onChange={(e) => set("title", e.target.value)}
          error={errors.title}
          required
        />
        <TextAreaField
          label={t("lessons.material.notes")}
          name="notes"
          rows={4}
          maxLength={LESSONS_LIMITS.materialNotes}
          value={values.notes}
          onChange={(e) => set("notes", e.target.value)}
          error={errors.notes}
        />
        <div className="grid2">
          <TextField
            label={t("lessons.material.link")}
            name="linkUrl"
            type="url"
            placeholder="https://"
            value={values.linkUrl}
            maxLength={LESSONS_LIMITS.link}
            onChange={(e) => set("linkUrl", e.target.value)}
            error={errors.linkUrl}
          />
          <TextField
            label={t("lessons.material.publishOn")}
            name="publishOn"
            type="date"
            min={year?.startsOn}
            max={year?.endsOn}
            value={values.publishOn}
            onChange={(e) => set("publishOn", e.target.value)}
            hint={t("lessons.material.publishHint")}
            error={errors.publishOn}
          />
        </div>
        <AttachmentList files={saved?.files ?? []} onRemove={saved ? (file) => void removeFile(file.id) : undefined} />
        <FilePicker
          label={t("lessons.material.files")}
          files={files}
          onChange={(next) => {
            setFileError(undefined);
            setFiles(next);
          }}
          max={Math.max(0, room)}
          error={fileError}
          name="files"
        />
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
