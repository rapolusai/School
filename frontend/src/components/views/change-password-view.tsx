"use client";

import { useState } from "react";
import { TextField } from "@/components/ui/field";
import { FormAlert, PageHead } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { changePassword, useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { useI18n } from "@/lib/i18n";
import { MIN_PASSWORD } from "./forgot-password-view";

/**
 * /app/account/password: a signed-in school user changes their own password. The API signs every other device out
 * and gives this one a fresh session, so the person stays here.
 */
export function ChangePasswordView() {
  const { t } = useI18n();
  const auth = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  if (auth.status === "authenticated" && !auth.me.tenant) {
    return (
      <>
        <PageHead title={t("account.password.title")} />
        <p className="card empty">{t("account.password.schoolOnly")}</p>
      </>
    );
  }

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setDone(false);
    const problems: Record<string, string> = {};
    if (!current) problems.currentPassword = t("validation.required");
    if (next.length < MIN_PASSWORD) problems.newPassword = t("validation.password");
    else if (next !== repeat) problems.repeat = t("reset.v.repeat");
    setFieldErrors(problems);
    if (Object.keys(problems).length > 0) return;
    setBusy(true);
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setRepeat("");
      setDone(true);
    } catch (caught) {
      const apiError = toApiError(caught);
      if (apiError.status === 400 && apiError.errors?.currentPassword) {
        setFieldErrors({ currentPassword: t("account.password.wrongCurrent") });
      } else if (apiError.status === 400 && apiError.errors?.newPassword) {
        setFieldErrors({ newPassword: t("account.password.sameAsCurrent") });
      } else {
        setError(apiError.status === 429 ? t("login.error.tooMany") : errorMessage(apiError, t));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHead title={t("account.password.title")} />
      <section className="card max-w-lg">
        <p className="text-ink-2 mb-3.5">{t("account.password.subtitle")}</p>
        <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
          <FormAlert message={error} />
          {done ? (
            <div className="alert alert-good" role="status" data-testid="password-changed">
              {t("account.password.done")}
            </div>
          ) : null}
          <TextField
            label={t("account.password.current")}
            name="currentPassword"
            type="password"
            autoComplete="current-password"
            required
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            error={fieldErrors.currentPassword}
          />
          <TextField
            label={t("reset.newPassword")}
            name="newPassword"
            type="password"
            autoComplete="new-password"
            hint={t("validation.password")}
            required
            value={next}
            onChange={(e) => setNext(e.target.value)}
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
          <button type="submit" className="btn btn-primary self-start" disabled={busy}>
            {busy ? t("common.saving") : t("account.password.save")}
          </button>
        </form>
      </section>
    </>
  );
}
