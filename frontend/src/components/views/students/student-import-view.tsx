"use client";

import { ArrowLeft, CircleCheck, Download, FileUp, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { FormAlert, PageHead } from "@/components/ui/states";
import { api, toApiError } from "@/lib/api";
import { countCsvRows, IMPORT_COLUMNS, IMPORT_MAX_ROWS, IMPORT_OPTIONAL_COLUMNS, importTemplate } from "@/lib/csv";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import type { ImportResult } from "@/lib/types";

/** Largest file the API accepts (it also caps the rows at 2,000). */
export const MAX_FILE_BYTES = 2_000_000;
/** The API lists at most this many problems. */
const MAX_REPORTED_ERRORS = 500;

type Picked = { name: string; text: string; rows: number };

type Stage =
  | { kind: "idle" }
  | { kind: "checked"; result: ImportResult }
  | { kind: "imported"; result: ImportResult };

export function StudentImportView() {
  const { t } = useI18n();
  const [file, setFile] = useState<Picked | null>(null);
  const [stage, setStage] = useState<Stage>({ kind: "idle" });
  const [busy, setBusy] = useState<"check" | "import" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  const downloadTemplate = () => {
    const blob = new Blob([importTemplate()], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "students-template.csv";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  };

  const onPick = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const picked = event.target.files?.[0];
    setStage({ kind: "idle" });
    setError(null);
    setFieldError(null);
    if (!picked) {
      setFile(null);
      return;
    }
    if (picked.size > MAX_FILE_BYTES) {
      setFile(null);
      setFieldError(t("import.v.tooLarge"));
      return;
    }
    const text = await picked.text();
    const rows = countCsvRows(text);
    setFile({ name: picked.name, text, rows });
    if (rows > IMPORT_MAX_ROWS) setFieldError(t("import.v.tooManyRows", { max: IMPORT_MAX_ROWS.toLocaleString("en-IN") }));
    else if (rows === 0) setFieldError(t("import.v.noRows"));
  };

  const run = async (dryRun: boolean) => {
    if (!file || busy) return;
    setBusy(dryRun ? "check" : "import");
    setError(null);
    try {
      const result = await api.importStudents(file.text, dryRun);
      setStage(dryRun ? { kind: "checked", result } : { kind: "imported", result });
    } catch (caught) {
      const apiError = toApiError(caught);
      if (apiError.errors?.csv) {
        setFieldError(apiError.errors.csv);
        setStage({ kind: "idle" });
      } else if (!dryRun && apiError.status === 400) {
        // Something changed since the check (for example a section filled up): check again to show why.
        setError(apiError.detail ?? t("import.failed"));
        try {
          setStage({ kind: "checked", result: await api.importStudents(file.text, true) });
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
      setFieldError(t("import.v.pickFile"));
      return;
    }
    if (fieldError) return;
    void run(true);
  };

  const result = stage.kind === "idle" ? null : stage.result;

  return (
    <>
      <Link href="/app/students" className="link inline-flex items-center gap-1 text-[13.5px]">
        <ArrowLeft size={16} aria-hidden="true" />
        {t("student.back")}
      </Link>
      <PageHead eyebrow={t("import.eyebrow")} title={t("import.title")} />

      {stage.kind === "imported" ? (
        <section className="card flex flex-col items-start gap-3" data-testid="import-done">
          <span className="badge-ic" style={{ background: "var(--good-soft)", color: "var(--good)" }}>
            <CircleCheck size={20} aria-hidden="true" />
          </span>
          <h2>{plural(t, "import.done", stage.result.created)}</h2>
          <p className="text-ink-2">{t("import.done.body")}</p>
          <div className="flex flex-wrap gap-2">
            <Link href="/app/students" className="btn btn-primary">
              {t("import.done.view")}
            </Link>
            <button
              type="button"
              className="btn"
              onClick={() => {
                setFile(null);
                setStage({ kind: "idle" });
              }}
            >
              {t("import.done.another")}
            </button>
          </div>
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
          <section className="card flex flex-col gap-3" aria-labelledby="import-howto">
            <h2 id="import-howto">{t("import.howTo")}</h2>
            <ol className="list-decimal space-y-1.5 pl-5 text-sm text-ink-2">
              <li>{t("import.step.template")}</li>
              <li>{t("import.step.fill", { max: IMPORT_MAX_ROWS.toLocaleString("en-IN") })}</li>
              <li>{t("import.step.check")}</li>
            </ol>
            <div>
              <p className="field-label">{t("import.columns")}</p>
              <p className="mt-1 flex flex-wrap gap-1">
                {IMPORT_COLUMNS.map((c) => (
                  <span key={c} className="chip mono">
                    {c}
                  </span>
                ))}
              </p>
              <p className="field-label mt-2">{t("import.optionalColumns")}</p>
              <p className="mt-1 flex flex-wrap gap-1">
                {IMPORT_OPTIONAL_COLUMNS.map((c) => (
                  <span key={c} className="chip mono">
                    {c}
                  </span>
                ))}
              </p>
              <p className="field-hint mt-2">{t("import.formats")}</p>
            </div>
            <button type="button" className="btn self-start" onClick={downloadTemplate}>
              <Download size={18} aria-hidden="true" />
              {t("import.template")}
            </button>
          </section>

          <section className="card flex flex-col gap-3.5" aria-labelledby="import-file">
            <h2 id="import-file">{t("import.file")}</h2>
            <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate>
              <FormAlert message={error} />
              <div className="field">
                <label htmlFor="import-csv" className="field-label">
                  {t("import.pick")}
                </label>
                <input
                  id="import-csv"
                  name="csv"
                  type="file"
                  accept=".csv,text/csv"
                  className="input"
                  onChange={onPick}
                  aria-invalid={fieldError ? true : undefined}
                  aria-describedby={fieldError ? "import-csv-error" : "import-csv-hint"}
                />
                {fieldError ? (
                  <p id="import-csv-error" className="field-error">
                    {fieldError}
                  </p>
                ) : (
                  <p id="import-csv-hint" className="field-hint">
                    {file ? plural(t, "import.picked", file.rows, { name: file.name }) : t("import.pick.hint")}
                  </p>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                <button type="submit" className="btn" disabled={busy !== null}>
                  <FileUp size={18} aria-hidden="true" />
                  {busy === "check" ? t("import.checking") : t("import.check")}
                </button>
                {result && result.invalidRows === 0 ? (
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => void run(false)}
                    disabled={busy !== null}
                  >
                    {busy === "import" ? t("import.importing") : plural(t, "import.submit", result.validRows)}
                  </button>
                ) : null}
              </div>
            </form>

            {result ? <ImportReport result={result} /> : null}
          </section>
        </div>
      )}
    </>
  );
}

function ImportReport({ result }: { result: ImportResult }) {
  const { t } = useI18n();
  const ok = result.invalidRows === 0;
  return (
    <div className="flex flex-col gap-3" data-testid="import-report">
      <div className={`alert ${ok ? "alert-info" : "alert-bad"}`} role="status">
        {ok ? (
          <CircleCheck size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        ) : (
          <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        )}
        <span>
          {ok
            ? plural(t, "import.allValid", result.validRows)
            : t("import.someInvalid", { invalid: result.invalidRows, total: result.totalRows })}
        </span>
      </div>
      {result.ignoredColumns.length ? (
        <p className="field-hint">{t("import.ignored", { columns: result.ignoredColumns.join(", ") })}</p>
      ) : null}
      {result.errors.length ? (
        <>
          <div className="table-wrap hidden sm:block">
            <table className="table" data-testid="import-errors">
              <thead>
                <tr>
                  <th scope="col" className="r">
                    {t("import.col.row")}
                  </th>
                  <th scope="col">{t("import.col.column")}</th>
                  <th scope="col">{t("import.col.problem")}</th>
                </tr>
              </thead>
              <tbody>
                {result.errors.map((e, i) => (
                  <tr key={`${e.row}-${e.column}-${i}`}>
                    <td className="r num">{e.row}</td>
                    <td className="mono">{e.column}</td>
                    <td>{e.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="list sm:hidden">
            {result.errors.map((e, i) => (
              <li key={`${e.row}-${e.column}-${i}`} className="li flex-col gap-0.5">
                <span className="text-[13px] text-ink-3">
                  {t("import.rowColumn", { row: e.row, column: e.column })}
                </span>
                <span>{e.message}</span>
              </li>
            ))}
          </ul>
          {result.errors.length >= MAX_REPORTED_ERRORS ? (
            <p className="field-hint">{t("import.moreErrors")}</p>
          ) : null}
        </>
      ) : null}
    </div>
  );
}
