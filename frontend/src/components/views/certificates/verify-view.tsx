"use client";

import { BadgeCheck, BadgeX } from "lucide-react";
import { LanguageSelect, ThemeToggle } from "@/components/preferences";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { certificatesApi } from "@/lib/certificates-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";
import { typeLabel } from "./certificates-shared";

/** /verify/[token]: what anyone scanning a certificate's QR code sees. No sign-in, no app shell. */
export function VerifyView({ token }: { token: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const result = useApiData(`public:verify:${token}`, () => certificatesApi.verify(token));
  const v = result.data;
  return (
    <div className="enquiry-page">
      <main className="enquiry-main" id="main">
        {result.error && !v ? (
          <section className="card" data-testid="verify-unavailable">
            {result.error.status === 404 ? (
              <>
                <h1 className="mb-2">{t("certificates.verify.notFoundTitle")}</h1>
                <p className="text-ink-2">{t("certificates.verify.notFound")}</p>
              </>
            ) : result.error.status === 429 ? (
              <p className="alert alert-bad" role="alert">
                {t("certificates.verify.tooMany")}
              </p>
            ) : (
              <ErrorState error={result.error} onRetry={result.reload} />
            )}
          </section>
        ) : !v ? (
          <section className="card">
            <LoadingRows rows={5} />
          </section>
        ) : (
          <section className="card flex flex-col gap-4" data-testid="verify-result" data-valid={v.valid}>
            <header className="enquiry-head">
              <span className={`badge-ic ${v.valid ? "text-good" : "text-bad"}`} aria-hidden="true">
                {v.valid ? <BadgeCheck size={20} /> : <BadgeX size={20} />}
              </span>
              <div className="min-w-0">
                <p className="eyebrow">{v.schoolName}</p>
                <h1 className="mt-1" data-testid="verify-status">
                  {t(v.valid ? "certificates.verify.valid" : "certificates.verify.cancelled")}
                </h1>
              </div>
            </header>
            <dl className="verify-facts">
              <div>
                <dt>{t("certificates.col.type")}</dt>
                <dd>{typeLabel(t, v.type)}</dd>
              </div>
              <div>
                <dt>{t("certificates.col.title")}</dt>
                <dd>{v.title}</dd>
              </div>
              <div>
                <dt>{t("certificates.col.serial")}</dt>
                <dd className="mono" data-testid="verify-serial">
                  {v.serialNo}
                </dd>
              </div>
              <div>
                <dt>{t("certificates.verify.issuedTo")}</dt>
                <dd>{v.firstName ?? "—"}</dd>
              </div>
              <div>
                <dt>{t("certificates.col.issuedOn")}</dt>
                <dd>{formatPlainDate(v.issuedOn, locale)}</dd>
              </div>
            </dl>
            <p className="text-[13px] text-ink-3">{t(v.valid ? "certificates.verify.validNote" : "certificates.verify.cancelledNote")}</p>
          </section>
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
