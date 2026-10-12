"use client";

import { UserRound } from "lucide-react";
import { useEffect, useState } from "react";
import { getAccessToken } from "@/lib/api";
import { code128Bars } from "@/lib/code128";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import type { IdCard, SchoolHeader } from "@/lib/types";
import { printPhone } from "./certificates-shared";

/* ID cards: CR80 (85.6 × 54 mm) front and back, printed 2 × 5 on an A4 sheet or one at a time. */

/** Cards on one A4 sheet: two columns of five. */
export const CARDS_PER_SHEET = 10;

/** The cards laid out on sheets of ten, front and back of each card side by side. Pure and exported for tests. */
export function sheetsOf<T>(cards: readonly T[]): T[][] {
  const sheets: T[][] = [];
  for (let i = 0; i < cards.length; i += CARDS_PER_SHEET) sheets.push(cards.slice(i, i + CARDS_PER_SHEET));
  return sheets;
}

/** A Code 128 barcode of the admission number or employee code, as SVG bars. */
function Barcode({ value }: { value: string }) {
  const { t } = useI18n();
  let drawn: ReturnType<typeof code128Bars>;
  try {
    drawn = code128Bars(value);
  } catch {
    return null;
  }
  return (
    <svg
      className="idcard-barcode"
      viewBox={`0 0 ${drawn.modules} 22`}
      preserveAspectRatio="none"
      shapeRendering="crispEdges"
      role="img"
      aria-label={t("certificates.card.barcodeOf", { value })}
      data-testid="idcard-barcode"
    >
      <rect x={0} y={0} width={drawn.modules} height={22} fill="#fff" />
      {drawn.bars.map(([x, width]) => (
        <rect key={x} x={x} y={0} width={width} height={22} fill="#000" />
      ))}
    </svg>
  );
}

/**
 * The person's photo, fetched with the bearer token (a plain <img> would carry none), or a placeholder. The object
 * URL is released when the photo changes or the card goes away.
 */
export function CardPhoto({ fileId, name }: { fileId: string | null; name: string }) {
  const { t } = useI18n();
  // The fetched photo is remembered with the file it belongs to, so a changed file shows the placeholder meanwhile.
  const [fetched, setFetched] = useState<{ fileId: string; url: string } | null>(null);
  const url = fetched && fetched.fileId === fileId ? fetched.url : null;
  useEffect(() => {
    if (!fileId) return;
    let cancelled = false;
    let objectUrl: string | null = null;
    const token = getAccessToken();
    fetch(`/api/files/${encodeURIComponent(fileId)}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      credentials: "include",
    })
      .then((res) => (res.ok ? res.blob() : null))
      .then((blob) => {
        if (cancelled || !blob) return;
        objectUrl = URL.createObjectURL(blob);
        setFetched({ fileId, url: objectUrl });
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [fileId]);
  if (url) {
    // eslint-disable-next-line @next/next/no-img-element -- an object URL of a stored photo, never optimised
    return <img className="idcard-photo" src={url} alt={t("certificates.card.photoOf", { name })} data-testid="idcard-photo" />;
  }
  return (
    <span className="idcard-photo idcard-photo-empty" role="img" aria-label={t("certificates.card.noPhoto")} data-testid="idcard-photo-empty">
      <UserRound size={34} aria-hidden="true" />
    </span>
  );
}

/** The front of a card: school, photo, name, class or designation, number, validity. */
export function IdCardFront({ card, school }: { card: IdCard; school: SchoolHeader }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const student = card.subject.type === "STUDENT";
  const name = card.subject.name ?? "—";
  return (
    <div className={`idcard idcard-front${card.status === "CANCELLED" ? " idcard-cancelled" : ""}`} data-testid="idcard-front" data-card-no={card.cardNo}>
      <div className="idcard-band">
        {school.logoText ? <span className="idcard-logo">{school.logoText}</span> : null}
        <span className="idcard-school">{school.name}</span>
      </div>
      <div className="idcard-main">
        <CardPhoto fileId={card.photoFileId} name={name} />
        <div className="idcard-text">
          <b className="idcard-name">{name}</b>
          <span className="idcard-detail">{card.subject.detail ?? (student ? t("certificates.card.noClass") : "")}</span>
          <span className="idcard-row">
            <span className="idcard-label">{t(student ? "certificates.card.admissionNo" : "certificates.card.staffNo")}</span>
            <span className="mono">{card.subject.code ?? "—"}</span>
          </span>
          <span className="idcard-row">
            <span className="idcard-label">{t("certificates.card.validUntil")}</span>
            <span>{formatPlainDate(card.validUntil, locale)}</span>
          </span>
        </div>
      </div>
      {card.subject.code ? <Barcode value={card.subject.code} /> : null}
      <span className="idcard-no">{card.cardNo}</span>
    </div>
  );
}

/** The back of a card: blood group, the number to call, the school's address, the validity. */
export function IdCardBack({ card, school }: { card: IdCard; school: SchoolHeader }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <div className="idcard idcard-back" data-testid="idcard-back">
      <div className="idcard-back-rows">
        <span className="idcard-row">
          <span className="idcard-label">{t("certificates.card.bloodGroup")}</span>
          <b>{card.bloodGroup ?? "—"}</b>
        </span>
        <span className="idcard-row">
          <span className="idcard-label">{t("certificates.card.emergency")}</span>
          <b className="mono" data-testid="idcard-phone">
            {printPhone(card.emergencyPhone)}
          </b>
        </span>
        <span className="idcard-row">
          <span className="idcard-label">{t("certificates.card.valid")}</span>
          <span>
            {formatPlainDate(card.validFrom, locale)} – {formatPlainDate(card.validUntil, locale)}
          </span>
        </span>
      </div>
      <p className="idcard-found">{t("certificates.card.ifFound")}</p>
      <address className="idcard-address">
        <b>{school.name}</b>
        {school.address ? <span>{school.address}</span> : null}
        {school.phone ? <span>{t("certificates.doc.phone", { phone: school.phone })}</span> : null}
      </address>
    </div>
  );
}

/** One or more A4 sheets of cards (front and back side by side), for the screen preview and for printing. */
export function IdCardSheet({ cards, school }: { cards: readonly IdCard[]; school: SchoolHeader }) {
  const { t } = useI18n();
  const sheets = sheetsOf(cards);
  return (
    <div className="idcard-sheet" data-testid="idcard-sheet">
      {sheets.map((page, n) => (
        <section key={n} className="idcard-page" aria-label={t("certificates.card.sheetNo", { n: n + 1, total: sheets.length })}>
          {page.map((card) => (
            <div key={card.id} className="idcard-pair" data-testid="idcard-pair">
              <IdCardFront card={card} school={school} />
              <IdCardBack card={card} school={school} />
            </div>
          ))}
        </section>
      ))}
    </div>
  );
}
