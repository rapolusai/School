"use client";

import { useEffect, useState } from "react";
import { SelectField, TextField } from "@/components/ui/field";
import {
  ErrorState,
  FormAlert,
  LoadingRows,
  PageHead,
} from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatDateTime } from "@/lib/format";
import { govreportApi } from "@/lib/govreport-api";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import type { GovProfile, ProfileFields } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  codeOptions,
  fieldLabel,
  fromYesNo,
  GovReportNav,
  useCanManage,
  useCodes,
  useYears,
  VerifyNote,
  YearSelect,
  yesNo,
  yesNoOptions,
} from "./govreport-shared";

/* /app/govreport: the school's government profile for a year, its completeness and teacher counts from staff. */

type Kind = "text" | "date" | "number" | "bool" | "category" | "management";
type FieldSpec = { name: keyof ProfileFields; kind: Kind; max?: number };

const SECTIONS: { titleKey: MessageKey; fields: FieldSpec[] }[] = [
  {
    titleKey: "govreport.profile.identity",
    fields: [
      { name: "udiseCode", kind: "text", max: 11 },
      { name: "board", kind: "text", max: 20 },
      { name: "affiliationNo", kind: "text", max: 60 },
      { name: "affiliationValidUntil", kind: "date" },
      { name: "schoolCategoryCode", kind: "category" },
      { name: "managementCode", kind: "management" },
      { name: "stateCode", kind: "text", max: 2 },
      { name: "diseDistrictCode", kind: "text", max: 10 },
      { name: "diseBlockCode", kind: "text", max: 10 },
      { name: "recognitionAuthority", kind: "text", max: 120 },
      { name: "recognitionNo", kind: "text", max: 60 },
      { name: "recognitionValidUntil", kind: "date" },
      { name: "lowestClass", kind: "text", max: 20 },
      { name: "highestClass", kind: "text", max: 20 },
      { name: "mediumOfInstruction", kind: "text", max: 60 },
    ],
  },
  {
    titleKey: "govreport.profile.infrastructure",
    fields: [
      { name: "classrooms", kind: "number", max: 999 },
      { name: "otherRooms", kind: "number", max: 999 },
      { name: "toiletsBoys", kind: "number", max: 999 },
      { name: "toiletsGirls", kind: "number", max: 999 },
      { name: "toiletsCwsn", kind: "number", max: 999 },
      { name: "computers", kind: "number", max: 99999 },
      { name: "libraryBooks", kind: "number", max: 9999999 },
      { name: "drinkingWaterSource", kind: "text", max: 60 },
    ],
  },
  {
    titleKey: "govreport.profile.facilities",
    fields: [
      { name: "drinkingWater", kind: "bool" },
      { name: "electricity", kind: "bool" },
      { name: "library", kind: "bool" },
      { name: "playground", kind: "bool" },
      { name: "internet", kind: "bool" },
      { name: "ramps", kind: "bool" },
      { name: "boundaryWall", kind: "bool" },
    ],
  },
];

const ALL_FIELDS = SECTIONS.flatMap((s) => s.fields);
type Values = Record<string, string>;

function toValues(p: GovProfile): Values {
  const v: Values = {};
  for (const f of ALL_FIELDS) {
    const raw = p[f.name];
    v[f.name] =
      f.kind === "bool"
        ? yesNo(raw as boolean | null)
        : raw === null || raw === undefined
          ? ""
          : String(raw);
  }
  return v;
}

/** Client checks mirroring the API: an 11-digit UDISE code, whole numbers within range, a 2-letter state. */
export function validateProfile(v: Values): Problems {
  const problems: Problems = {};
  if (v.udiseCode && !/^\d{11}$/.test(v.udiseCode.trim()))
    problems.udiseCode = "govreport.v.udise";
  if (v.stateCode && !/^[A-Za-z]{2}$/.test(v.stateCode.trim()))
    problems.stateCode = "govreport.v.state";
  for (const f of ALL_FIELDS) {
    if (f.kind !== "number" || !v[f.name]) continue;
    const n = Number(v[f.name]);
    if (!Number.isInteger(n) || n < 0 || n > (f.max ?? 999))
      problems[f.name] = "govreport.v.count";
  }
  return problems;
}

function toBody(v: Values): Partial<ProfileFields> {
  const body: Record<string, unknown> = {};
  for (const f of ALL_FIELDS) {
    const text = (v[f.name] ?? "").trim();
    if (f.kind === "bool") body[f.name] = fromYesNo(text);
    else if (f.kind === "number")
      body[f.name] = text === "" ? null : Number(text);
    else if (f.name === "stateCode")
      body[f.name] = text ? text.toUpperCase() : null;
    else body[f.name] = text || null;
  }
  return body as Partial<ProfileFields>;
}

