"use client";

import { Download } from "lucide-react";
import { useState } from "react";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill, type PillTone } from "@/components/ui/pill";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, parseRupees } from "@/lib/format";
import { govPaths, govreportApi } from "@/lib/govreport-api";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { SCHEME_STATUSES, type SchemeStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  codeOptions,
  GovReportNav,
  useCanManage,
  useCodes,
  useYears,
  VerifyNote,
  YearSelect,
} from "./govreport-shared";

/* /app/govreport/schemes: the scholarship and scheme register, with an export per scheme. */

const TONE: Record<SchemeStatus, PillTone> = {
  APPLIED: "info",
  APPROVED: "accent",
  RECEIVED: "good",
  REJECTED: "bad",
};

export function SchemesView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManage();
  const schemes = useCodes("SCHEME");
  const { current } = useYears();
  const [chosenYear, setChosenYear] = useState("");
  const [scheme, setScheme] = useState("");
  const yearId = chosenYear || current;
  const list = useApiData(
    yearId ? `govreport:schemes:${yearId}:${scheme}` : null,
    () => govreportApi.schemes(yearId, scheme || undefined),
  );
  const [error, setError] = useState<string | null>(null);

  const changeStatus = async (id: string, status: SchemeStatus) => {
    const row = list.data?.find((r) => r.id === id);
    if (!row) return;
    setError(null);
    try {
      await govreportApi.updateScheme(id, {
        status,
        amountPaise: row.amountPaise,
        referenceNo: row.referenceNo,
        appliedOn: row.appliedOn,
        notes: row.notes,
      });
      list.reload();
      toast(t("govreport.schemes.updated"));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    }
  };

  return (
    <>
      <PageHead
        eyebrow={t("nav.govreport")}
        title={t("govreport.schemes.title")}
      />
      <GovReportNav />
      <VerifyNote />
      <form
        method="post"
        className="toolbar"
        onSubmit={(e) => e.preventDefault()}
        aria-label={t("reports.filters")}
      >
        <YearSelect value={yearId} onChange={setChosenYear} />
        <SelectField
          label={t("govreport.schemes.scheme")}
          name="schemeCode"
          value={scheme}
          onChange={(e) => setScheme(e.target.value)}
          options={codeOptions(schemes, t("govreport.schemes.all"))}
          className="w-full sm:w-auto sm:min-w-52"
        />
        <button
          type="button"
          className="btn self-end"
          onClick={() =>
            void govreportApi.download(
              govPaths.schemesCsv({ yearId, schemeCode: scheme || undefined }),
              "schemes.csv",
            )
          }
          data-testid="govreport-schemes-csv"
        >
          <Download size={18} aria-hidden="true" />
          {t("govreport.exports.csv")}
        </button>
      </form>
      <FormAlert message={error} />
      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-3">
        <section
          className="card xl:col-span-2"
          aria-labelledby="gov-schemes"
          data-testid="govreport-schemes"
        >
          <div className="card-head">
            <h2 id="gov-schemes">{t("govreport.schemes.register")}</h2>
          </div>
          {list.error && !list.data ? (
            <ErrorState error={list.error} onRetry={list.reload} />
          ) : !list.data ? (
            <LoadingRows rows={4} />
          ) : list.data.length === 0 ? (
            <p className="empty">{t("govreport.schemes.empty")}</p>
          ) : (
            <ul className="rowcards">
              {list.data.map((r) => (
                <li
                  key={r.id}
                  className="rowcard flex-wrap"
                  data-testid="govreport-scheme-row"
                >
                  <span className="min-w-0 flex-1">
                    <b className="block font-semibold">{r.fullName}</b>
                    <span className="block text-[13px] text-ink-3">
                      {[
                        r.schemeLabel ?? r.schemeCode,
                        [r.className, r.sectionName].filter(Boolean).join(" "),
                        r.amountPaise !== null
                          ? formatPaise(r.amountPaise)
                          : null,
                        r.referenceNo,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </span>
                  {canManage ? (
                    <select
                      className="input w-auto"
                      aria-label={t("govreport.schemes.status")}
                      value={r.status}
                      onChange={(e) =>
                        void changeStatus(r.id, e.target.value as SchemeStatus)
                      }
                    >
                      {SCHEME_STATUSES.map((s) => (
                        <option key={s} value={s}>
                          {t(`govreport.schemes.status.${s}` as MessageKey)}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <Pill tone={TONE[r.status]}>
                      {t(`govreport.schemes.status.${r.status}` as MessageKey)}
                    </Pill>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
        {canManage && yearId ? (
          <EnrolCard yearId={yearId} onSaved={list.reload} />
        ) : null}
      </div>
    </>
  );
}

type EnrolValues = {
  sectionId: string;
  studentId: string;
  schemeCode: string;
  status: SchemeStatus;
  amount: string;
  referenceNo: string;
  appliedOn: string;
};

/** Client checks mirroring the API. Exported for tests. */
export function validateEnrol(v: EnrolValues): Problems {
  const problems: Problems = {};
  if (!v.studentId) problems.studentId = "govreport.v.student";
  if (!v.schemeCode) problems.schemeCode = "govreport.v.scheme";
  if (v.amount.trim() && parseRupees(v.amount) === null)
    problems.amountPaise = "govreport.v.amount";
  return problems;
}

function EnrolCard({
  yearId,
  onSaved,
}: {
  yearId: string;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const schemes = useCodes("SCHEME");
  const classes = useApiData("govreport:classes", api.listClasses).data ?? [];
  const sections = classes.flatMap((c) =>
    c.sections.map((s) => ({ value: s.id, label: `${c.name} ${s.name}` })),
  );
  const blank: EnrolValues = {
    sectionId: "",
    studentId: "",
    schemeCode: "",
    status: "APPLIED",
    amount: "",
    referenceNo: "",
    appliedOn: "",
  };
  const form = useForm<EnrolValues>(blank);
  const grid = useApiData(
    form.values.sectionId ? `govreport:grid:${form.values.sectionId}` : null,
    () => govreportApi.grid(form.values.sectionId),
  );
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateEnrol(form.values), t, event.currentTarget)) return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await govreportApi.enrol({
        studentId: v.studentId,
        academicYearId: yearId,
        schemeCode: v.schemeCode,
        status: v.status,
        amountPaise: v.amount.trim() ? parseRupees(v.amount) : null,
        referenceNo: v.referenceNo.trim() || null,
        appliedOn: v.appliedOn || null,
      });
      form.reset({ ...blank, sectionId: v.sectionId });
      onSaved();
    });
    if (ok) toast(t("govreport.schemes.enrolled"));
  };
  return (
    <form
      method="post"
      className="card flex flex-col gap-3"
      onSubmit={submit}
      noValidate
      data-testid="govreport-enrol"
      aria-labelledby="gov-enrol"
    >
      <div className="card-head">
        <h2 id="gov-enrol">{t("govreport.schemes.enrol")}</h2>
      </div>
      <FormAlert message={form.formError} />
      <SelectField
        label={t("govreport.section")}
        name="sectionId"
        value={form.values.sectionId}
        onChange={(e) => form.set("sectionId", e.target.value)}
        options={[{ value: "", label: t("govreport.choose") }, ...sections]}
      />
      <SelectField
        label={t("govreport.schemes.student")}
        name="studentId"
        value={form.values.studentId}
        onChange={(e) => form.set("studentId", e.target.value)}
        options={[
          { value: "", label: t("govreport.choose") },
          ...(grid.data?.rows ?? []).map((r) => ({
            value: r.studentId,
            label: `${r.fullName} (${r.admissionNo})`,
          })),
        ]}
        error={form.errors.studentId}
      />
      <SelectField
        label={t("govreport.schemes.scheme")}
        name="schemeCode"
        value={form.values.schemeCode}
        onChange={(e) => form.set("schemeCode", e.target.value)}
        options={codeOptions(schemes, t("govreport.choose"))}
        error={form.errors.schemeCode}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        <SelectField
          label={t("govreport.schemes.status")}
          name="status"
          value={form.values.status}
          onChange={(e) => form.set("status", e.target.value as SchemeStatus)}
          options={SCHEME_STATUSES.map((s) => ({
            value: s,
            label: t(`govreport.schemes.status.${s}` as MessageKey),
          }))}
        />
        <TextField
          label={t("govreport.schemes.amount")}
          name="amountPaise"
          inputMode="decimal"
          value={form.values.amount}
          onChange={(e) => form.set("amount", e.target.value)}
          error={form.errors.amountPaise}
        />
        <TextField
          label={t("govreport.schemes.reference")}
          name="referenceNo"
          maxLength={60}
          value={form.values.referenceNo}
          onChange={(e) => form.set("referenceNo", e.target.value)}
          error={form.errors.referenceNo}
        />
        <TextField
          label={t("govreport.schemes.appliedOn")}
          name="appliedOn"
          type="date"
          value={form.values.appliedOn}
          onChange={(e) => form.set("appliedOn", e.target.value)}
          error={form.errors.appliedOn}
        />
      </div>
      <div>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={form.submitting}
        >
          {form.submitting ? t("common.working") : t("govreport.schemes.save")}
        </button>
      </div>
    </form>
  );
}
