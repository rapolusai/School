"use client";

import { CircleCheck, School } from "lucide-react";
import { useState } from "react";
import { LanguageSelect, ThemeToggle } from "@/components/preferences";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { admissionsApi } from "@/lib/admissions-api";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPhone, todayInIndia } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import { GUARDIAN_RELATIONS, type GuardianRelation, type PublicSchoolInfo, type YearChoice } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate, isValidEmail, isValidIndianMobile, normalizeIndianMobile } from "@/lib/validation";
import { yearsBefore } from "./admission-time";

export type EnquiryValues = {
  parentName: string;
  mobile: string;
  relation: "" | GuardianRelation;
  email: string;
  childFirstName: string;
  childLastName: string;
  dateOfBirth: string;
  className: string;
  academicYear: "" | YearChoice;
  message: string;
  consent: boolean;
  /** Honeypot, hidden from people. */
  website: string;
};

function emptyEnquiry(info?: PublicSchoolInfo): EnquiryValues {
  return {
    parentName: "",
    mobile: "",
    relation: "",
    email: "",
    childFirstName: "",
    childLastName: "",
    dateOfBirth: "",
    className: "",
    academicYear: info?.years.length === 1 ? info.years[0].code : "",
    message: "",
    consent: false,
    website: "",
  };
}

/** The same checks as the API. */
export function validateEnquiry(values: EnquiryValues, today: string): Problems {
  const problems: Problems = {};
  if (!values.parentName.trim()) problems.parentName = "validation.required";
  else if (values.parentName.trim().length > 200) problems.parentName = "validation.tooLong";
  if (!values.mobile.trim()) problems.mobile = "validation.required";
  else if (!isValidIndianMobile(values.mobile)) problems.mobile = "students.v.phone";
  if (!values.relation) problems.relation = "validation.choose";
  if (values.email.trim() && !isValidEmail(values.email)) problems.email = "validation.email";
  if (!values.childFirstName.trim()) problems.childFirstName = "validation.required";
  else if (values.childFirstName.trim().length > 100) problems.childFirstName = "validation.tooLong";
  if (values.childLastName.trim().length > 100) problems.childLastName = "validation.tooLong";
  if (!isPlainDate(values.dateOfBirth)) problems.dateOfBirth = "validation.date";
  else if (values.dateOfBirth >= today) problems.dateOfBirth = "students.v.dobPast";
  else if (values.dateOfBirth < yearsBefore(today, 25)) problems.dateOfBirth = "admissions.v.dob";
  if (!values.className) problems.className = "admissions.v.class";
  if (!values.academicYear) problems.academicYear = "admissions.v.year";
  if (values.message.trim().length > 1000) problems.message = "validation.tooLong";
  if (!values.consent) problems.consent = "admissions.public.v.consent";
  return problems;
}

type Sent = { parentName: string; childName: string; mobile: string };

/** A school's public admission enquiry page: no sign-in and no app shell. */
export function PublicEnquiryView({ schoolCode }: { schoolCode: string }) {
  const { t } = useI18n();
  const info = useApiData(`public:admission-info:${schoolCode}`, () => admissionsApi.publicInfo(schoolCode));
  const school = info.data;

  return (
    <div className="enquiry-page">
      <main className="enquiry-main" id="main">
        {info.error && !school ? (
          <section className="card" data-testid="enquiry-unavailable">
            {info.error.status === 404 ? (
              <>
                <h1 className="mb-2">{t("admissions.public.notFoundTitle")}</h1>
                <p className="text-ink-2">{t("admissions.public.notFound")}</p>
              </>
            ) : info.error.status === 429 ? (
              <p className="alert alert-bad" role="alert">
                {t("admissions.public.tooMany")}
              </p>
            ) : (
              <ErrorState error={info.error} onRetry={info.reload} />
            )}
          </section>
        ) : !school ? (
          <section className="card">
            <LoadingRows rows={6} />
          </section>
        ) : (
          <EnquiryForm key={schoolCode} schoolCode={schoolCode} school={school} />
        )}
      </main>
      <footer className="enquiry-foot">
        <p>{t("admissions.public.poweredBy")}</p>
        <div className="flex flex-wrap items-center justify-center gap-2">
          <LanguageSelect />
          <ThemeToggle withLabel />
        </div>
      </footer>
    </div>
  );
}

