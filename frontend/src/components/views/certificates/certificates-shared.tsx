"use client";

import { Search } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ErrorState } from "@/components/ui/states";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { initials } from "@/lib/format";
import { translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { staffApi } from "@/lib/staff-api";
import {
  STAFF_CERTIFICATE_TYPES,
  type CardStatus,
  type CertificateStatus,
  type CertificateSubject,
  type CertificateSubjectType,
  type CertificateType,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* Parts shared by the certificate screens (docs/api/phase-2-certificates.md). */

type CertificateLink = { href: string; labelKey: MessageKey; permission: string };

const LINKS: CertificateLink[] = [
  { href: "/app/certificates", labelKey: "certificates.nav.register", permission: PERMISSIONS.certificatesRead },
  { href: "/app/certificates/id-cards", labelKey: "certificates.nav.idCards", permission: PERMISSIONS.certificatesRead },
  { href: "/app/certificates/templates", labelKey: "certificates.nav.templates", permission: PERMISSIONS.certificatesRead },
];

/** The module's own navigation under each page's heading. */
export function CertificatesNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = LINKS.filter((link) => hasPermission(me, link.permission));
  return (
    <nav className="subnav no-print" aria-label={t("certificates.nav.label")}>
      {links.map((link) => {
        const active =
          pathname === link.href ||
          (link.href !== "/app/certificates" && pathname.startsWith(`${link.href}/`)) ||
          (link.href === "/app/certificates" && /^\/app\/certificates\/[^/]+$/.test(pathname) && !pathname.includes("id-cards") && !pathname.includes("templates"));
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

export function useCanIssue(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.certificatesIssue);
}

export function useCanSign(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.certificatesSign);
}

/* ------------------------------------------------------------------ labels and pills */

export const STATUS_TONE: Record<CertificateStatus, PillTone> = {
  REQUESTED: "warn",
  PREPARED: "info",
  ISSUED: "good",
  CANCELLED: "neutral",
};

export const CARD_TONE: Record<CardStatus, PillTone> = { ACTIVE: "good", CANCELLED: "neutral" };

export const typeLabel = (t: Translate, type: CertificateType) => translateOr(t, `certificates.type.${type}`, type);
export const statusLabel = (t: Translate, status: CertificateStatus) =>
  translateOr(t, `certificates.status.${status}`, status);
export const cardStatusLabel = (t: Translate, status: CardStatus) =>
  translateOr(t, `certificates.card.status.${status}`, status);
export const subjectTypeLabel = (t: Translate, type: CertificateSubjectType) =>
  translateOr(t, `certificates.subjectType.${type}`, type);
/** The label of a placeholder ("Parent's or guardian's name"), or the key itself for one the school added. */
export const placeholderLabel = (t: Translate, key: string) => translateOr(t, `certificates.field.${key}`, key);

export const isStaffType = (type: CertificateType) => STAFF_CERTIFICATE_TYPES.includes(type);

export function CertificateStatusPill({ status }: { status: CertificateStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={STATUS_TONE[status]} dot>
      {statusLabel(t, status)}
    </Pill>
  );
}

export function CardStatusPill({ status }: { status: CardStatus }) {
  const { t } = useI18n();
  return <Pill tone={CARD_TONE[status]}>{cardStatusLabel(t, status)}</Pill>;
}

/** The person's name, or a placeholder when the record is gone. */
export function subjectName(t: Translate, subject: CertificateSubject | null | undefined): string {
  return subject?.name ?? t("certificates.subject.unknown");
}

/** "Class 5 A · ADM-12" or "PGT Mathematics · EMP-7". */
export function subjectSub(subject: CertificateSubject | null | undefined): string {
  return [subject?.detail, subject?.code].filter(Boolean).join(" · ");
}

/** "9876543210" → "XXXXXX3210": what screens show of an emergency number. */
export function maskPhone(phone: string | null | undefined): string {
  if (!phone) return "—";
  const digits = phone.replace(/\D/g, "");
  if (digits.length <= 4) return "X".repeat(digits.length);
  return `${"X".repeat(digits.length - 4)}${digits.slice(-4)}`;
}

/** "9876543210" → "98765 43210" for the printed card. */
export function printPhone(phone: string | null | undefined): string {
  if (!phone) return "—";
  const digits = phone.replace(/\D/g, "");
  return digits.length === 10 ? `${digits.slice(0, 5)} ${digits.slice(5)}` : phone;
}

/* ------------------------------------------------------------------ choosing a person */

export type PickedSubject = { type: CertificateSubjectType; id: string; name: string; code: string | null; detail: string | null };

/**
 * Finds a student (by name or admission number) or a member of staff (by name or employee code) through the
 * students and staff lists, which every certificate issuer may read.
 */
export function SubjectPicker({
  subjectType,
  onPick,
  autoFocus = false,
}: {
  subjectType: CertificateSubjectType;
  onPick: (subject: PickedSubject) => void;
  autoFocus?: boolean;
}) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const q = query.trim();
  const ready = q.length >= 2;
  const results = useApiData(ready ? `certificates:pick:${subjectType}:${q}` : null, async (): Promise<PickedSubject[]> => {
    if (subjectType === "STUDENT") {
      const page = await api.listStudents({ q, size: 10 });
      return page.items.map((s) => ({
        type: "STUDENT",
        id: s.id,
        name: s.fullName,
        code: s.admissionNo,
        detail: [s.className, s.sectionName].filter(Boolean).join(" ") || null,
      }));
    }
    const page = await staffApi.list({ q, size: 10 });
    return page.items.map((s) => ({ type: "STAFF", id: s.userId, name: s.name, code: s.employeeCode, detail: s.designation }));
  });

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const onChange = (value: string) => {
    setText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setQuery(value), 250);
  };

  const placeholder = t(subjectType === "STUDENT" ? "certificates.pick.student" : "certificates.pick.staff");
  const hits = results.data ?? [];

  return (
    <div className="flex flex-col gap-2" data-testid="subject-picker">
      <label className="search max-w-none flex-none">
        <Search size={18} aria-hidden="true" />
        <span className="sr-only">{placeholder}</span>
        <input
          type="search"
          name="subjectSearch"
          value={text}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          maxLength={100}
          autoComplete="off"
          autoFocus={autoFocus}
        />
      </label>
      {!q ? null : !ready ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("certificates.pick.more")}
        </p>
      ) : results.error && !results.data ? (
        <ErrorState error={results.error} onRetry={results.reload} />
      ) : results.loading && !results.data ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("common.loading")}
        </p>
      ) : hits.length === 0 ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("certificates.pick.none", { query: q })}
        </p>
      ) : (
        <ul className="pick-list" aria-label={t("certificates.pick.results")}>
          {hits.map((hit) => (
            <li key={hit.id}>
              <button type="button" className="pick" onClick={() => onPick(hit)}>
                <span className="avatar" aria-hidden="true">
                  {initials(hit.name)}
                </span>
                <span className="min-w-0 flex-1">
                  <b className="block">{hit.name}</b>
                  <span className="block text-[12.5px] text-ink-3">{[hit.detail, hit.code].filter(Boolean).join(" · ")}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The class sections for a section filter, as "Class 5 A" options. */
export function useSectionOptions(): { value: string; label: string }[] {
  const classes = useApiData("certificates:classes", api.listClasses).data ?? [];
  return classes.flatMap((c) => c.sections.map((s) => ({ value: s.id, label: `${c.name} ${s.name}` })));
}
