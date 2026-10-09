"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthLayout } from "@/components/auth-layout";
import { TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { useI18n, type Translate } from "@/lib/i18n";
import { landingPath, safeNextPath } from "@/lib/permissions";

type Mode = "school" | "platform";

export function loginErrorMessage(error: ApiError, mode: Mode, t: Translate): string {
  if (error.status === 401) {
    return mode === "platform" ? t("login.error.invalidPlatform") : t("login.error.invalid");
  }
  if (error.status === 429) return t("login.error.tooMany");
  if (error.status === 400 && error.errors) return t("validation.fixErrors");
  return errorMessage(error, t);
}

export function LoginView({
  next: requestedNext = null,
  school = "",
  signupDone = false,
}: {
  /** ?next= from the URL; only paths inside /app are honoured. */
  next?: string | null;
  /** ?school= prefill, used after sign-up. */
  school?: string;
  signupDone?: boolean;
}) {
  const { t } = useI18n();
  const auth = useAuth();
  const router = useRouter();
  const next = safeNextPath(requestedNext);

  const [mode, setMode] = useState<Mode>("school");
  const [schoolCode, setSchoolCode] = useState(school);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  // Already signed in (e.g. session restored from the refresh cookie): go straight in.
  useEffect(() => {
    if (auth.status === "authenticated" && !submitting) {
      router.replace(next ?? landingPath(auth.me));
    }
  }, [auth.status, auth.me, next, router, submitting]);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      const me =
        mode === "platform"
          ? await auth.platformLogin(email, password)
          : await auth.login(schoolCode, email, password);
      router.replace(next ?? landingPath(me));
    } catch (caught) {
      const apiError = toApiError(caught);
      setError(loginErrorMessage(apiError, mode, t));
      setFieldErrors(apiError.status === 400 ? (apiError.errors ?? {}) : {});
      setSubmitting(false);
    }
  };

  const switchMode = (nextMode: Mode) => {
    setMode(nextMode);
    setError(null);
    setFieldErrors({});
  };

  const platform = mode === "platform";

  return (
    <AuthLayout title={t("login.art.title")} body={t("login.art.body")}>
      <div>
        <p className="eyebrow">{platform ? t("login.platform.eyebrow") : t("login.eyebrow")}</p>
        <h1 className="mt-1.5">{platform ? t("login.platform.title") : t("login.title")}</h1>
        <p className="mt-1.5 text-ink-2">
          {platform ? t("login.platform.subtitle") : t("login.subtitle")}
        </p>
      </div>

      {signupDone && !platform ? (
        <div className="alert alert-info" role="status">
          {t("login.signupDone")}
        </div>
      ) : null}

      {/* method="post": if scripts fail to load, a native submit must never put the password in the URL. */}
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit}>
        <FormAlert message={error} />
        {!platform ? (
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
            error={fieldErrors.schoolCode}
          />
        ) : null}
        <TextField
          label={t("login.email")}
          name="email"
          type="email"
          autoComplete="username"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={fieldErrors.email}
        />
        <TextField
          label={t("login.password")}
          name="password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fieldErrors.password}
        />
        <button type="submit" className="btn btn-primary btn-lg" disabled={submitting}>
          {submitting ? t("login.submitting") : t("login.submit")}
          {!submitting ? <ArrowRight size={18} aria-hidden="true" /> : null}
        </button>
      </form>

      <div className="flex flex-col items-center gap-3 text-center">
        <button
          type="button"
          className="linkbtn text-sm"
          onClick={() => switchMode(platform ? "school" : "platform")}
        >
          {platform ? t("login.schoolLink") : t("login.superAdminLink")}
        </button>
        {!platform ? (
          <p className="text-ink-2">
            {t("login.newSchool")}{" "}
            <Link href="/signup" className="link">
              {t("login.startTrial")}
            </Link>
          </p>
        ) : null}
      </div>
    </AuthLayout>
  );
}
