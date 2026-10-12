"use client";

import { Camera } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useId, useState } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { getAccessToken } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import { translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasAnyPermission, hasPermission, PERMISSIONS } from "@/lib/permissions";
import type {
  CallDirection,
  CourierDirection,
  EnquirySource,
  EnquiryStatus,
  EnquiryTopic,
  IdType,
  PassStatus,
  VisitorPurpose,
  VisitorStatus,
} from "@/lib/types";
import { isValidIndianMobile } from "@/lib/validation";

/* Parts shared by the front office screens (docs/api/phase-2-frontoffice.md). */

type OfficeLink = { href: string; labelKey: MessageKey; anyOf: readonly string[]; exact?: boolean };

const READ = [PERMISSIONS.frontofficeRead, PERMISSIONS.frontofficeManage] as const;
const READ_OR_GATE = [...READ, PERMISSIONS.gatePass] as const;

const OFFICE_LINKS: OfficeLink[] = [
  { href: "/app/frontoffice", labelKey: "frontoffice.nav.visitors", anyOf: READ_OR_GATE, exact: true },
  { href: "/app/frontoffice/passes", labelKey: "frontoffice.nav.passes", anyOf: READ },
  { href: "/app/frontoffice/gate", labelKey: "frontoffice.nav.gate", anyOf: [PERMISSIONS.gatePass, PERMISSIONS.frontofficeManage] },
  { href: "/app/frontoffice/enquiries", labelKey: "frontoffice.nav.enquiries", anyOf: READ },
  { href: "/app/frontoffice/courier", labelKey: "frontoffice.nav.courier", anyOf: READ },
  { href: "/app/frontoffice/register", labelKey: "frontoffice.nav.register", anyOf: READ },
  { href: "/app/frontoffice/blocklist", labelKey: "frontoffice.nav.blocklist", anyOf: READ_OR_GATE },
];

