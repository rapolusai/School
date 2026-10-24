"use client";

import { ArrowLeft, CircleCheck, Download, FileSpreadsheet, FileUp, TriangleAlert, Upload } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { FormAlert, PageHead } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import {
  problemCount,
  SCHOOL_IMPORT_MAX_STAFF,
  SCHOOL_IMPORT_MAX_STUDENTS,
  schoolImportApi,
  workbookProblem,
  type SchoolImportResult,
  type SchoolImportSectionStatus,
  type SchoolImportSheet,
} from "@/lib/school-import-api";

const SECTION_LABELS: Record<SchoolImportSectionStatus, MessageKey> = {
  EXISTS: "schoolImport.section.EXISTS",
  NEW_SECTION: "schoolImport.section.NEW_SECTION",
  NEW_CLASS: "schoolImport.section.NEW_CLASS",
  MISSING: "schoolImport.section.MISSING",
};

const SECTION_TONES: Record<SchoolImportSectionStatus, PillTone> = {
  EXISTS: "good",
  NEW_SECTION: "info",
  NEW_CLASS: "info",
  MISSING: "bad",
};

/** Who may use the school import: it adds students and staff together. */
export function canImportSchool(me: Parameters<typeof hasPermission>[0]): boolean {
  return hasPermission(me, PERMISSIONS.studentsManage) && hasPermission(me, PERMISSIONS.staffManage);
}