export function ProfileView() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManage();
  const { current } = useYears();
  const [chosen, setChosen] = useState("");
  const yearId = chosen || current;
  const profile = useApiData(
    yearId ? `govreport:profile:${yearId}` : null,
    () => govreportApi.profile(yearId),
  );
  const categories = useCodes("SCHOOL_CATEGORY");
  const managements = useCodes("MANAGEMENT");
  const form = useForm<Values>({});
  const { reset } = form;
  useEffect(() => {
    if (profile.data) reset(toValues(profile.data));
  }, [profile.data, reset]);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateProfile(form.values), t, event.currentTarget))
      return;
    const ok = await form.submit(t, async () => {
      const saved = await govreportApi.saveProfile(toBody(form.values), yearId);
      reset(toValues(saved));
      profile.reload();
    });
    if (ok) toast(t("govreport.profile.saved"));
  };

  const data = profile.data;
  const input = (f: FieldSpec) => {
    const common = {
      label: fieldLabel(t, f.name),
      name: f.name,
      value: form.values[f.name] ?? "",
      error: form.errors[f.name],
      disabled: !canManage,
    };
    if (f.kind === "bool") {
      return (
        <SelectField
          key={f.name}
          {...common}
          options={yesNoOptions(t)}
          onChange={(e) => form.set(f.name, e.target.value)}
        />
      );
    }
    if (f.kind === "category" || f.kind === "management") {
      const list = f.kind === "category" ? categories : managements;
      return (
        <SelectField
          key={f.name}
          {...common}
          options={codeOptions(list, t("govreport.notSet"))}
          onChange={(e) => form.set(f.name, e.target.value)}
        />
      );
    }
    return (
      <TextField
        key={f.name}
        {...common}
        type={f.kind === "date" ? "date" : "text"}
        inputMode={
          f.kind === "number" || f.name === "udiseCode" ? "numeric" : undefined
        }
        maxLength={f.kind === "text" ? f.max : undefined}
        onChange={(e) => form.set(f.name, e.target.value)}
      />
    );
  };

  return (
    <>
      <PageHead
        eyebrow={t("nav.govreport")}
        title={t("govreport.profile.title")}
      />
      <GovReportNav />
      <VerifyNote />
      <div className="toolbar">
        <YearSelect value={yearId} onChange={setChosen} />
      </div>
      {profile.error && !data ? (
        <ErrorState error={profile.error} onRetry={profile.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-3">
          <form
            method="post"
            className="card flex flex-col gap-4 xl:col-span-2"
            onSubmit={submit}
            noValidate
            data-testid="govreport-profile-form"
            aria-label={t("govreport.profile.title")}
          >
            <FormAlert message={form.formError} />
            {SECTIONS.map((section) => (
              <fieldset key={section.titleKey} className="flex flex-col gap-3">
                <legend className="mb-2 font-semibold">
                  {t(section.titleKey)}
                </legend>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {section.fields.map(input)}
                </div>
              </fieldset>
            ))}
            {canManage ? (
              <div>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={form.submitting}
                >
                  {form.submitting
                    ? t("common.working")
                    : t("govreport.profile.save")}
                </button>
              </div>
            ) : null}
          </form>
          <div className="flex flex-col gap-3.5">
            <section
              className="card"
              aria-labelledby="gov-completeness"
              data-testid="govreport-profile-completeness"
            >
              <div className="card-head">
                <h2 id="gov-completeness">
                  {t("govreport.profile.completeness")}
                </h2>
              </div>
              <p className="text-2xl font-semibold num">
                {data.completeness.percent}%
              </p>
              <p className="text-ink-3">
                {t("govreport.profile.filled", {
                  filled: data.completeness.filled,
                  total: data.completeness.total,
                })}
              </p>
              {data.completeness.missing.length > 0 ? (
                <p className="mt-2 text-[13px]">
                  {t("govreport.profile.missing")}:{" "}
                  {data.completeness.missing
                    .map((f) => fieldLabel(t, f))
                    .join(", ")}
                </p>
              ) : null}
              {data.updatedAt ? (
                <p className="mt-2 text-[13px] text-ink-3">
                  {t("govreport.updatedAt", {
                    date: formatDateTime(data.updatedAt, localeFor(lang)),
                  })}
                </p>
              ) : null}
            </section>
            <section
              className="card"
              aria-labelledby="gov-teachers"
              data-testid="govreport-teachers"
            >
              <div className="card-head">
                <h2 id="gov-teachers">{t("govreport.teachers.title")}</h2>
              </div>
              <dl className="kv">
                <dt>{t("govreport.teachers.total")}</dt>
                <dd className="num">{data.teachers.total}</dd>
                <dt>{t("govreport.teachers.trained")}</dt>
                <dd className="num">{data.teachers.trained}</dd>
                <dt>{t("govreport.teachers.postGraduate")}</dt>
                <dd className="num">{data.teachers.postGraduate}</dd>
                <dt>{t("govreport.teachers.graduate")}</dt>
                <dd className="num">{data.teachers.graduate}</dd>
                <dt>{t("govreport.teachers.other")}</dt>
                <dd className="num">{data.teachers.other}</dd>
                <dt>{t("govreport.teachers.incomplete")}</dt>
                <dd className="num">{data.teachers.profileIncomplete}</dd>
              </dl>
              <p className="mt-2 text-[13px] text-ink-3">
                {t("govreport.teachers.hint")}
              </p>
            </section>
          </div>
        </div>
      )}
    </>
  );
}
