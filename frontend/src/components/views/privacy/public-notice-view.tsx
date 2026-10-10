"use client";

import { ShieldCheck } from "lucide-react";
import { useState } from "react";
import { LanguageSelect, ThemeToggle } from "@/components/preferences";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { formatDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { privacyApi } from "@/lib/privacy-api";
import type { PublicNotice } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { NoticeBody } from "./privacy-ui";

/** /privacy/[schoolCode]: a school's privacy notice for anyone, with its older versions. No sign-in, no app shell. */
export function PublicNoticeView({ schoolCode }: { schoolCode: string }) {
  const { t } = useI18n();
  const [version, setVersion] = useState<number | undefined>(undefined);
  const notice = useApiData(`public:privacy-notice:${schoolCode}:${version ?? "current"}`, () =>
    privacyApi.publicNotice(schoolCode, version),
  );

  return (
    <div className="enquiry-page">
      <main className="enquiry-main" id="main">
        {notice.error && !notice.data ? (
          <section className="card" data-testid="notice-unavailable">
            {notice.error.status === 404 ? (
              <>
                <h1 className="mb-2">{t("privacy.public.notFoundTitle")}</h1>
                <p className="text-ink-2">{t("privacy.public.notFound")}</p>
              </>
            ) : notice.error.status === 429 ? (
              <p className="alert alert-bad" role="alert">
                {t("privacy.public.tooMany")}
              </p>
            ) : (
              <ErrorState error={notice.error} onRetry={notice.reload} />
            )}
          </section>
        ) : !notice.data ? (
          <section className="card">
            <LoadingRows rows={8} />
          </section>
        ) : (
          <Notice notice={notice.data} loading={notice.loading} onVersion={setVersion} />
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

function Notice({
  notice,
  loading,
  onVersion,
}: {
  notice: PublicNotice;
  loading: boolean;
  onVersion: (version: number | undefined) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const older = notice.version !== notice.currentVersion;

  return (
    <>
      <section className="card flex flex-col gap-4" aria-busy={loading || undefined}>
        <header className="enquiry-head">
          <span className="badge-ic" aria-hidden="true">
            <ShieldCheck size={20} />
          </span>
          <div className="min-w-0">
            <p className="eyebrow">{t("privacy.public.eyebrow")}</p>
            <h1 className="mt-0.5" data-testid="notice-school">
              {notice.schoolName}
            </h1>
            <p className="text-sm text-ink-2" data-testid="notice-version">
              {t("privacy.public.version", { version: notice.version, date: formatDate(notice.publishedAt, locale) })}
            </p>
          </div>
        </header>
        {older ? (
          <div className="alert alert-info" role="status">
            <span className="flex flex-wrap items-center gap-2">
              {t("privacy.public.older", { version: notice.version, current: notice.currentVersion })}
              <button type="button" className="btn btn-sm" onClick={() => onVersion(undefined)}>
                {t("privacy.public.readCurrent")}
              </button>
            </span>
          </div>
        ) : null}
        {notice.changeSummary ? (
          <p className="text-[13.5px] text-ink-2">{t("privacy.notice.changed", { summary: notice.changeSummary })}</p>
        ) : null}
        <NoticeBody notice={notice} scroll={false} />
      </section>

      {notice.versions.length > 1 ? (
        <section className="card" aria-labelledby="public-versions">
          <h2 id="public-versions">{t("privacy.notice.versions")}</h2>
          <ul className="list" data-testid="public-versions">
            {notice.versions.map((v) => (
              <li key={v.version} className="li">
                <span className="min-w-0 flex-1">
                  <b className="block font-semibold">
                    {t("privacy.notice.version", { version: v.version })}
                    {v.version === notice.currentVersion ? ` · ${t("privacy.notice.currentShort")}` : ""}
                  </b>
                  <span className="block text-[12.5px] text-ink-3">{formatDate(v.publishedAt, locale)}</span>
                  {v.changeSummary ? <span className="block text-[13px] text-ink-2">{v.changeSummary}</span> : null}
                </span>
                {v.version === notice.version ? (
                  <span className="text-[13px] text-ink-3">{t("privacy.public.showing")}</span>
                ) : (
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => onVersion(v.version === notice.currentVersion ? undefined : v.version)}
                  >
                    {t("privacy.notice.read")}
                  </button>
                )}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}