/** The template and sample download buttons, with their own error message. */
export function SchoolImportDownloads() {
  const { t } = useI18n();
  const [busy, setBusy] = useState<"template" | "sample" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const download = async (which: "template" | "sample") => {
    if (busy) return;
    setBusy(which);
    setError(null);
    try {
      await (which === "template" ? schoolImportApi.downloadTemplate() : schoolImportApi.downloadSample());
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <FormAlert message={error} />
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn" onClick={() => void download("template")} disabled={busy !== null}>
          <Download size={18} aria-hidden="true" />
          {busy === "template" ? t("schoolImport.downloading") : t("schoolImport.template")}
        </button>
        <button type="button" className="btn" onClick={() => void download("sample")} disabled={busy !== null}>
          <FileSpreadsheet size={18} aria-hidden="true" />
          {busy === "sample" ? t("schoolImport.downloading") : t("schoolImport.sample")}
        </button>
      </div>
    </div>
  );
}

/** The step on the School setup page that leads a new school to the import. */
export function SchoolImportCard() {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="school-import-card" data-testid="school-import-card">
      <div className="flex items-start gap-3">
        <span className="badge-ic" style={{ background: "var(--accent-soft)", color: "var(--accent)" }}>
          <Upload size={20} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <h2 id="school-import-card">{t("schoolImport.card.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("schoolImport.card.body")}</p>
          <p className="field-hint mt-1">{t("schoolImport.sample.hint")}</p>
        </div>
      </div>
      <div className="flex flex-wrap items-start gap-2">
        <SchoolImportDownloads />
        <Link href="/app/setup/import" className="btn btn-primary">
          <FileUp size={18} aria-hidden="true" />
          {t("schoolImport.upload")}
        </Link>
      </div>
    </section>
  );
}

type Stage =
  | { kind: "idle" }
  | { kind: "checked"; result: SchoolImportResult }
  | { kind: "imported"; result: SchoolImportResult };

/** Bring an existing school's students and staff in from one Excel workbook: check, then import. */
export function SchoolImportView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const canCreate = hasPermission(me, PERMISSIONS.settingsManage);
  const [file, setFile] = useState<File | null>(null);
  const [createMissing, setCreateMissing] = useState(false);
  const [stage, setStage] = useState<Stage>({ kind: "idle" });
  const [busy, setBusy] = useState<"check" | "import" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  if (!canImportSchool(me)) {
    return (
      <section className="card">
        <p className="empty">{t("schoolImport.notAllowed")}</p>
      </section>
    );
  }

  const onPick = (event: React.ChangeEvent<HTMLInputElement>) => {
    const picked = event.target.files?.[0] ?? null;
    setStage({ kind: "idle" });
    setError(null);
    setFieldError(null);
    setFile(picked);
    if (!picked) return;
    const problem = workbookProblem(picked);
    if (problem === "type") setFieldError(t("schoolImport.v.type"));
    else if (problem === "empty") setFieldError(t("schoolImport.v.empty"));
    else if (problem === "size") setFieldError(t("schoolImport.v.tooLarge"));
  };

  const run = async (dryRun: boolean) => {
    if (!file || busy) return;
    setBusy(dryRun ? "check" : "import");
    setError(null);
    try {
      const result = await schoolImportApi.upload(file, dryRun, createMissing);
      setStage(dryRun ? { kind: "checked", result } : { kind: "imported", result });
    } catch (caught) {
      const apiError = toApiError(caught);
      if (apiError.errors?.file) {
        setFieldError(apiError.errors.file);
        setStage({ kind: "idle" });
      } else if (!dryRun && apiError.status === 400) {
        // Something changed since the check (for example a section filled up): check again to show why.
        setError(apiError.detail ?? t("schoolImport.failed"));
        try {
          setStage({ kind: "checked", result: await schoolImportApi.upload(file, true, createMissing) });
        } catch {
          // Keep the message above.
        }
      } else {
        setError(errorMessage(apiError, t));
      }
    } finally {
      setBusy(null);
    }
  };

  const onSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!file) {
      setFieldError(t("schoolImport.v.pickFile"));
      return;
    }
    if (fieldError) return;
    void run(true);
  };

  const result = stage.kind === "checked" ? stage.result : null;
  const back = (
    <Link href="/app/setup" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("schoolImport.back")}
    </Link>
  );

  if (stage.kind === "imported") {
    const r = stage.result;
    return (
      <>
        {back}
        <PageHead eyebrow={t("schoolImport.eyebrow")} title={t("schoolImport.title")} />
        <section className="card flex flex-col items-start gap-3" data-testid="school-import-done">
          <span className="badge-ic" style={{ background: "var(--good-soft)", color: "var(--good)" }}>
            <CircleCheck size={20} aria-hidden="true" />
          </span>
          <h2>{t("schoolImport.done", { students: r.studentsCreated, staff: r.staffCreated })}</h2>
          <p className="text-ink-2">{t("schoolImport.done.body")}</p>
          <p className="text-ink-2">{t("schoolImport.signIns")}</p>
          <div className="flex flex-wrap gap-2">
            <Link href="/app/students" className="btn btn-primary">
              {t("schoolImport.done.students")}
            </Link>
            <Link href="/app/staff" className="btn">
              {t("schoolImport.done.staff")}
            </Link>
          </div>
        </section>
      </>
    );
  }

  return (
    <>
      {back}
      <PageHead eyebrow={t("schoolImport.eyebrow")} title={t("schoolImport.title")} />
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
        <section className="card flex flex-col gap-3" aria-labelledby="school-import-howto">
          <h2 id="school-import-howto">{t("schoolImport.howTo")}</h2>
          <ol className="list-decimal space-y-1.5 pl-5 text-sm text-ink-2">
            <li>{t("schoolImport.step.template")}</li>
            <li>{t("schoolImport.step.fill")}</li>
            <li>{t("schoolImport.step.check")}</li>
          </ol>
          <p className="field-hint">
            {t("schoolImport.limits", {
              students: SCHOOL_IMPORT_MAX_STUDENTS.toLocaleString("en-IN"),
              staff: SCHOOL_IMPORT_MAX_STAFF.toLocaleString("en-IN"),
            })}
          </p>
          <p className="field-hint">{t("schoolImport.signIns")}</p>
          <p className="field-hint">{t("schoolImport.sample.hint")}</p>
          <SchoolImportDownloads />
        </section>

        <section className="card flex flex-col gap-3.5" aria-labelledby="school-import-file">
          <h2 id="school-import-file">{t("schoolImport.file")}</h2>
          <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate>
            <FormAlert message={error} />
            <div className="field">
              <label htmlFor="school-import-xlsx" className="field-label">
                {t("schoolImport.pick")}
              </label>
              <input
                id="school-import-xlsx"
                name="file"
                type="file"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                className="input"
                onChange={onPick}
                aria-invalid={fieldError ? true : undefined}
                aria-describedby={fieldError ? "school-import-xlsx-error" : "school-import-xlsx-hint"}
              />
              {fieldError ? (
                <p id="school-import-xlsx-error" className="field-error">
                  {fieldError}
                </p>
              ) : (
                <p id="school-import-xlsx-hint" className="field-hint">
                  {file ? file.name : t("schoolImport.pick.hint")}
                </p>
              )}
            </div>
            {canCreate ? (
              <label className="check">
                <input
                  type="checkbox"
                  name="createMissing"
                  checked={createMissing}
                  onChange={(e) => {
                    setCreateMissing(e.target.checked);
                    setStage({ kind: "idle" });
                  }}
                />
                <span>
                  <b className="block font-semibold">{t("schoolImport.createMissing")}</b>
                  <span className="block text-[13px] text-ink-3">{t("schoolImport.createMissing.hint")}</span>
                </span>
              </label>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <button type="submit" className="btn" disabled={busy !== null}>
                <FileUp size={18} aria-hidden="true" />
                {busy === "check" ? t("schoolImport.checking") : t("schoolImport.check")}
              </button>
              {result && result.ready ? (
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => void run(false)}
                  disabled={busy !== null}
                >
                  {busy === "import"
                    ? t("schoolImport.importing")
                    : t("schoolImport.submit", { students: result.studentRows, staff: result.staffRows })}
                </button>
              ) : null}
            </div>
          </form>

          {result ? <SchoolImportReport result={result} /> : null}
        </section>
      </div>
    </>
  );
}

