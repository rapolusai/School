"use client";

import { useMemo, useSyncExternalStore } from "react";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { qrMatrix, qrPath } from "@/lib/qr";
import type { CertificateDetail, SchoolHeader } from "@/lib/types";

/* The printed certificate: an A4 page with the school header, the title, the serial number, the wording, the
   signatory and the QR code of the verification page. Used by the office and by the family app. */

/** The address a QR code points at: "<origin>/verify/<token>". Pure and exported for tests. */
export function verifyUrl(origin: string, token: string): string {
  return `${origin.replace(/\/$/, "")}/verify/${encodeURIComponent(token)}`;
}

/** The current page's origin in the browser, or a placeholder when rendered on the server. */
export function pageOrigin(): string {
  return typeof window === "undefined" ? "" : window.location.origin;
}

const noopSubscribe = () => () => {};

/** The page's origin: "" on the server and during hydration, then window.location.origin. */
export function usePageOrigin(): string {
  return useSyncExternalStore(noopSubscribe, pageOrigin, () => "");
}

/** A QR code of the text as an SVG (one unit per module, a four-module quiet zone). */
export function QrCode({ value, size = 96, label }: { value: string; size?: number; label: string }) {
  const matrix = useMemo(() => {
    try {
      return qrMatrix(value);
    } catch {
      return null;
    }
  }, [value]);
  if (!matrix) return null;
  const n = matrix.size + 8;
  return (
    <svg
      className="qr"
      width={size}
      height={size}
      viewBox={`0 0 ${n} ${n}`}
      shapeRendering="crispEdges"
      role="img"
      aria-label={label}
      data-testid="qr-code"
      data-version={matrix.version}
    >
      <rect x={0} y={0} width={n} height={n} fill="#fff" />
      <path transform="translate(4 4)" d={qrPath(matrix)} fill="#000" />
    </svg>
  );
}

/** The school's name, address and affiliation at the top of a certificate or card. */
export function SchoolHead({ school }: { school: SchoolHeader }) {
  const { t } = useI18n();
  return (
    <header className="cert-head">
      {school.logoText ? (
        <span className="cert-logo" aria-hidden="true">
          {school.logoText}
        </span>
      ) : null}
      <div className="min-w-0">
        <h2 className="cert-school">{school.name}</h2>
        {school.address ? <p className="cert-address">{school.address}</p> : null}
        <p className="cert-address">
          {[
            school.phone ? t("certificates.doc.phone", { phone: school.phone }) : null,
            school.affiliationNo ? t("certificates.doc.affiliation", { no: school.affiliationNo }) : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        </p>
      </div>
    </header>
  );
}

/** The A4 certificate. `origin` is where the verification page lives (window.location.origin). */
export function CertificateDocument({ certificate, origin }: { certificate: CertificateDetail; origin: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const issued = certificate.status === "ISSUED";
  const cancelled = certificate.status === "CANCELLED";
  const paragraphs = certificate.body.split(/\n{2,}/).map((p) => p.trim()).filter(Boolean);
  const url = certificate.verifyToken ? verifyUrl(origin, certificate.verifyToken) : null;
  return (
    <article
      className={`cert-doc${issued || cancelled ? "" : " cert-draft"}`}
      data-testid="certificate-document"
      data-status={certificate.status}
      aria-label={certificate.title}
    >
      {cancelled ? <span className="cert-stamp">{t("certificates.doc.cancelled")}</span> : null}
      {!issued && !cancelled ? <span className="cert-stamp cert-stamp-draft">{t("certificates.doc.draft")}</span> : null}
      <SchoolHead school={certificate.school} />
      <div className="cert-meta">
        <span>
          {t("certificates.doc.serial")}: <b data-testid="certificate-serial">{certificate.serialNo ?? t("certificates.doc.unnumbered")}</b>
        </span>
        <span>
          {t("certificates.doc.date")}:{" "}
          <b>{certificate.issuedOn ? formatPlainDate(certificate.issuedOn, locale) : "—"}</b>
        </span>
      </div>
      <h1 className="cert-title">{certificate.title}</h1>
      <div className="cert-body">
        {paragraphs.map((p, i) => (
          <p key={i}>
            {p.split("\n").map((line, j) => (
              <span key={j}>
                {j > 0 ? <br /> : null}
                {line}
              </span>
            ))}
          </p>
        ))}
      </div>
      <footer className="cert-foot">
        <div className="cert-qr">
          {url ? (
            <>
              <QrCode value={url} size={92} label={t("certificates.doc.qrLabel")} />
              <span className="cert-qr-text">{t("certificates.doc.scan")}</span>
            </>
          ) : null}
        </div>
        <div className="cert-sign">
          <span className="cert-sign-line" aria-hidden="true" />
          <b>{certificate.signatory.name ?? certificate.signedByName ?? "—"}</b>
          <span>{certificate.signatory.designation ?? ""}</span>
          {certificate.signedByName && certificate.signatory.name !== certificate.signedByName ? (
            <span className="cert-signed-by">{t("certificates.doc.signedBy", { name: certificate.signedByName })}</span>
          ) : null}
        </div>
      </footer>
      {url ? (
        <p className="cert-verify-url" data-testid="certificate-verify-url">
          {t("certificates.doc.verifyAt", { url })}
        </p>
      ) : null}
    </article>
  );
}
