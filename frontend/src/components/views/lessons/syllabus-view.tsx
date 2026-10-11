"use client";

import { ArrowRight, Copy, FileUp, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { YearSelect } from "@/components/views/exams/exams-shared";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { lessonsApi } from "@/lib/lessons-api";
import type { LessonsOptions, SyllabusPreviewRow, SyllabusRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { CsvImportDialog } from "./csv-import-dialog";
import { classChoices, LessonsNav, monthLabel, useLessonsOptions } from "./lessons-shared";

/** Who may add or change a syllabus: approvers (any) and teachers (subjects they teach). */
function canEditAny(options: LessonsOptions | undefined): boolean {
  return Boolean(options && (options.canApprove || options.canManage) && options.yearId);
}

/** /app/lessons/syllabus: each class and subject's syllabus for the year. */
export function SyllabusListView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [yearId, setYearId] = useState("");
  const options = useLessonsOptions(yearId);
  const chosenYear = yearId || options.data?.yearId || "";
  const list = useApiData(`lessons:syllabi:${yearId}`, () => lessonsApi.syllabi(yearId || null));
  const [dialog, setDialog] = useState<"new" | "copy" | "import" | null>(null);
  const editable = canEditAny(options.data);
  const year = options.data?.years.find((y) => y.id === chosenYear);

  return (
    <>
      <PageHead
        eyebrow={year?.name}
        title={t("lessons.syllabus.title")}
        actions={
          editable ? (
            <>
              <button type="button" className="btn" onClick={() => setDialog("import")}>
                <FileUp size={18} aria-hidden="true" />
                {t("lessons.syllabus.import")}
              </button>
              {options.data && options.data.years.length > 1 ? (
                <button type="button" className="btn" onClick={() => setDialog("copy")}>
                  <Copy size={18} aria-hidden="true" />
                  {t("lessons.syllabus.copy")}
                </button>
              ) : null}
              <button type="button" className="btn btn-primary" onClick={() => setDialog("new")}>
                <Plus size={18} aria-hidden="true" />
                {t("lessons.syllabus.add")}
              </button>
            </>
          ) : null
        }
      />
      <LessonsNav />
      {options.data && options.data.years.length > 1 ? (
        <form method="post" className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={t("lessons.filters")}>
          <YearSelect years={options.data.years} value={chosenYear} onChange={setYearId} />
        </form>
      ) : null}
      <section className="card flex flex-col gap-3" data-testid="syllabus-list">
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={5} />
        ) : options.data && !options.data.yearId ? (
          <p className="empty">{t("lessons.noYear")}</p>
        ) : list.data.length === 0 ? (
          <p className="empty">{editable ? t("lessons.syllabus.emptyEdit") : t("lessons.syllabus.empty")}</p>
        ) : (
          <ul className="rowcards">
            {list.data.map((s) => (
              <li key={s.id}>
                <Link href={`/app/lessons/syllabus/${encodeURIComponent(s.id)}`} className="rowcard" data-testid="syllabus-row">
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">
                      {s.className} · {s.subjectName}
                    </b>
                    <span className="block text-[12.5px] text-ink-3">
                      {[
                        plural(t, "lessons.syllabus.units", s.units),
                        plural(t, "lessons.syllabus.topics", s.topics),
                        plural(t, "lessons.plan.periodsCount", s.plannedPeriods),
                        t("lessons.updatedBy", {
                          date: formatDateTime(s.updatedAt, locale),
                          name: s.updatedByName ?? "—",
                        }),
                      ].join(" · ")}
                    </span>
                  </span>
                  <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      {dialog === "new" && options.data ? (
        <NewSyllabusDialog options={options.data} existing={list.data ?? []} onClose={() => setDialog(null)} />
      ) : null}
      {dialog === "copy" && options.data ? (
        <CopyDialog
          options={options.data}
          toYearId={chosenYear}
          onClose={() => setDialog(null)}
          onCopied={(copied, skipped) => {
            setDialog(null);
            toast(t("lessons.syllabus.copied", { copied, skipped }));
            list.reload();
          }}
        />
      ) : null}
      {dialog === "import" ? (
        <CsvImportDialog<SyllabusPreviewRow>
          title={t("lessons.syllabus.import")}
          description={t("lessons.syllabus.importHelp", { year: year?.name ?? "" })}
          columns="class, subject, unit, topic, periods, target_month"
          check={(csv) => lessonsApi.importSyllabi(csv, true, chosenYear || null)}
          commit={(csv) => lessonsApi.importSyllabi(csv, false, chosenYear || null)}
          previewHead={[
            t("lessons.class"),
            t("lessons.subject"),
            t("lessons.unit"),
            t("lessons.topic"),
            t("lessons.plan.periods"),
            t("lessons.syllabus.month"),
          ]}
          previewCells={(row) => [
            row.className,
            row.subjectName,
            row.unit,
            row.topic,
            String(row.plannedPeriods),
            monthLabel(row.targetMonth, locale),
          ]}
          onClose={() => setDialog(null)}
          onImported={(result) => {
            setDialog(null);
            toast(plural(t, "lessons.syllabus.imported", result.validRows));
            list.reload();
          }}
        />
      ) : null}
    </>
  );
}

/** Choosing the class and subject of a new syllabus, then opening the editor. */
function NewSyllabusDialog({
  options,
  existing,
  onClose,
}: {
  options: LessonsOptions;
  existing: SyllabusRow[];
  onClose: () => void;
}) {
  const { t } = useI18n();
  const router = useRouter();
  const classes = options.canApprove ? options.classes.filter((c) => c.subjects.length > 0) : classChoices(options);
  const form = useForm({ classId: "", subjectId: "" });
  const { values, set, errors } = form;
  const chosen = classes.find((c) => c.id === values.classId);
  const taken = new Set(existing.filter((s) => s.classId === values.classId).map((s) => s.subjectId));
  const subjects = (chosen?.subjects ?? []).filter((s) => !taken.has(s.id));

  const onSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!values.classId) problems.classId = "lessons.v.class";
    if (!values.subjectId) problems.subjectId = "lessons.v.subject";
    if (!form.check(problems, t, event.currentTarget)) return;
    const query = new URLSearchParams({ year: options.yearId ?? "", class: values.classId, subject: values.subjectId });
    router.push(`/app/lessons/syllabus/new?${query.toString()}`);
  };

  return (
    <Dialog open onClose={onClose} title={t("lessons.syllabus.add")} description={t("lessons.syllabus.addHelp")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <SelectField
          label={t("lessons.class")}
          name="classId"
          value={values.classId}
          onChange={(e) => {
            set("classId", e.target.value);
            set("subjectId", "");
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
          onChange={(e) => set("subjectId", e.target.value)}
          options={[{ value: "", label: t("lessons.pick") }, ...subjects.map((s) => ({ value: s.id, label: s.name }))]}
          hint={values.classId && subjects.length === 0 ? t("lessons.syllabus.allDone") : undefined}
          error={errors.subjectId}
          disabled={!values.classId}
          required
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary">
            {t("common.continue")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** Copying another year's syllabi into this year (all classes, or chosen ones); existing ones are kept. */
function CopyDialog({
  options,
  toYearId,
  onClose,
  onCopied,
}: {
  options: LessonsOptions;
  toYearId: string;
  onClose: () => void;
  onCopied: (copied: number, skipped: number) => void;
}) {
  const { t } = useI18n();
  const others = options.years.filter((y) => y.id !== toYearId);
  const target = options.years.find((y) => y.id === toYearId);
  const earlier = others.filter((y) => target && y.startsOn < target.startsOn);
  const form = useForm({ fromYearId: (earlier[earlier.length - 1] ?? earlier[0] ?? others[0])?.id ?? "", classIds: [] as string[] });
  const { values, set, errors } = form;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(values.fromYearId ? {} : { fromYearId: "lessons.v.year" }, t, event.currentTarget)) return;
    await form.submit(t, async () => {
      const result = await lessonsApi.copySyllabi({
        fromYearId: values.fromYearId,
        toYearId,
        classIds: values.classIds.length ? values.classIds : null,
      });
      onCopied(result.copied, result.skipped);
    });
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={t("lessons.syllabus.copy")}
      description={t("lessons.syllabus.copyHelp", { year: target?.name ?? "" })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <SelectField
          label={t("lessons.syllabus.copyFrom")}
          name="fromYearId"
          value={values.fromYearId}
          onChange={(e) => set("fromYearId", e.target.value)}
          options={others.map((y) => ({ value: y.id, label: y.name }))}
          error={errors.fromYearId}
          required
          data-autofocus
        />
        <fieldset className="fieldset">
          <legend>{t("lessons.syllabus.copyClasses")}</legend>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {options.classes.map((c) => (
              <label key={c.id} className="check">
                <input
                  type="checkbox"
                  name="classIds"
                  value={c.id}
                  checked={values.classIds.includes(c.id)}
                  onChange={(e) =>
                    set(
                      "classIds",
                      e.target.checked ? [...values.classIds, c.id] : values.classIds.filter((x) => x !== c.id),
                    )
                  }
                />
                <span>{c.name}</span>
              </label>
            ))}
          </div>
          <p className="field-hint">{t("lessons.syllabus.copyAll")}</p>
        </fieldset>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting || others.length === 0}>
            {form.submitting ? t("common.working") : t("lessons.syllabus.copyRun")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