function sectionCell(t: ReturnType<typeof useI18n>["t"], sheet: SchoolImportSheet) {
  if (sheet.kind === "STAFF") return t("schoolImport.kind.STAFF");
  if (sheet.kind === "IGNORED") return t("schoolImport.kind.IGNORED");
  if (!sheet.className) return "—";
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      {`${sheet.className} - ${sheet.sectionName}`}
      {sheet.section ? <Pill tone={SECTION_TONES[sheet.section]}>{t(SECTION_LABELS[sheet.section])}</Pill> : null}
    </span>
  );
}

/** The check, sheet by sheet: counts, what will be added, and every problem by row. */
export function SchoolImportReport({ result }: { result: SchoolImportResult }) {
  const { t } = useI18n();
  const problems = problemCount(result);
  const { classes, sections, departments } = result.toCreate;
  const sheets = result.sheets.filter((s) => s.kind !== "IGNORED" || s.problems.length > 0);
  return (
    <div className="flex flex-col gap-3" data-testid="school-import-report">
      <div className={`alert ${result.ready ? "alert-info" : "alert-bad"}`} role="status">
        {result.ready ? (
          <CircleCheck size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        ) : (
          <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        )}
        <span>
          {result.ready
            ? t("schoolImport.ready", { students: result.studentRows, staff: result.staffRows })
            : plural(t, "schoolImport.notReady", problems)}
        </span>
      </div>

      {result.ready && classes.length + sections.length + departments.length > 0 ? (
        <div className="text-sm" data-testid="school-import-to-create">
          <p className="field-label">{t("schoolImport.toCreate")}</p>
          <ul className="mt-1 list-disc space-y-0.5 pl-5 text-ink-2">
            {classes.length ? <li>{t("schoolImport.toCreate.classes", { names: classes.join(", ") })}</li> : null}
            {sections.length ? <li>{t("schoolImport.toCreate.sections", { names: sections.join(", ") })}</li> : null}
            {departments.length ? (
              <li>{t("schoolImport.toCreate.departments", { names: departments.join(", ") })}</li>
            ) : null}
          </ul>
        </div>
      ) : null}

      <div className="table-wrap">
        <table className="table" data-testid="school-import-sheets">
          <thead>
            <tr>
              <th scope="col">{t("schoolImport.col.sheet")}</th>
              <th scope="col">{t("schoolImport.col.section")}</th>
              <th scope="col" className="r">
                {t("schoolImport.col.rows")}
              </th>
              <th scope="col" className="r">
                {t("schoolImport.col.ready")}
              </th>
              <th scope="col" className="r">
                {t("schoolImport.col.toFix")}
              </th>
            </tr>
          </thead>
          <tbody>
            {sheets.map((s) => (
              <tr key={s.name}>
                <td>{s.name}</td>
                <td>{sectionCell(t, s)}</td>
                <td className="r num">{s.rows}</td>
                <td className="r num">{s.validRows}</td>
                <td className="r num">{s.invalidRows + s.problems.filter((p) => p.row === null).length}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {sheets
        .filter((s) => s.problems.length > 0 || s.ignoredColumns.length > 0)
        .map((s) => (
          <section key={s.name} className="flex flex-col gap-1.5" aria-label={t("schoolImport.problems", { sheet: s.name })}>
            <h3 className="text-sm font-semibold">{t("schoolImport.problems", { sheet: s.name })}</h3>
            {s.ignoredColumns.length ? (
              <p className="field-hint">{t("schoolImport.ignored", { columns: s.ignoredColumns.join(", ") })}</p>
            ) : null}
            {s.problems.length ? (
              <ul className="list" data-testid="school-import-problems">
                {s.problems.map((p, i) => (
                  <li key={`${p.row}-${p.column}-${i}`} className="li flex-col gap-0.5">
                    <span className="text-[13px] text-ink-3">
                      {p.row === null
                        ? t("schoolImport.sheetProblem")
                        : p.column
                          ? t("schoolImport.rowColumn", { row: p.row, column: p.column })
                          : t("schoolImport.row", { row: p.row })}
                    </span>
                    <span>{p.message}</span>
                  </li>
                ))}
              </ul>
            ) : null}
            {s.problemsNotShown > 0 ? (
              <p className="field-hint">{t("schoolImport.more", { count: s.problemsNotShown })}</p>
            ) : null}
          </section>
        ))}
    </div>
  );
}