function EnquiryForm({ schoolCode, school }: { schoolCode: string; school: PublicSchoolInfo }) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const form = useForm<EnquiryValues>(emptyEnquiry(school));
  const { values, errors } = form;
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState<Sent | null>(null);
  const [gone, setGone] = useState(false);

  const boardName = translateOr(t, `board.${school.board}`, school.board);
  const set = <K extends keyof EnquiryValues>(field: K, value: EnquiryValues[K]) => form.set(field, value);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (sending) return;
    if (!form.check(validateEnquiry(values, today), t, event.currentTarget)) return;
    setSending(true);
    form.setFormError(null);
    try {
      await admissionsApi.sendEnquiry(schoolCode, {
        parentName: values.parentName.trim(),
        relation: values.relation as GuardianRelation,
        mobile: values.mobile.trim(),
        email: values.email.trim() || null,
        childFirstName: values.childFirstName.trim(),
        childLastName: values.childLastName.trim() || null,
        dateOfBirth: values.dateOfBirth,
        className: values.className,
        academicYear: values.academicYear as YearChoice,
        message: values.message.trim() || null,
        consent: values.consent,
        consentVersion: school.consentVersion,
        website: values.website,
      });
      setSent({
        parentName: values.parentName.trim(),
        childName: [values.childFirstName.trim(), values.childLastName.trim()].filter(Boolean).join(" "),
        mobile: normalizeIndianMobile(values.mobile) ?? values.mobile.trim(),
      });
      form.reset(emptyEnquiry(school));
    } catch (caught) {
      const error = toApiError(caught);
      if (error.status === 404) setGone(true);
      else if (error.status === 429) form.setFormError(t("admissions.public.tooMany"));
      else if (error.errors && Object.keys(error.errors).length) {
        form.setErrors(error.errors);
        form.setFormError(error.errors.consent ?? t("validation.fixErrors"));
      } else form.setFormError(errorMessage(error, t));
    } finally {
      setSending(false);
    }
  };

  const header = (
    <header className="enquiry-head">
      <span className="badge-ic" aria-hidden="true">
        <School size={20} />
      </span>
      <div className="min-w-0">
        <p className="eyebrow">{t("admissions.public.eyebrow")}</p>
        <h1 className="mt-0.5" data-testid="enquiry-school">
          {school.name}
        </h1>
        <p className="text-sm text-ink-2">{[boardName, school.city].filter(Boolean).join(" · ")}</p>
      </div>
    </header>
  );

  if (gone) {
    return (
      <section className="card">
        <h1 className="mb-2">{t("admissions.public.notFoundTitle")}</h1>
        <p className="text-ink-2">{t("admissions.public.notFound")}</p>
      </section>
    );
  }

  if (sent) {
    return (
      <section className="card flex flex-col gap-4" data-testid="enquiry-sent">
        {header}
        <div className="flex items-start gap-3" role="status">
          <span className="badge-ic" style={{ background: "var(--good-soft)", color: "var(--good)" }}>
            <CircleCheck size={20} aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h2>{t("admissions.public.sentTitle", { name: sent.parentName })}</h2>
            <p className="mt-1 text-ink-2">
              {t("admissions.public.sentBody", { school: school.name, child: sent.childName, mobile: formatPhone(sent.mobile) })}
            </p>
          </div>
        </div>
        <button type="button" className="btn self-start" onClick={() => setSent(null)}>
          {t("admissions.public.another")}
        </button>
      </section>
    );
  }

  return (
    <section className="card flex flex-col gap-4">
      {header}
      <p className="text-ink-2">{t("admissions.public.intro")}</p>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <fieldset className="fieldset">
          <legend>{t("admissions.public.you")}</legend>
          <TextField
            label={t("admissions.public.parentName")}
            name="parentName"
            autoComplete="name"
            maxLength={200}
            value={values.parentName}
            onChange={(e) => set("parentName", e.target.value)}
            error={errors.parentName}
            required
          />
          <div className="grid2">
            <TextField
              label={t("admissions.public.mobile")}
              name="mobile"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              maxLength={20}
              placeholder="98765 43210"
              hint={t("admissions.public.mobile.hint")}
              value={values.mobile}
              onChange={(e) => set("mobile", e.target.value)}
              error={errors.mobile}
              required
            />
            <SelectField
              label={t("admissions.public.relation")}
              name="relation"
              value={values.relation}
              onChange={(e) => set("relation", e.target.value as EnquiryValues["relation"])}
              error={errors.relation}
              required
              options={[
                { value: "", label: t("common.choose") },
                ...GUARDIAN_RELATIONS.map((r) => ({ value: r, label: translateOr(t, `relation.${r}`, r) })),
              ]}
            />
          </div>
          <TextField
            label={t("admissions.public.email")}
            name="email"
            type="email"
            autoComplete="email"
            maxLength={254}
            value={values.email}
            onChange={(e) => set("email", e.target.value)}
            error={errors.email}
          />
        </fieldset>

        <fieldset className="fieldset">
          <legend>{t("admissions.public.child")}</legend>
          <div className="grid2">
            <TextField
              label={t("students.field.firstName")}
              name="childFirstName"
              autoComplete="off"
              maxLength={100}
              value={values.childFirstName}
              onChange={(e) => set("childFirstName", e.target.value)}
              error={errors.childFirstName}
              required
            />
            <TextField
              label={t("students.field.lastName")}
              name="childLastName"
              autoComplete="off"
              maxLength={100}
              value={values.childLastName}
              onChange={(e) => set("childLastName", e.target.value)}
              error={errors.childLastName}
            />
          </div>
          <div className="grid2">
            <TextField
              label={t("students.field.dateOfBirth")}
              name="dateOfBirth"
              type="date"
              max={today}
              value={values.dateOfBirth}
              onChange={(e) => set("dateOfBirth", e.target.value)}
              error={errors.dateOfBirth}
              required
            />
            <SelectField
              label={t("admissions.public.class")}
              name="className"
              value={values.className}
              onChange={(e) => set("className", e.target.value)}
              error={errors.className}
              required
              options={[
                { value: "", label: t("common.choose") },
                ...school.classes.map((name) => ({ value: name, label: name })),
              ]}
            />
          </div>
          <div className="field">
            <span className="field-label" id="enquiry-year-label">
              {t("admissions.public.year")}
            </span>
            <div className="seg self-start" role="radiogroup" aria-labelledby="enquiry-year-label">
              {school.years.map((year) => (
                <label key={year.code}>
                  <input
                    type="radio"
                    name="academicYear"
                    value={year.code}
                    checked={values.academicYear === year.code}
                    onChange={() => set("academicYear", year.code)}
                  />
                  {year.name}
                </label>
              ))}
            </div>
            {errors.academicYear ? <p className="field-error">{errors.academicYear}</p> : null}
          </div>
        </fieldset>

        <TextAreaField
          label={t("admissions.public.message")}
          name="message"
          rows={3}
          maxLength={1000}
          hint={t("admissions.public.message.hint")}
          value={values.message}
          onChange={(e) => set("message", e.target.value)}
          error={errors.message}
        />

        <div className="hp-field" aria-hidden="true">
          <label>
            {t("admissions.public.website")}
            <input
              type="text"
              name="website"
              tabIndex={-1}
              autoComplete="off"
              value={values.website}
              onChange={(e) => set("website", e.target.value)}
            />
          </label>
        </div>

        <div className="field">
          <label className="check items-start">
            <input
              type="checkbox"
              name="consent"
              checked={values.consent}
              onChange={(e) => set("consent", e.target.checked)}
              aria-invalid={errors.consent ? true : undefined}
              aria-describedby={errors.consent ? "enquiry-consent-error" : undefined}
            />
            <span>{t("admissions.public.consent", { school: school.name })}</span>
          </label>
          {errors.consent ? (
            <p id="enquiry-consent-error" className="field-error">
              {errors.consent}
            </p>
          ) : null}
        </div>

        <button type="submit" className="btn btn-primary btn-lg w-full sm:w-auto sm:self-start" disabled={sending}>
          {sending ? t("admissions.public.sending") : t("admissions.public.submit")}
        </button>
      </form>
    </section>
  );
}