/** The front office's own navigation under each page's heading; nothing for a guard who only has the gate. */
export function FrontOfficeNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = OFFICE_LINKS.filter((link) => hasAnyPermission(me, link.anyOf));
  if (links.length < 2) return null;
  return (
    <nav className="subnav no-print" aria-label={t("frontoffice.nav.label")}>
      {links.map((link) => {
        const active = link.exact
          ? pathname === link.href || pathname.startsWith("/app/frontoffice/visitors")
          : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

/** True when the signed-in person runs the front office (frontoffice.manage). */
export function useCanManageOffice(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.frontofficeManage);
}

/** True when the signed-in person may work the gate (gate.pass or frontoffice.manage). */
export function useAtGate(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.gatePass) || hasPermission(me, PERMISSIONS.frontofficeManage);
}

/* ------------------------------------------------------------------ labels and pills */

export const purposeLabel = (t: Translate, purpose: VisitorPurpose) =>
  translateOr(t, `frontoffice.purpose.${purpose}`, purpose);
export const idTypeLabel = (t: Translate, type: IdType) => translateOr(t, `frontoffice.idType.${type}`, type);
export const visitorStatusLabel = (t: Translate, status: VisitorStatus) =>
  translateOr(t, `frontoffice.visitorStatus.${status}`, status);
export const passStatusLabel = (t: Translate, status: PassStatus) =>
  translateOr(t, `frontoffice.passStatus.${status}`, status);
export const enquiryStatusLabel = (t: Translate, status: EnquiryStatus) =>
  translateOr(t, `frontoffice.enquiryStatus.${status}`, status);
export const sourceLabel = (t: Translate, source: EnquirySource) =>
  translateOr(t, `frontoffice.source.${source}`, source);
export const topicLabel = (t: Translate, topic: EnquiryTopic) => translateOr(t, `frontoffice.topic.${topic}`, topic);
export const directionLabel = (t: Translate, direction: CallDirection) =>
  translateOr(t, `frontoffice.call.${direction}`, direction);
export const courierDirectionLabel = (t: Translate, direction: CourierDirection) =>
  translateOr(t, `frontoffice.courier.${direction}`, direction);

export const VISITOR_TONE: Record<VisitorStatus, PillTone> = { EXPECTED: "info", IN: "good", OUT: "neutral" };

export const PASS_TONE: Record<PassStatus, PillTone> = {
  REQUESTED: "warn",
  APPROVED: "info",
  RELEASED: "good",
  REJECTED: "bad",
  CANCELLED: "neutral",
  EXPIRED: "neutral",
  LOCKED: "bad",
};

export const ENQUIRY_TONE: Record<EnquiryStatus, PillTone> = {
  OPEN: "info",
  FOLLOW_UP: "warn",
  CLOSED: "neutral",
  CONVERTED: "good",
};

export function VisitorStatusPill({ status }: { status: VisitorStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={VISITOR_TONE[status]} dot>
      {visitorStatusLabel(t, status)}
    </Pill>
  );
}

export function PassStatusPill({ status }: { status: PassStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={PASS_TONE[status]} dot>
      {passStatusLabel(t, status)}
    </Pill>
  );
}

export function EnquiryStatusPill({ status }: { status: EnquiryStatus }) {
  const { t } = useI18n();
  return <Pill tone={ENQUIRY_TONE[status]}>{enquiryStatusLabel(t, status)}</Pill>;
}

/** "11 Oct 2026, 2:30 pm" for an instant, or an em dash. */
export function when(value: string | null | undefined, locale: string): string {
  return value ? formatDateTime(value, locale) : "—";
}

/* ------------------------------------------------------------------ checks shared with the API */

/** A phone typed in a form is fine when empty (unless required) or an Indian mobile number. */
export function phoneProblem(value: string, required = false): MessageKey | null {
  const trimmed = value.trim();
  if (!trimmed) return required ? "frontoffice.v.phone" : null;
  return isValidIndianMobile(trimmed) ? null : "frontoffice.v.phone";
}

/** A datetime-local value ("2026-10-11T14:30") as an ISO instant, or null when it does not parse. */
export function localToInstant(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** Now plus `minutes`, as a datetime-local value in the browser's zone. */
export function localNow(minutes = 0, now: Date = new Date()): string {
  const d = new Date(now.getTime() + minutes * 60_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/* ------------------------------------------------------------------ photos from the phone camera */

/** The longest side a visitor or pickup photo is shrunk to in the browser before upload. */
export const PHOTO_MAX_SIDE = 640;

/**
 * Shrinks an image to at most {@link PHOTO_MAX_SIDE} pixels on its longer side, as a JPEG, using a canvas. Falls
 * back to the file as it is when the browser cannot decode or draw it (no canvas, no createImageBitmap), so the
 * upload still works. Exported for tests.
 */
export async function downscaleImage(file: File, maxSide = PHOTO_MAX_SIDE): Promise<Blob> {
  if (typeof document === "undefined" || typeof createImageBitmap !== "function") return file;
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext("2d");
    if (!context) return file;
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close?.();
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
    return blob ?? file;
  } catch {
    return file;
  }
}

/**
 * A "Take photo" control: on a phone the camera opens straight away (`capture`), elsewhere a file picker, and
 * any image file is accepted so a photo from the gallery works too. The picked image is shrunk and handed to
 * `onPhoto` as a JPEG (or as it is when the browser cannot shrink it).
 */
export function PhotoCapture({
  label,
  busy = false,
  onPhoto,
  testId = "photo-capture",
}: {
  label: string;
  busy?: boolean;
  onPhoto: (blob: Blob, name: string) => Promise<void>;
  testId?: string;
}) {
  const { t } = useI18n();
  const id = useId();
  const [error, setError] = useState<string | null>(null);
  const pick = async (file: File | undefined) => {
    setError(null);
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setError(t("frontoffice.v.photoType"));
      return;
    }
    const blob = await downscaleImage(file);
    const name = blob === file ? file.name : file.name.replace(/\.[^.]+$/, "") + ".jpg";
    await onPhoto(blob, name);
  };
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className={`btn btn-sm${busy ? " opacity-60" : ""}`} data-testid={testId}>
        <Camera size={16} aria-hidden="true" />
        {label}
      </label>
      <input
        id={id}
        type="file"
        accept="image/*"
        capture="environment"
        className="sr-only"
        disabled={busy}
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = "";
          void pick(file);
        }}
      />
      {error ? (
        <p className="field-error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/**
 * A stored photo, shown small. GET /api/files/{id} needs the bearer token, so the bytes are fetched and shown
 * through an object URL; nothing while it loads, a camera glyph when there is no photo.
 */
export function PhotoThumb({ fileId, alt, size = 56 }: { fileId: string | null; alt: string; size?: number }) {
  const { t } = useI18n();
  // The object URL is kept with the id it belongs to, so a changed id shows the skeleton until its bytes arrive.
  const [loaded, setLoaded] = useState<{ id: string; url: string } | null>(null);
  const url = loaded && loaded.id === fileId ? loaded.url : null;
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
        setLoaded({ id: fileId, url: objectUrl });
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [fileId]);
  if (!fileId) {
    return (
      <span className="badge-ic" style={{ width: size, height: size }} aria-label={t("frontoffice.photo.none")}>
        <Camera size={18} aria-hidden="true" />
      </span>
    );
  }
  if (!url) return <span className="skeleton rounded-lg" style={{ width: size, height: size }} aria-hidden="true" />;
  // eslint-disable-next-line @next/next/no-img-element -- an object URL from the API, not a static asset
  return <img src={url} alt={alt} width={size} height={size} className="rounded-lg object-cover" style={{ width: size, height: size }} />;
}
