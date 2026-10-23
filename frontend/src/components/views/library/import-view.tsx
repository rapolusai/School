"use client";

import { CircleCheck, Download, FileUp, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { FormAlert, PageHead } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { countCsvRows, csvLine } from "@/lib/csv";
import { errorMessage, fieldErrorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import type { LibraryImportResult } from "@/lib/types";
import { LibraryNav } from "./library-shared";

/** The API's limits for a catalogue file (docs/api/phase-2-library.md). */
export const LIBRARY_IMPORT_MAX_ROWS = 2000;
export const LIBRARY_IMPORT_MAX_BYTES = 2_000_000;
const MAX_REPORTED_ERRORS = 500;

export const LIBRARY_IMPORT_COLUMNS = ["title"] as const;
export const LIBRARY_IMPORT_OPTIONAL_COLUMNS = [
  "isbn",
  "subtitle",
  "authors",
  "publisher",
  "year",
  "edition",
  "language",
  "subject",
  "call_number",
  "shelf_location",
  "copies",
  "price",
  "source",
  "accession_no",
  "acquired_on",
] as const;

/** A catalogue template with the header and two example rows. Pure and exported for tests. */
export function libraryImportTemplate(): string {
  return (
    [
      csvLine([...LIBRARY_IMPORT_COLUMNS, ...LIBRARY_IMPORT_OPTIONAL_COLUMNS]),
      csvLine([
        "The Jungle Book",
        "9788171673407",
        "",
        "Rudyard Kipling",
        "Rupa Publications",
        "2015",
        "",
        "English",
        "Fiction",
        "823 KIP",
        "Shelf A2",
        "2",
        "199",
        "PURCHASE",
        "",
        "2026-06-15",
      ]),
      csvLine(["Panchatantra", "", "", "Vishnu Sharma", "", "", "", "Hindi", "Stories", "891.43 VIS", "", "1", "", "DONATION", "", ""]),
    ].join("\r\n") + "\r\n"
  );
}

type Picked = { name: string; text: string; rows: number };
type Stage = { kind: "idle" } | { kind: "checked"; result: LibraryImportResult } | { kind: "imported"; result: LibraryImportResult };

/** /app/library/import: books from a spreadsheet, checked first; nothing is saved until every row is right. */
export function LibraryImportView() {
  const { t } = useI18n();
  const [file, setFile] = useState<Picked | null>(null);
  const [stage, setStage] = useState<Stage>({ kind: "idle" });
  const [busy, setBusy] = useState<"check" | "import" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  const downloadTemplate = () => {
    const blob = new Blob([libraryImportTemplate()], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "library-catalogue-template.csv";
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
    if (picked.size > LIBRARY_IMPORT_MAX_BYTES) {
      setFile(null);
      setFieldError(t("import.v.tooLarge"));
      return;
    }
    const text = await picked.text();
    const rows = countCsvRows(text);
    setFile({ name: picked.name, text, rows });
    if (rows > LIBRARY_IMPORT_MAX_ROWS) {
      setFieldError(t("library.import.v.tooManyRows", { max: LIBRARY_IMPORT_MAX_ROWS.toLocaleString("en-IN") }));
    } else if (rows === 0) setFieldError(t("library.import.v.noRows"));
  };

  const run = async (dryRun: boolean) => {
    if (!file || busy) return;
    setBusy(dryRun ? "check" : "import");
    setError(null);
    try {
      const result = await libraryApi.importCsv(file.text, dryRun);
      setStage(dryRun ? { kind: "checked", result } : { kind: "imported", result });
    } catch (caught) {
      const apiError = toApiError(caught);
      if (apiError.errors?.csv) {
        setFieldError(fieldErrorMessage(apiError.errors.csv, t));
        setStage({ kind: "idle" });
      } else if (!dryRun && apiError.status === 400) {
        // Something changed since the check (an accession number was taken meanwhile): check again to show why.
        setError(apiError.detail ? errorMessage(apiError, t) : t("import.failed"));
        try {
          setStage({ kind: "checked", result: await libraryApi.importCsv(file.text, true) });
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
      <PageHead eyebrow={t("nav.library")} title={t("library.import.title")} />
      <LibraryNav />
      {stage.kind === "imported" ? (
        <section className="card flex flex-col items-start gap-3" data-testid="library-import-done">
          <span className="badge-ic" style={{ background: "var(--good-soft)", color: "var(--good)" }}>
            <CircleCheck size={20} aria-hidden="true" />
          </span>
          <h2>
            {plural(t, "library.import.doneTitles", stage.result.titlesCreated)} ·{" "}
            {plural(t, "library.import.doneCopies", stage.result.copiesCreated)}
          </h2>
          <p className="text-ink-2">{t("library.import.doneBody")}</p>
          <div className="flex flex-wrap gap-2">
            <Link href="/app/library" className="btn btn-primary">
              {t("library.import.view")}
            </Link>
            <Link href="/app/library/labels" className="btn">
              {t("library.labels.title")}
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
          <section className="card flex flex-col gap-3" aria-labelledby="library-import-howto">
            <h2 id="library-import-howto">{t("import.howTo")}</h2>
            <ol className="list-decimal space-y-1.5 pl-5 text-sm text-ink-2">
              <li>{t("import.step.template")}</li>
              <li>{t("library.import.step.fill", { max: LIBRARY_IMPORT_MAX_ROWS.toLocaleString("en-IN") })}</li>
              <li>{t("import.step.check")}</li>
            </ol>
            <div>
              <p className="field-label">{t("import.columns")}</p>
              <p className="mt-1 flex flex-wrap gap-1">
                {LIBRARY_IMPORT_COLUMNS.map((c) => (
                  <span key={c} className="chip mono">
                    {c}
                  </span>
                ))}
              </p>
              <p className="field-label mt-2">{t("import.optionalColumns")}</p>
              <p className="mt-1 flex flex-wrap gap-1">
                {LIBRARY_IMPORT_OPTIONAL_COLUMNS.map((c) => (
                  <span key={c} className="chip mono">
                    {c}
                  </span>
                ))}
              </p>
              <p className="field-hint mt-2">{t("library.import.formats")}</p>
            </div>
            <button type="button" className="btn self-start" onClick={downloadTemplate}>
              <Download size={18} aria-hidden="true" />
              {t("import.template")}
            </button>
          </section>

          <section className="card flex flex-col gap-3.5" aria-labelledby="library-import-file">
            <h2 id="library-import-file">{t("import.file")}</h2>
            <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate>
              <FormAlert message={error} />
              <div className="field">
                <label htmlFor="library-import-csv" className="field-label">
                  {t("import.pick")}
                </label>
                <input
                  id="library-import-csv"
                  name="csv"
                  type="file"
                  accept=".csv,text/csv"
                  className="input"
                  onChange={onPick}
                  aria-invalid={fieldError ? true : undefined}
                  aria-describedby={fieldError ? "library-import-csv-error" : "library-import-csv-hint"}
                />
                {fieldError ? (
                  <p id="library-import-csv-error" className="field-error">
                    {fieldError}
                  </p>
                ) : (
                  <p id="library-import-csv-hint" className="field-hint">
                    {file ? plural(t, "library.import.picked", file.rows, { name: file.name }) : t("import.pick.hint")}
                  </p>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                <button type="submit" className="btn" disabled={busy !== null}>
                  <FileUp size={18} aria-hidden="true" />
                  {busy === "check" ? t("import.checking") : t("import.check")}
                </button>
                {result && result.invalidRows === 0 && result.validRows > 0 ? (
                  <button type="button" className="btn btn-primary" onClick={() => void run(false)} disabled={busy !== null}>
                    {busy === "import" ? t("import.importing") : plural(t, "library.import.submit", result.validRows)}
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

function ImportReport({ result }: { result: LibraryImportResult }) {
  const { t } = useI18n();
  const ok = result.invalidRows === 0;
  return (
    <div className="flex flex-col gap-3" data-testid="library-import-report">
      <div className={`alert ${ok ? "alert-info" : "alert-bad"}`} role="status">
        {ok ? (
          <CircleCheck size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        ) : (
          <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        )}
        <span>
          {ok
            ? `${plural(t, "import.allValid", result.validRows)} ${t("library.import.willAdd", {
                titles: result.titlesCreated,
                copies: result.copiesCreated,
              })}`
            : t("import.someInvalid", { invalid: result.invalidRows, total: result.totalRows })}
        </span>
      </div>
      {result.ignoredColumns.length ? (
        <p className="field-hint">{t("import.ignored", { columns: result.ignoredColumns.join(", ") })}</p>
      ) : null}
      {result.errors.length ? (
        <>
          <div className="table-wrap hidden sm:block">
            <table className="table" data-testid="library-import-errors">
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
                <span className="text-[13px] text-ink-3">{t("import.rowColumn", { row: e.row, column: e.column })}</span>
                <span>{e.message}</span>
              </li>
            ))}
          </ul>
          {result.errors.length >= MAX_REPORTED_ERRORS ? <p className="field-hint">{t("import.moreErrors")}</p> : null}
        </>
      ) : null}
      {ok && result.preview.length ? (
        <div className="table-wrap" data-testid="library-import-preview">
          <table className="table">
            <caption className="sr-only">{t("library.import.preview")}</caption>
            <thead>
              <tr>
                <th scope="col" className="r">
                  {t("import.col.row")}
                </th>
                <th scope="col">{t("library.col.title")}</th>
                <th scope="col" className="r">
                  {t("library.col.copies")}
                </th>
              </tr>
            </thead>
            <tbody>
              {result.preview.map((row) => (
                <tr key={row.row}>
                  <td className="r num">{row.row}</td>
                  <td>
                    {row.title}
                    <span className="block text-[12.5px] text-ink-3">
                      {[row.authors, row.isbn13, row.accessionNo, row.existingTitle ? t("library.import.existing") : null]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </td>
                  <td className="r num">{row.copies}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}
