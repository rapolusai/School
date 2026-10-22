"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { frontOfficeApi } from "@/lib/frontoffice-api";
import { localeFor, useI18n } from "@/lib/i18n";
import type { VisitorView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { idTypeLabel, PhotoThumb, purposeLabel, VisitorStatusPill, when } from "./frontoffice-shared";

/** /app/frontoffice/visitors/[id]: the visitor's details and a printable slip for the badge. */
export function VisitorSlipView({ visitorId }: { visitorId: string }) {
  const { t } = useI18n();
  const visitor = useApiData(visitorId ? `frontoffice:visitor:${visitorId}` : null, () => frontOfficeApi.visitor(visitorId));
  return (
    <>
      <PageHead
        eyebrow={t("nav.frontoffice")}
        title={t("frontoffice.slip.title")}
        actions={
          <>
            <Link href="/app/frontoffice" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("frontoffice.slip.back")}
            </Link>
            <button type="button" className="btn btn-primary" onClick={() => window.print()} disabled={!visitor.data}>
              <Printer size={18} aria-hidden="true" />
              {t("frontoffice.slip.print")}
            </button>
          </>
        }
      />
      {visitor.error?.status === 404 ? (
        <p className="empty">{t("frontoffice.slip.notFound")}</p>
      ) : visitor.error && !visitor.data ? (
        <ErrorState error={visitor.error} onRetry={visitor.reload} />
      ) : !visitor.data ? (
        <LoadingRows rows={5} />
      ) : (
        <section className="card">
          <VisitorSlip visitor={visitor.data} />
        </section>
      )}
    </>
  );
}

/** The slip itself: school, badge number, who, whom to meet, when in; the full phone for the office's copy. */
export function VisitorSlip({ visitor }: { visitor: VisitorView }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  return (
    <article className="receipt print-area" aria-label={t("frontoffice.slip.title")} data-testid="visitor-slip">
      <header className="receipt-head">
        <div className="min-w-0">
          <p className="receipt-school">{me?.tenant?.name ?? ""}</p>
          <p className="text-[13px] text-ink-2">{t("frontoffice.slip.subtitle")}</p>
        </div>
        <div className="receipt-title">
          <p className="font-display text-2xl font-bold">{visitor.badgeNo ?? "—"}</p>
          <VisitorStatusPill status={visitor.status} />
        </div>
      </header>
      <div className="flex items-start gap-4">
        <PhotoThumb fileId={visitor.photoFileId} alt={visitor.name} size={96} />
        <dl className="kv flex-1 text-[14px]">
          <dt>{t("frontoffice.field.name")}</dt>
          <dd className="font-semibold">{visitor.name}</dd>
          <dt>{t("frontoffice.field.phone")}</dt>
          <dd className="num">{visitor.phone ?? visitor.phoneMasked ?? "—"}</dd>
          <dt>{t("frontoffice.field.purpose")}</dt>
          <dd>
            {purposeLabel(t, visitor.purpose)}
            {visitor.purposeNote ? ` · ${visitor.purposeNote}` : ""}
          </dd>
          <dt>{t("frontoffice.field.toMeet")}</dt>
          <dd>{visitor.toMeetName ?? "—"}</dd>
          <dt>{t("frontoffice.field.people")}</dt>
          <dd className="num">{visitor.peopleCount}</dd>
          <dt>{t("frontoffice.field.idType")}</dt>
          <dd>{idTypeLabel(t, visitor.idType)}</dd>
          <dt>{t("frontoffice.slip.in")}</dt>
          <dd>{when(visitor.checkedInAt, locale)}</dd>
          <dt>{t("frontoffice.slip.out")}</dt>
          <dd>{when(visitor.checkedOutAt, locale)}</dd>
          <dt>{t("frontoffice.slip.by")}</dt>
          <dd>{visitor.checkedInByName ?? visitor.addedByName ?? "—"}</dd>
        </dl>
      </div>
      <footer className="receipt-foot">
        <p className="text-[12.5px] text-ink-3">{t("frontoffice.slip.note")}</p>
        <div className="mt-6 flex justify-between gap-6 text-[12.5px]">
          <span className="border-t border-line pt-1">{t("frontoffice.slip.signVisitor")}</span>
          <span className="border-t border-line pt-1">{t("frontoffice.slip.signOffice")}</span>
        </div>
      </footer>
    </article>
  );
}
