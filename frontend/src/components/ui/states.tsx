"use client";

import { CircleAlert, RefreshCw } from "lucide-react";
import type { ApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { useI18n } from "@/lib/i18n";

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
