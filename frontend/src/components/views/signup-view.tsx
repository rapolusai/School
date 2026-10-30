"use client";

import { ArrowLeft, ArrowRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { AuthLayout } from "@/components/auth-layout";
import { SelectField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { Pill } from "@/components/ui/pill";
import { planLabel } from "@/components/views/billing/billing-ui";
import { api, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { billingApi } from "@/lib/billing-api";
import { errorMessage, fieldErrorMessages } from "@/lib/error-message";
import { formatPaise } from "@/lib/format";
import { plural, translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { BOARDS, PLANS, type Board, type Plan, type PlanView, type SignupRequest } from "@/lib/types";
import {
  isValidEmail,
  isValidSchoolCode,
  MIN_PASSWORD_LENGTH,
  suggestSchoolCode,
} from "@/lib/validation";

type Values = Required<SignupRequest>;
type Field = keyof Values;
type Errors = Partial<Record<Field, string>>;

const TOTAL_STEPS = 3;
const STEP_FIELDS: Record<1 | 2, Field[]> = {
  1: ["schoolName", "schoolCode", "board", "city"],
  2: ["adminName", "adminEmail", "password"],
};

/** Client-side checks for one wizard step. Returns message keys per invalid field. */
export function validateSignupStep(step: 1 | 2, values: SignupRequest): Partial<Record<Field, MessageKey>> {
  const errors: Partial<Record<Field, MessageKey>> = {};
  if (step === 1) {
    if (!values.schoolName.trim()) errors.schoolName = "validation.required";
    if (!isValidSchoolCode(values.schoolCode)) errors.schoolCode = "validation.schoolCode";
    if (!values.city.trim()) errors.city = "validation.required";
  } else {
    if (!values.adminName.trim()) errors.adminName = "validation.required";
    if (!isValidEmail(values.adminEmail)) errors.adminEmail = "validation.email";
    if (values.password.length < MIN_PASSWORD_LENGTH) errors.password = "validation.password";
  }
  return errors;
}

function stepOf(field: string): 1 | 2 | 3 {
  if ((STEP_FIELDS[1] as string[]).includes(field)) return 1;
  if ((STEP_FIELDS[2] as string[]).includes(field)) return 2;
  return 3;
}

export function SignupView() {
  const { t } = useI18n();
  const auth = useAuth();
  const router = useRouter();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const formRef = useRef<HTMLFormElement>(null);

  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [values, setValues] = useState<Values>({
    schoolName: "",
    schoolCode: "",
    board: "CBSE",
    city: "",
    adminName: "",
    adminEmail: "",
    password: "",
    plan: "STARTER",
  });
  const [plans, setPlans] = useState<PlanView[] | null>(null);
  const [codeEdited, setCodeEdited] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // The catalogue only adds prices and limits to the plan choice; without it the plans are listed by name.
  useEffect(() => {
    let live = true;
    billingApi
      .publicPlans()
      .then((list) => {
        if (live) setPlans(list);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, []);

  // Announce each new step to assistive tech by moving focus to its heading.
  const shownStep = useRef(step);
  useEffect(() => {
    if (shownStep.current === step) return;
    shownStep.current = step;
    headingRef.current?.focus();
  }, [step]);

  const set = <K extends Field>(field: K, value: Values[K]) => {
    setValues((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const onSchoolName = (name: string) => {
    setValues((prev) => ({
      ...prev,
      schoolName: name,
      schoolCode: codeEdited ? prev.schoolCode : suggestSchoolCode(name),
    }));
    setErrors((prev) => ({ ...prev, schoolName: undefined, schoolCode: undefined }));
  };

  const onSchoolCode = (raw: string) => {
    const code = raw.toLowerCase().replace(/\s+/g, "-");
    setCodeEdited(code !== "");
    set("schoolCode", code);
  };

  const focusField = (field: string) => {
    window.requestAnimationFrame(() => {
      formRef.current?.querySelector<HTMLElement>(`[name="${field}"]`)?.focus();
    });
  };

  const submit = async () => {
    setSubmitting(true);
    setFormError(null);
    const body: Values = {
      ...values,
      schoolName: values.schoolName.trim(),
      city: values.city.trim(),
      adminName: values.adminName.trim(),
      adminEmail: values.adminEmail.trim(),
    };
    try {
      await api.signup(body);
    } catch (caught) {
      const error = toApiError(caught);
      setSubmitting(false);
      const serverErrors: Errors = { ...(error.errors ? (fieldErrorMessages(error.errors, t) as Errors) : undefined) };
      // The only value that can clash for a brand-new school is its code.
      if (error.status === 409 && Object.keys(serverErrors).length === 0) {
        serverErrors.schoolCode = errorMessage(error, t);
      }
      const fields = Object.keys(serverErrors);
      if (fields.length) {
        const earliest = Math.min(...fields.map(stepOf)) as 1 | 2 | 3;
        setErrors(serverErrors);
        if (earliest < 3) {
          setStep(earliest);
          focusField(fields.find((f) => stepOf(f) === earliest) ?? fields[0]);
          return;
        }
      }
      setFormError(errorMessage(error, t));
      return;
    }
    try {
      await auth.login(body.schoolCode, body.adminEmail, body.password);
      router.replace("/app/dashboard");
    } catch {
      router.replace(`/login?school=${encodeURIComponent(body.schoolCode)}&signup=done`);
    }
  };

  const onSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    if (step === 3) {
      void submit();
      return;
    }
    const found = validateSignupStep(step, values);
    const fields = Object.keys(found) as Field[];
    if (fields.length) {
      setErrors(
        Object.fromEntries(fields.map((f) => [f, t(found[f] as MessageKey)])) as Errors,
      );
      focusField(fields[0]);
      return;
    }
    setStep((step + 1) as 2 | 3);
  };

  const boardOptions = BOARDS.map((b) => ({ value: b, label: t(`board.${b}` as MessageKey) }));
  const titles: Record<1 | 2 | 3, MessageKey> = {
    1: "signup.step1.title",
    2: "signup.step2.title",
    3: "signup.step3.title",
  };

  return (
    <AuthLayout title={t("signup.art.title")} body={t("signup.art.body")}>
      <div className="flex flex-col gap-4">
        <div className="steps" aria-hidden="true">
          {[1, 2, 3].map((i) => (
            <span key={i} className={i <= step ? "on" : ""} />
          ))}
        </div>
        <div>
          <p className="eyebrow">{t("signup.step", { step, total: TOTAL_STEPS })}</p>
          <h1 ref={headingRef} tabIndex={-1} className="mt-1 outline-none">
            {t(titles[step])}
          </h1>
        </div>
      </div>

      <form ref={formRef} method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={formError} />

        {step === 1 ? (
          <>
            <TextField
              label={t("signup.schoolName")}
              name="schoolName"
              autoComplete="organization"
              placeholder={t("signup.schoolName.placeholder")}
              value={values.schoolName}
              onChange={(e) => onSchoolName(e.target.value)}
              error={errors.schoolName}
              required
            />
            <TextField
              label={t("signup.schoolCode")}
              name="schoolCode"
              autoCapitalize="none"
              spellCheck={false}
              className="[&_input]:font-mono"
              value={values.schoolCode}
              onChange={(e) => onSchoolCode(e.target.value)}
              hint={t("signup.schoolCode.hint")}
              error={errors.schoolCode}
              maxLength={40}
              required
            />
            <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2">
              <SelectField
                label={t("signup.board")}
                name="board"
                value={values.board}
                onChange={(e) => set("board", e.target.value as Board)}
                options={boardOptions}
                error={errors.board}
              />
              <TextField
                label={t("signup.city")}
                name="city"
                autoComplete="address-level2"
                placeholder={t("signup.city.placeholder")}
                value={values.city}
                onChange={(e) => set("city", e.target.value)}
                error={errors.city}
                required
              />
            </div>
          </>
        ) : null}

        {step === 2 ? (
          <>
            <TextField
              label={t("signup.adminName")}
              name="adminName"
              autoComplete="name"
              placeholder={t("signup.adminName.placeholder")}
              value={values.adminName}
              onChange={(e) => set("adminName", e.target.value)}
              error={errors.adminName}
              required
            />
            <TextField
              label={t("signup.adminEmail")}
              name="adminEmail"
              type="email"
              autoComplete="email"
              placeholder={t("signup.adminEmail.placeholder")}
              value={values.adminEmail}
              onChange={(e) => set("adminEmail", e.target.value)}
              error={errors.adminEmail}
              required
            />
            <TextField
              label={t("signup.password")}
              name="password"
              type="password"
              autoComplete="new-password"
              value={values.password}
              onChange={(e) => set("password", e.target.value)}
              hint={t("signup.password.hint")}
              error={errors.password}
              minLength={MIN_PASSWORD_LENGTH}
              required
            />
          </>
        ) : null}

        {step === 3 ? (
          <PlanPicker value={values.plan} plans={plans} onChange={(plan) => set("plan", plan)} />
        ) : null}

        {step === 3 ? (
          <div className="card flex flex-col gap-3.5">
            <dl className="kv">
              <dt>{t("signup.schoolName")}</dt>
              <dd>{values.schoolName}</dd>
              <dt>{t("signup.schoolCode")}</dt>
              <dd className="mono">{values.schoolCode}</dd>
              <dt>{t("signup.board")}</dt>
              <dd>{t(`board.${values.board}` as MessageKey)}</dd>
              <dt>{t("signup.city")}</dt>
              <dd>{values.city}</dd>
              <dt>{t("signup.adminName")}</dt>
              <dd>{values.adminName}</dd>
              <dt>{t("signup.adminEmail")}</dt>
              <dd>{values.adminEmail}</dd>
            </dl>
            <p className="text-sm text-ink-2">{t("signup.review.note")}</p>
          </div>
        ) : null}

        <div className="flex items-center justify-between gap-2 pt-1">
          {step > 1 ? (
            <button
              type="button"
              className="btn"
              onClick={() => setStep((step - 1) as 1 | 2)}
              disabled={submitting}
            >
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </button>
          ) : (
            <span />
          )}
          <button type="submit" className="btn btn-primary btn-lg" disabled={submitting}>
            {step < 3 ? t("common.continue") : submitting ? t("signup.submitting") : t("signup.submit")}
            {!submitting ? <ArrowRight size={18} aria-hidden="true" /> : null}
          </button>
        </div>
      </form>

      <p className="text-center text-ink-2">
        {t("signup.haveAccount")}{" "}
        <Link href="/login" className="link">
          {t("signup.signIn")}
        </Link>
      </p>
    </AuthLayout>
  );
}

/** The plan the school starts its trial on: name, price per student and limits, with Starter chosen by default. */
function PlanPicker({
  value,
  plans,
  onChange,
}: {
  value: Plan;
  plans: PlanView[] | null;
  onChange: (plan: Plan) => void;
}) {
  const { t } = useI18n();
  const options = plans ?? PLANS.map((plan) => ({ plan }) as Partial<PlanView> & { plan: Plan });
  return (
    <fieldset className="flex flex-col gap-2" data-testid="signup-plans">
      <legend className="field-label mb-1.5">{t("signup.plan")}</legend>
      {options.map((option) => (
        <label key={option.plan} className="check items-start">
          <input
            type="radio"
            name="plan"
            value={option.plan}
            checked={value === option.plan}
            onChange={() => onChange(option.plan)}
            className="mt-0.5"
          />
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
              <b className="font-semibold">
                {planLabel(t, option.plan)}
                {option.popular ? (
                  <>
                    {" "}
                    <Pill tone="info">{t("billing.plans.popular")}</Pill>
                  </>
                ) : null}
              </b>
              {option.pricePerStudentPerYearPaise !== undefined ? (
                <span className="text-[13px] text-ink-2">
                  <b className="font-semibold text-ink">{formatPaise(option.pricePerStudentPerYearPaise)}</b>{" "}
                  {t("billing.plans.perStudentYear")}
                </span>
              ) : null}
            </span>
            {option.features ? (
              <>
                <span className="text-[13px] text-ink-2">
                  {option.maxStudents === null
                    ? t("billing.plans.unlimitedStudents")
                    : t("billing.plans.upToStudents", { count: (option.maxStudents ?? 0).toLocaleString("en-IN") })}
                  {" · "}
                  {option.maxBranches === null || option.maxBranches === undefined
                    ? t("billing.plans.unlimitedBranches")
                    : plural(t, "billing.plans.branches", option.maxBranches)}
                </span>
                <span className="text-[13px] text-ink-3">
                  {option.features.map((f) => translateOr(t, `billing.feature.${f}`, f)).join(" · ")}
                </span>
              </>
            ) : null}
          </span>
        </label>
      ))}
      <p className="field-hint">{t("signup.plan.hint")}</p>
    </fieldset>
  );
}
