"use client";

import { CircleAlert, RefreshCw } from "lucide-react";
import type { ApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { useI18n, type Translate } from "@/lib/i18n";

/** Shimmering placeholder rows while data loads. */
export function LoadingRows({ rows = 3 }: { rows?: number }) {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-3 py-2" aria-busy="true">
      <span className="sr-only">{t("common.loading")}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton h-9" aria-hidden="true" />
      ))}
    </div>
  );
}

/**
 * The page title of a record that has not loaded: "Loading…" while it loads, then "Not found" (404) or
 * "Couldn't load" once the load failed, so a failed page never keeps saying it is loading.
 */
export function pendingTitle(t: Translate, error: ApiError | undefined): string {
  if (!error) return t("common.loading");
  return error.status === 404 ? t("common.notFound") : t("common.error.loadTitle");
}

export function ErrorState({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  const { t } = useI18n();
  return (
    <div className="flex flex-col items-start gap-3 py-2">
      <div className="alert alert-bad w-full" role="alert">
        <CircleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        <span>
          {t("common.error.load")} {errorMessage(error, t)}
        </span>
      </div>
      {onRetry ? (
        <button type="button" className="btn btn-sm" onClick={onRetry}>
          <RefreshCw size={16} aria-hidden="true" />
          {t("common.retry")}
        </button>
      ) : null}
    </div>
  );
}

/** A record that failed to load: the plain not-found note for a 404, else the error with a retry button. */
export function LoadFailure({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  const { t } = useI18n();
  if (error.status === 404) return <p className="empty">{t("common.error.notFound")}</p>;
  return <ErrorState error={error} onRetry={onRetry} />;
}

export function FormAlert({ message }: { message: string | null | undefined }) {
  if (!message) return null;
  return (
    <div className="alert alert-bad" role="alert">
      <CircleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
      <span>{message}</span>
    </div>
  );
}

export function PageHead({
  eyebrow,
  title,
  actions,
}: {
  eyebrow?: React.ReactNode;
  title: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <div className="page-head">
      <div className="min-w-0">
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1 className="mt-1">{title}</h1>
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  );
}
