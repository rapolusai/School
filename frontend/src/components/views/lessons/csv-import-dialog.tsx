"use client";

import { FileUp } from "lucide-react";
import { useId, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { FormAlert } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import type { ImportRowError } from "@/lib/types";
import { readCsvFile } from "./lessons-shared";

/** What both lessons imports (syllabus and question bank) answer. */
export type CsvImportResult<P> = {
  dryRun: boolean;
  committed: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  errors: ImportRowError[];
  ignoredColumns: string[];
  preview: P[];
};

/** The largest CSV text the API accepts, in characters. */
const MAX_CSV = 2_000_000;

/**
 * Importing a CSV file in two steps, like the student import: the file is checked first (a dry run that shows the
 * rows it read and every row problem), and only a clean file can then be imported, all rows or none.
 */
export function CsvImportDialog<P extends { row: number }>({
  title,
  description,
  columns,
  check,
  commit,
  previewHead,
  previewCells,
  onClose,
  onImported,
}: {
  title: string;
  description: string;
  /** the columns the file has, for the help line */
  columns: string;
  check: (csv: string) => Promise<CsvImportResult<P>>;
  commit: (csv: string) => Promise<CsvImportResult<P>>;
  previewHead: string[];
  previewCells: (row: P) => string[];
  onClose: () => void;
  onImported: (result: CsvImportResult<P>) => void;
}) {
  const { t } = useI18n();
  const inputId = useId();
  const [csv, setCsv] = useState<string | null>(null);
  const [fileName, setFileName] = useState("");
  const [result, setResult] = useState<CsvImportResult<P> | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pick = async (file: File | undefined) => {
    setResult(null);
    setError(null);
    setCsv(null);
    if (!file) return;
    setFileName(file.name);
    if (!/\.csv$/i.test(file.name)) {
      setError(t("lessons.import.notCsv"));
      return;
    }
    setBusy(true);
    try {
      const text = await readCsvFile(file);
      if (text.length > MAX_CSV) {
        setError(t("lessons.import.tooLarge"));
        return;
      }
      setCsv(text);
      setResult(await check(text));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const run = async () => {
    if (!csv) return;
    setBusy(true);
    setError(null);
    try {
      onImported(await commit(csv));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const ready = result !== null && result.invalidRows === 0 && result.validRows > 0;

  return (
    <Dialog open onClose={onClose} title={title} description={description} closeLabel={t("common.close")}>
      <form
        method="post"
        className="flex flex-col gap-3.5"
        onSubmit={(e) => {
          e.preventDefault();
          void run();
        }}
        noValidate
        data-testid="csv-import"
      >
        <p className="text-[13px] text-ink-2">{t("lessons.import.columns", { columns })}</p>
        <div className="field">
          <span className="field-label">{t("lessons.import.file")}</span>
          <div className="flex flex-wrap items-center gap-2">
            <label className="btn btn-sm" htmlFor={inputId}>
              <FileUp size={16} aria-hidden="true" />
              {t("lessons.import.choose")}
            </label>
            <input
              id={inputId}
              name="file"
              type="file"
              accept=".csv,text/csv"
              className="sr-only"
              onChange={(e) => void pick(e.target.files?.[0])}
            />
            <span className="text-[13px] text-ink-3">{fileName || t("lessons.import.noFile")}</span>
          </div>
        </div>
        <FormAlert message={error} />
        {busy && !result ? <p className="text-[13px] text-ink-3">{t("lessons.import.checking")}</p> : null}
        {result ? (
          <div className="flex flex-col gap-2" data-testid="import-result">
            <p className={`alert ${result.invalidRows > 0 ? "alert-warn" : "alert-good"}`} role="status">
              {result.invalidRows > 0
                ? t("lessons.import.problems", { total: result.totalRows, invalid: result.invalidRows })
                : plural(t, "lessons.import.ready", result.validRows)}
            </p>
            {result.ignoredColumns.length > 0 ? (
              <p className="text-[12.5px] text-ink-3">
                {t("lessons.import.ignored", { columns: result.ignoredColumns.join(", ") })}
              </p>
            ) : null}
            {result.errors.length > 0 ? (
              <div className="table-wrap max-h-60 overflow-auto">
                <table className="table" data-testid="import-errors">
                  <caption className="sr-only">{t("lessons.import.errors")}</caption>
                  <thead>
                    <tr>
                      <th scope="col" className="r">
                        {t("lessons.import.row")}
                      </th>
                      <th scope="col">{t("lessons.import.column")}</th>
                      <th scope="col">{t("lessons.import.problem")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.errors.map((e, i) => (
                      <tr key={`${e.row}:${e.column}:${i}`}>
                        <td className="r num">{e.row}</td>
                        <td className="mono">{e.column}</td>
                        <td>{e.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : result.preview.length > 0 ? (
              <div className="table-wrap max-h-60 overflow-auto">
                <table className="table" data-testid="import-preview">
                  <caption className="sr-only">{t("lessons.import.preview")}</caption>
                  <thead>
                    <tr>
                      <th scope="col" className="r">
                        {t("lessons.import.row")}
                      </th>
                      {previewHead.map((head) => (
                        <th key={head} scope="col">
                          {head}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {result.preview.map((row) => (
                      <tr key={row.row}>
                        <td className="r num">{row.row}</td>
                        {previewCells(row).map((cell, i) => (
                          <td key={i}>{cell}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
          </div>
        ) : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={!ready || busy}>
            {busy && result ? t("common.working") : result && ready ? plural(t, "lessons.import.run", result.validRows) : t("lessons.import.runNone")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
