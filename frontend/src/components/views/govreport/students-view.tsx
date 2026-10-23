"use client";

import { Download, Upload } from "lucide-react";
import { useMemo, useState } from "react";
import { SelectField, TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useSectionOptions } from "@/components/views/certificates/certificates-shared";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { govPaths, govreportApi } from "@/lib/govreport-api";
import { useI18n, type MessageKey } from "@/lib/i18n";
import type {
  CodeView,
  GovImportResult,
  SectionGrid,
  StudentFieldsEntry,
  StudentFieldsRow,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import {
  codeOptions,
  fieldLabel,
  fromYesNo,
  GovReportNav,
  useCanManage,
  useCodes,
  VerifyNote,
  yesNo,
  yesNoOptions,
} from "./govreport-shared";

/* /app/govreport/students: the bulk entry grid by section, the CSV import and the completeness by section. */

type RowValues = {
  apaarId: string;
  pen: string;
  socialCategory: string;
  religion: string;
  motherTongue: string;
  bpl: string;
  cwsnType: string;
  rteAdmitted: string;
  previousSchoolUdise: string;
};

function rowValues(r: StudentFieldsRow): RowValues {
  return {
    apaarId: r.apaarId ?? "",
    pen: r.pen ?? "",
    socialCategory: r.socialCategory ?? "",
    religion: r.religion ?? "",
    motherTongue: r.motherTongue ?? "",
    bpl: yesNo(r.bpl),
    cwsnType: r.cwsnType ?? "",
    rteAdmitted: yesNo(r.rteAdmitted),
    previousSchoolUdise: r.previousSchoolUdise ?? "",
  };
}

/** Client checks mirroring the API: 12-digit APAAR, up to 20 letters or digits for PEN, 11-digit UDISE. */
export function rowProblems(
  v: RowValues,
): Partial<Record<keyof RowValues, MessageKey>> {
  const problems: Partial<Record<keyof RowValues, MessageKey>> = {};
  if (v.apaarId.trim() && !/^\d{12}$/.test(v.apaarId.trim()))
    problems.apaarId = "govreport.v.apaar";
  if (v.pen.trim() && !/^[A-Za-z0-9]{1,20}$/.test(v.pen.trim()))
    problems.pen = "govreport.v.pen";
  if (
    v.previousSchoolUdise.trim() &&
    !/^\d{11}$/.test(v.previousSchoolUdise.trim())
  )
    problems.previousSchoolUdise = "govreport.v.udise";
  return problems;
}

function entry(studentId: string, v: RowValues): StudentFieldsEntry {
  const text = (s: string) => s.trim() || null;
  return {
    studentId,
    apaarId: text(v.apaarId),
    pen: text(v.pen),
    socialCategory: text(v.socialCategory),
    religion: text(v.religion),
    motherTongue: text(v.motherTongue),
    bpl: fromYesNo(v.bpl),
    cwsnType: text(v.cwsnType),
    rteAdmitted: fromYesNo(v.rteAdmitted),
    previousSchoolUdise: text(v.previousSchoolUdise),
  };
}

export function StudentsView() {
  const { t } = useI18n();
  const sections = useSectionOptions();
  const [chosen, setChosen] = useState("");
  const sectionId = chosen || sections[0]?.value || "";
  return (
    <>
      <PageHead
        eyebrow={t("nav.govreport")}
        title={t("govreport.students.title")}
      />
      <GovReportNav />
      <div className="toolbar">
        <SelectField
          label={t("govreport.section")}
          name="sectionId"
          value={sectionId}
          onChange={(e) => setChosen(e.target.value)}
          options={sections}
          className="w-full sm:w-auto sm:min-w-44"
        />
      </div>
      {sectionId ? (
        <FieldsGrid key={sectionId} sectionId={sectionId} />
      ) : (
        <p className="empty">{t("govreport.students.noSections")}</p>
      )}
      <div className="mt-3.5 grid grid-cols-1 gap-3.5 xl:grid-cols-2">
        <ImportCard />
        <CompletenessCard />
      </div>
    </>
  );
}

type Lists = {
  categories: CodeView[];
  religions: CodeView[];
  tongues: CodeView[];
  cwsn: CodeView[];
};

/** The grid: one row per student (a card on phones), saved together. Exported for tests. */
export function FieldsGrid({
  sectionId,
  initial,
}: {
  sectionId: string;
  initial?: SectionGrid;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManage();
  const grid = useApiData(initial ? null : `govreport:grid:${sectionId}`, () =>
    govreportApi.grid(sectionId),
  );
  const data = initial ?? grid.data;
  const lists: Lists = {
    categories: useCodes("SOCIAL_CATEGORY"),
    religions: useCodes("RELIGION"),
    tongues: useCodes("MOTHER_TONGUE"),
    cwsn: useCodes("CWSN_TYPE"),
  };
  // Only the rows the user has edited; the rest show what is saved.
  const [edits, setEdits] = useState<Record<string, RowValues>>({});
  const [errors, setErrors] = useState<Record<string, string | undefined>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const values = useMemo(
    () =>
      Object.fromEntries(
        (data?.rows ?? []).map((r) => [
          r.studentId,
          edits[r.studentId] ?? rowValues(r),
        ]),
      ),
    [data, edits],
  );
  const changed = useMemo(
    () =>
      (data?.rows ?? []).filter(
        (r) =>
          JSON.stringify(values[r.studentId]) !== JSON.stringify(rowValues(r)),
      ),
    [data, values],
  );

  const set = (studentId: string, field: keyof RowValues, value: string) => {
    setEdits((prev) => ({
      ...prev,
      [studentId]: {
        ...(prev[studentId] ?? values[studentId]),
        [field]: value,
      },
    }));
    setErrors((prev) => ({ ...prev, [`${studentId}.${field}`]: undefined }));
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Record<string, string> = {};
    for (const r of changed) {
      for (const [field, key] of Object.entries(
        rowProblems(values[r.studentId]),
      )) {
        if (key) problems[`${r.studentId}.${field}`] = t(key);
      }
    }
    if (Object.keys(problems).length) {
      setErrors(problems);
      setFormError(t("validation.fixErrors"));
      return;
    }
    if (changed.length === 0) return;
    setSaving(true);
    setFormError(null);
    try {
      const result = await govreportApi.saveGrid(
        changed.map((r) => entry(r.studentId, values[r.studentId])),
      );
      toast(t("govreport.students.saved", { count: result.saved }));
      setEdits({});
      grid.reload();
    } catch (caught) {
      const error = toApiError(caught);
      // "entries[2].apaarId" → the third changed row's field.
      const mapped: Record<string, string> = {};
      for (const [key, message] of Object.entries(error.errors ?? {})) {
        const match = /^entries\[(\d+)\]\.(\w+)$/.exec(key);
        const row = match ? changed[Number(match[1])] : undefined;
        if (row && match && message)
          mapped[`${row.studentId}.${match[2]}`] = message;
      }
      setErrors(mapped);
      setFormError(
        Object.keys(mapped).length
          ? t("validation.fixErrors")
          : errorMessage(error, t),
      );
    } finally {
      setSaving(false);
    }
  };

  if (grid.error && !data)
    return <ErrorState error={grid.error} onRetry={grid.reload} />;
  if (!data) return <LoadingRows rows={6} />;
  return (
    <form
      method="post"
      className="card flex flex-col gap-3"
      onSubmit={save}
      noValidate
      data-testid="govreport-grid"
      aria-label={t("govreport.students.title")}
    >
      <div className="card-head flex flex-wrap items-center justify-between gap-2">
        <h2>{data.sectionLabel}</h2>
        <span className="text-ink-3" data-testid="govreport-grid-summary">
          {t("govreport.students.complete", {
            complete: data.complete,
            students: data.students,
          })}
        </span>
      </div>
      <FormAlert message={formError} />
      {data.rows.length === 0 ? (
        <p className="empty">{t("govreport.students.empty")}</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {data.rows.map((r) => (
            <GridRow
              key={r.studentId}
              row={r}
              values={values[r.studentId] ?? rowValues(r)}
              errors={errors}
              lists={lists}
              disabled={!canManage}
              onChange={set}
            />
          ))}
        </ul>
      )}
      {canManage && data.rows.length > 0 ? (
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="submit"
            className="btn btn-primary"
            disabled={saving || changed.length === 0}
          >
            {saving
              ? t("common.working")
              : t("govreport.students.save", { count: changed.length })}
          </button>
        </div>
      ) : null}
    </form>
  );
}

function GridRow({
  row,
  values,
  errors,
  lists,
  disabled,
  onChange,
}: {
  row: StudentFieldsRow;
  values: RowValues;
  errors: Record<string, string | undefined>;
  lists: Lists;
  disabled: boolean;
  onChange: (studentId: string, field: keyof RowValues, value: string) => void;
}) {
  const { t } = useI18n();
  const id = row.studentId;
  const err = (field: keyof RowValues) => errors[`${id}.${field}`];
  const text = (
    field: keyof RowValues,
    extra: { maxLength: number; inputMode?: "numeric"; disabled?: boolean },
  ) => (
    <div className="field">
      <label className="flex flex-col gap-1">
        <span className="field-label text-[12.5px]">
          {fieldLabel(t, field)}
        </span>
        <input
          className="input"
          name={`${id}.${field}`}
          value={values[field]}
          onChange={(e) => onChange(id, field, e.target.value)}
          maxLength={extra.maxLength}
          inputMode={extra.inputMode}
          disabled={disabled || extra.disabled}
          aria-invalid={err(field) ? true : undefined}
        />
      </label>
      {err(field) ? <span className="field-error">{err(field)}</span> : null}
    </div>
  );
  const select = (
    field: keyof RowValues,
    options: { value: string; label: string }[],
  ) => (
    <div className="field">
      <label className="flex flex-col gap-1">
        <span className="field-label text-[12.5px]">
          {fieldLabel(t, field)}
        </span>
        <select
          className="input"
          name={`${id}.${field}`}
          value={values[field]}
          onChange={(e) => onChange(id, field, e.target.value)}
          disabled={disabled}
          aria-invalid={err(field) ? true : undefined}
        >
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      {err(field) ? <span className="field-error">{err(field)}</span> : null}
    </div>
  );
  const notSet = t("govreport.notSet");
  return (
    <li
      className="rounded-lg border border-line p-3"
      data-testid="govreport-grid-row"
    >
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <b className="font-semibold">{row.fullName}</b>
        <span className="mono text-[12.5px] text-ink-3">{row.admissionNo}</span>
        {row.missing.length === 0 ? (
          <Pill tone="good">{t("govreport.complete")}</Pill>
        ) : (
          <Pill tone="warn">
            {t("govreport.missingCount", { count: row.missing.length })}
          </Pill>
        )}
        {row.updatedVia === "FAMILY" ? (
          <Pill tone="info">{t("govreport.byFamily")}</Pill>
        ) : null}
      </div>
      <div className="grid grid-cols-2 gap-2 md:grid-cols-3 xl:grid-cols-9">
        {text("apaarId", { maxLength: 12, inputMode: "numeric" })}
        {text("pen", { maxLength: 20 })}
        {select("socialCategory", codeOptions(lists.categories, notSet))}
        {select("religion", codeOptions(lists.religions, notSet))}
        {select("motherTongue", codeOptions(lists.tongues, notSet))}
        {select("bpl", yesNoOptions(t))}
        {select("cwsnType", codeOptions(lists.cwsn, t("govreport.none")))}
        {select("rteAdmitted", yesNoOptions(t))}
        {text("previousSchoolUdise", { maxLength: 11, inputMode: "numeric" })}
      </div>
    </li>
  );
}

function ImportCard() {
  const { t } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManage();
  const [csv, setCsv] = useState("");
  const [result, setResult] = useState<GovImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (dryRun: boolean) => {
    if (!csv.trim()) {
      setError(t("govreport.import.empty"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const r = await govreportApi.importCsv(csv, dryRun);
      setResult(r);
      if (r.committed) toast(t("govreport.import.done", { count: r.saved }));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const readFile = async (file: File | undefined) => {
    if (!file) return;
    setCsv(await file.text());
    setResult(null);
  };

  if (!canManage) return null;
  return (
    <form
      method="post"
      className="card flex flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        void run(true);
      }}
      noValidate
      data-testid="govreport-import"
      aria-labelledby="gov-import"
    >
      <div className="card-head">
        <h2 id="gov-import">{t("govreport.import.title")}</h2>
      </div>
      <p className="text-[13px] text-ink-3">{t("govreport.import.hint")}</p>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className="btn btn-sm"
          onClick={() =>
            void govreportApi.download(
              govPaths.template(),
              "government-fields-template.csv",
            )
          }
        >
          <Download size={16} aria-hidden="true" />
          {t("govreport.import.template")}
        </button>
        <label className="btn btn-sm">
          <Upload size={16} aria-hidden="true" />
          {t("govreport.import.choose")}
          <input
            type="file"
            accept=".csv,text/csv"
            className="sr-only"
            name="file"
            onChange={(e) => void readFile(e.target.files?.[0])}
          />
        </label>
      </div>
      <TextAreaField
        label={t("govreport.import.csv")}
        name="csv"
        rows={5}
        value={csv}
        onChange={(e) => {
          setCsv(e.target.value);
          setResult(null);
        }}
        error={error ?? undefined}
      />
      {result ? (
        <div
          className={`alert ${result.invalidRows ? "alert-bad" : "alert-good"}`}
          role="status"
          data-testid="govreport-import-result"
        >
          <span>
            {result.committed
              ? t("govreport.import.done", { count: result.saved })
              : t("govreport.import.checked", {
                  valid: result.validRows,
                  invalid: result.invalidRows,
                })}
          </span>
        </div>
      ) : null}
      {result && result.errors.length > 0 ? (
        <div className="table-wrap">
          <table className="table">
            <caption className="sr-only">
              {t("govreport.import.errors")}
            </caption>
            <thead>
              <tr>
                <th scope="col">{t("govreport.import.row")}</th>
                <th scope="col">{t("govreport.import.column")}</th>
                <th scope="col">{t("govreport.import.problem")}</th>
              </tr>
            </thead>
            <tbody>
              {result.errors.slice(0, 50).map((e, i) => (
                <tr key={`${e.row}-${e.column}-${i}`}>
                  <td className="num">{e.row}</td>
                  <td className="mono">{e.column}</td>
                  <td>{e.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <button type="submit" className="btn" disabled={busy}>
          {t("govreport.import.check")}
        </button>
        <button
          type="button"
          className="btn btn-primary"
          disabled={
            busy || !result || result.invalidRows > 0 || result.committed
          }
          onClick={() => void run(false)}
        >
          {t("govreport.import.run")}
        </button>
      </div>
    </form>
  );
}

function CompletenessCard() {
  const { t } = useI18n();
  const report = useApiData("govreport:completeness", () =>
    govreportApi.completeness(),
  );
  const data = report.data;
  return (
    <section
      className="card"
      aria-labelledby="gov-sections"
      data-testid="govreport-completeness"
    >
      <div className="card-head">
        <h2 id="gov-sections">{t("govreport.students.bySection")}</h2>
      </div>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : (
        <>
          <p className="mb-2">
            {t("govreport.students.overall", {
              complete: data.complete,
              students: data.students,
              percent: data.percent,
            })}
          </p>
          <ul className="flex flex-col gap-1.5">
            {data.rows.map((r) => (
              <li
                key={r.sectionId}
                className="flex items-center justify-between gap-2"
              >
                <span>{r.label}</span>
                <span className="num text-ink-3">
                  {r.complete}/{r.students} · {r.percent}%
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      <VerifyNote className="mt-3" />
    </section>
  );
}
