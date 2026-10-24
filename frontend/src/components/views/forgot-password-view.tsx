"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { AuthLayout } from "@/components/auth-layout";
import { TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { api, toApiError, type ApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { useI18n, type Translate } from "@/lib/i18n";

type Step = "request" | "confirm" | "done";

/** Shortest password the API accepts. */
export const MIN_PASSWORD = 10;

/** The message for a failed "set password"; a wrong or expired code is a 400 on the code field. Exported for tests. */
export function confirmErrorMessage(error: ApiError, t: Translate): string {
  if (error.status === 400 && error.errors?.code) return t("reset.error.code");
  if (error.status === 429) return t("login.error.tooMany");
  if (error.status === 400 && error.errors) return t("validation.fixErrors");
  return errorMessage(error, t);
}

/**
 * /forgot-password: ask for a code by school code and email, then set a new password with the code from the email.
 * The first step always says the same thing, so it never shows whether an account exists. Staff added without a
 * password use the same page to set their first one.
 */
export function ForgotPasswordView({ school = "" }: { school?: string }) {
  const { t } = useI18n();
  const [step, setStep] = useState<Step>("request");
  const [schoolCode, setSchoolCode] = useState(school);
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const cleanSchool = schoolCode.trim().toLowerCase();
  const cleanEmail = email.trim();

  const sendCode = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api.requestPasswordReset({ schoolCode: cleanSchool, email: cleanEmail });
      setStep("confirm");
    } catch (caught) {
      const apiError = toApiError(caught);
      setError(apiError.status === 429 ? t("login.error.tooMany") : errorMessage(apiError, t));
    } finally {
      setBusy(false);
    }
  };

  const setNewPassword = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    const problems: Record<string, string> = {};
    if (!/^\s*\d{6}\s*$/.test(code)) problems.code = t("reset.v.code");
    if (password.length < MIN_PASSWORD) problems.newPassword = t("validation.password");
    else if (password !== repeat) problems.repeat = t("reset.v.repeat");
    setFieldErrors(problems);
    if (Object.keys(problems).length > 0) return;
    setBusy(true);
    try {
      await api.confirmPasswordReset({
        schoolCode: cleanSchool,
        email: cleanEmail,
        code: code.trim(),
        newPassword: password,
      });
      setStep("done");
    } catch (caught) {
      setError(confirmErrorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title={t("login.art.title")} body={t("login.art.body")}>
      <div>
        <p className="eyebrow">{t("reset.eyebrow")}</p>
        <h1 className="mt-1.5">{step === "done" ? t("reset.done.title") : t("reset.title")}</h1>
        <p className="mt-1.5 text-ink-2">
          {step === "request"
            ? t("reset.subtitle")
            : step === "confirm"
              ? t("reset.sent", { email: cleanEmail })
              : t("reset.done.body")}
        </p>
      </div>

      {step === "request" ? (
        <form method="post" className="flex flex-col gap-3.5" onSubmit={sendCode}>
          <FormAlert message={error} />
          <TextField
            label={t("login.schoolCode")}
            name="schoolCode"
            autoComplete="organization"
            autoCapitalize="none"
            spellCheck={false}
            placeholder={t("login.schoolCode.placeholder")}
            required
            value={schoolCode}
            onChange={(e) => setSchoolCode(e.target.value)}
          />
          <TextField
            label={t("login.email")}
            name="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <button type="submit" className="btn btn-primary btn-lg" disabled={busy}>
            {busy ? t("reset.sending") : t("reset.send")}
            {!busy ? <ArrowRight size={18} aria-hidden="true" /> : null}
          </button>
        </form>
      ) : null}

      {step === "confirm" ? (
        <form method="post" className="flex flex-col gap-3.5" onSubmit={setNewPassword} noValidate>
          <FormAlert message={error} />
          <TextField
            label={t("reset.code")}
            name="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={8}
            required
            value={code}
            onChange={(e) => setCode(e.target.value)}
            error={fieldErrors.code}
          />
          <TextField
            label={t("reset.newPassword")}
            name="newPassword"
            type="password"
            autoComplete="new-password"
            hint={t("validation.password")}
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={fieldErrors.newPassword}
          />
          <TextField
            label={t("reset.repeat")}
            name="repeat"
            type="password"
            autoComplete="new-password"
            required
            value={repeat}
            onChange={(e) => setRepeat(e.target.value)}
            error={fieldErrors.repeat}
          />
          <button type="submit" className="btn btn-primary btn-lg" disabled={busy}>
            {busy ? t("common.saving") : t("reset.save")}
          </button>
          <button
            type="button"
            className="linkbtn text-sm self-center"
            onClick={() => {
              setStep("request");
              setCode("");
              setError(null);
              setFieldErrors({});
            }}
          >
            {t("reset.again")}
          </button>
        </form>
      ) : null}

      {step === "done" ? (
        <Link
          href={`/login?school=${encodeURIComponent(cleanSchool)}`}
          className="btn btn-primary btn-lg"
          data-testid="reset-sign-in"
        >
          {t("login.submit")}
          <ArrowRight size={18} aria-hidden="true" />
        </Link>
      ) : null}

      {step !== "done" ? (
        <p className="text-center">
          <Link href="/login" className="link text-sm">
            {t("reset.backToSignIn")}
          </Link>
        </p>
      ) : null}
    </AuthLayout>
  );
}
