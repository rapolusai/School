"use client";

import { Download } from "lucide-react";
import { useState } from "react";
import { SelectField, TextField } from "@/components/ui/field";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { govPaths, govreportApi, type ExportParams } from "@/lib/govreport-api";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import type { ExportCell, ExportPreview } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import {
  GovReportNav,
  useYears,
  VerifyNote,
  YearSelect,
} from "./govreport-shared";

/* /app/govreport/exports: UDISE+, CBSE OASIS and CBSE registration, previewed before download. */

type Kind = "udise" | "oasis" | "registration";
const KINDS: { kind: Kind; labelKey: MessageKey }[] = [
  { kind: "udise", labelKey: "govreport.exports.udise" },
  { kind: "oasis", labelKey: "govreport.exports.oasis" },
  { kind: "registration", labelKey: "govreport.exports.registration" },
];

export function ExportsView() {
  const { t } = useI18n();
  const { current } = useYears();
  const [kind, setKind] = useState<Kind>("udise");
  const [chosenYear, setChosenYear] = useState("");
  const [asOf, setAsOf] = useState(() => todayInIndia());
  const [classId, setClassId] = useState("");
  const yearId = chosenYear || current;
  const classes = useApiData(
    kind === "registration" ? "govreport:registration-classes" : null,
    govreportApi.registrationClasses,
  );
  const classList = classes.data ?? [];
  const chosenClass = classId || classList[0]?.id || "";
  const params: ExportParams = {
    yearId: yearId || undefined,
    asOf,
    classId: kind === "registration" ? chosenClass : undefined,
  };
  const ready =
    Boolean(yearId) &&
    /^\d{4}-\d{2}-\d{2}$/.test(asOf) &&
    (kind !== "registration" || Boolean(chosenClass));
  const key = ready
    ? `govreport:export:${kind}:${JSON.stringify(params)}`
    : null;
  const preview = useApiData(key, () =>
    kind === "udise"
      ? govreportApi.udise(params)
      : kind === "oasis"
        ? govreportApi.oasis(params)
        : govreportApi.registration(params),
  );

  return (
    <>
      <PageHead
        eyebrow={t("nav.govreport")}
        title={t("govreport.exports.title")}
      />
      <GovReportNav />
      <VerifyNote />
      <div
        className="subnav"
        role="group"
        aria-label={t("govreport.exports.kind")}
      >
        {KINDS.map((k) => (
          <button
            key={k.kind}
            type="button"
            aria-pressed={kind === k.kind}
            aria-current={kind === k.kind ? "page" : undefined}
            onClick={() => setKind(k.kind)}
            data-testid={`govreport-export-${k.kind}`}
          >
            {t(k.labelKey)}
          </button>
        ))}
      </div>
      <form
        method="post"
        className="toolbar"
        onSubmit={(e) => e.preventDefault()}
        aria-label={t("reports.filters")}
      >
        <YearSelect value={yearId} onChange={setChosenYear} />
        <TextField
          label={t("govreport.asOf")}
          name="asOf"
          type="date"
          value={asOf}
          onChange={(e) => setAsOf(e.target.value)}
          className="w-full sm:w-auto"
        />
        {kind === "registration" ? (
          <SelectField
            label={t("govreport.exports.class")}
            name="classId"
            value={chosenClass}
            onChange={(e) => setClassId(e.target.value)}
            options={classList.map((c) => ({ value: c.id, label: c.name }))}
            className="w-full sm:w-auto sm:min-w-36"
          />
        ) : null}
      </form>
      {kind === "registration" && classes.data && classList.length === 0 ? (
        <p className="empty">{t("govreport.exports.noRegistrationClasses")}</p>
      ) : preview.error && !preview.data ? (
        <ErrorState error={preview.error} onRetry={preview.reload} />
      ) : !preview.data || preview.loading ? (
        <LoadingRows rows={5} />
      ) : (
        <ExportPreviewPanel
          preview={preview.data}
          kind={kind}
          params={params}
        />
      )}
    </>
  );
}

function cell(value: ExportCell, yes: string, no: string): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "boolean") return value ? yes : no;
  return String(value);
}

/** The preview: each table as it will appear in the workbook, and the downloads. Exported for tests. */
export function ExportPreviewPanel({
  preview,
  kind,
  params,
}: {
  preview: ExportPreview;
  kind: Kind;
  params: ExportParams;
}) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const workbook =
    kind === "udise"
      ? govPaths.udiseXlsx(params)
      : kind === "oasis"
        ? govPaths.oasisXlsx(params)
        : govPaths.registrationXlsx(params);
  const download = async (path: string, name: string) => {
    setBusy(true);
    setError(null);
    try {
      const saved = await govreportApi.download(path, name);
      toast(t("reports.downloaded", { name: saved }));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section
      className="card flex flex-col gap-3"
      data-testid="govreport-export-preview"
      aria-labelledby="gov-export-head"
    >
      <div className="card-head flex flex-wrap items-center justify-between gap-2">
        <h2 id="gov-export-head">
          {preview.academicYearName} ·{" "}
          {t("govreport.asOfDate", {
            date: formatPlainDate(preview.asOf, localeFor(lang)),
          })}
        </h2>
        <button
          type="button"
          className="btn btn-primary"
          disabled={busy}
          onClick={() => void download(workbook, `${kind}.xlsx`)}
          data-testid="govreport-download-workbook"
        >
          <Download size={18} aria-hidden="true" />
          {t("govreport.exports.workbook")}
        </button>
      </div>
      <FormAlert message={error} />
      {preview.notes.length > 0 ? (
        <ul className="list-disc pl-5 text-[13px] text-ink-3">
          {preview.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      ) : null}
      {preview.tables.map((table) => (
        <div
          key={table.key}
          className="flex flex-col gap-2"
          data-testid={`govreport-export-table-${table.key}`}
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="font-semibold">{table.title}</h3>
            {kind === "udise" ? (
              <button
                type="button"
                className="btn btn-sm"
                disabled={busy}
                onClick={() =>
                  void download(
                    govPaths.udiseCsv(table.key, params),
                    `udise-${table.key}.csv`,
                  )
                }
              >
                <Download size={16} aria-hidden="true" />
                {t("govreport.exports.csv")}
              </button>
            ) : null}
          </div>
          {table.rows.length === 0 ? (
            <p className="empty">{t("govreport.exports.emptyTable")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table">
                <caption className="sr-only">{table.title}</caption>
                <thead>
                  <tr>
                    {table.columns.map((c) => (
                      <th key={c} scope="col">
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {table.rows.slice(0, 200).map((row, i) => (
                    <tr key={i}>
                      {row.map((value, j) => (
                        <td
                          key={j}
                          className={
                            typeof value === "number" ? "num" : undefined
                          }
                        >
                          {cell(value, t("govreport.yes"), t("govreport.no"))}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ))}
    </section>
  );
}
