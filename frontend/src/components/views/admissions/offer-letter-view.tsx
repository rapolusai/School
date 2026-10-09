"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { admissionsApi } from "@/lib/admissions-api";
import { api } from "@/lib/api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";

/** A printable offer letter on the school's letterhead (name, address and contacts from the school profile). */
export function OfferLetterView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const application = useApiData(`admission:${id}`, () => admissionsApi.get(id));
  const profile = useApiData("school:profile", api.getSchoolProfile);
  const a = application.data;
  const school = profile.data;
  const error = application.error ?? profile.error;

  const toolbar = (
    <div className="no-print flex flex-wrap items-center justify-between gap-2">
      <Link href={`/app/admissions/${id}`} className="link inline-flex items-center gap-1 text-[13.5px]">
        <ArrowLeft size={16} aria-hidden="true" />
        {t("admissions.offer.backToApplication")}
      </Link>
      {a?.offer && school ? (
        <button type="button" className="btn btn-primary" onClick={() => window.print()}>
          <Printer size={18} aria-hidden="true" />
          {t("admissions.offer.print")}
        </button>
      ) : null}
    </div>
  );

  if (error && (!a || !school)) {
    return (
      <>
        {toolbar}
        <section className="card">
          {application.error?.status === 404 ? (
            <p className="empty">{t("admissions.notFound")}</p>
          ) : (
            <ErrorState
              error={error}
              onRetry={() => {
                application.reload();
                profile.reload();
              }}
            />
          )}
        </section>
      </>
    );
  }
  if (!a || !school) {
    return (
      <>
        {toolbar}
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }
  if (!a.offer) {
    return (
      <>
        {toolbar}
        <section className="card">
          <p className="empty">{t("admissions.offer.none")}</p>
        </section>
      </>
    );
  }

  const parent = a.guardians.find((g) => g.primary) ?? a.guardians[0];
  const contacts = [school.phone, school.contactEmail].filter(Boolean).join(" · ");

  return (
    <>
      {toolbar}
      <article className="offer-letter" aria-label={t("admissions.offer.letter")} data-testid="offer-letter">
        <header className="offer-letter-head">
          <p className="offer-letter-school">{school.name}</p>
          {school.address ? <p className="whitespace-pre-line">{school.address}</p> : null}
          {school.city && !school.address ? <p>{school.city}</p> : null}
          {contacts ? <p>{contacts}</p> : null}
          <p className="text-[12.5px]">
            {translateOr(t, `board.${school.board}`, school.board)}
            {school.udiseCode ? ` · ${t("admissions.offer.udise", { code: school.udiseCode })}` : ""}
          </p>
        </header>

        <p className="offer-letter-date">{t("admissions.offer.date", { date: formatPlainDate(a.offer.offeredOn, locale) })}</p>

        <p>
          {parent ? t("admissions.offer.to", { name: parent.name }) : null}
          <br />
          {t("admissions.offer.parentOf", { name: a.childName })}
        </p>

        <p className="font-semibold">
          {t("admissions.offer.subject", { className: a.className, year: a.academicYearName })}
        </p>

        <p>{t("admissions.offer.greeting")}</p>
        <p>
          {t("admissions.offer.body", { name: a.childName, className: a.className, year: a.academicYearName })}
        </p>
        <p>
          {a.offer.validUntil
            ? t("admissions.offer.validUntil", { date: formatPlainDate(a.offer.validUntil, locale) })
            : t("admissions.offer.accept")}
        </p>
        <p>{t("admissions.offer.documents")}</p>
        <p>{t("admissions.offer.welcome")}</p>

        <div className="offer-letter-sign">
          <p>{t("admissions.offer.regards")}</p>
          <p className="offer-letter-signature" aria-hidden="true" />
          <p className="font-semibold">{t("admissions.offer.principal")}</p>
          <p>{school.name}</p>
        </div>
      </article>
    </>
  );
}
