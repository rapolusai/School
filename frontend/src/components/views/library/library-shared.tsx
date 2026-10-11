"use client";

import { Search } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ErrorState } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { initials } from "@/lib/format";
import { translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type {
  CopySource,
  CopyStatus,
  FineKind,
  FineStatus,
  LibraryMember,
  LibraryPaymentMode,
  MemberType,
  ReservationStatus,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* Parts shared by the library screens (docs/api/phase-2-library.md). */

type LibraryLink = { href: string; labelKey: MessageKey; permission: string; also?: string };

const LIBRARY_LINKS: LibraryLink[] = [
  { href: "/app/library", labelKey: "library.nav.catalogue", permission: PERMISSIONS.libraryRead, also: "/app/library/titles" },
  { href: "/app/library/desk", labelKey: "library.nav.desk", permission: PERMISSIONS.libraryManage },
  { href: "/app/library/holds", labelKey: "library.nav.holds", permission: PERMISSIONS.libraryManage },
  { href: "/app/library/fines", labelKey: "library.nav.fines", permission: PERMISSIONS.libraryManage },
  { href: "/app/library/labels", labelKey: "library.nav.labels", permission: PERMISSIONS.libraryManage },
  { href: "/app/library/stock", labelKey: "library.nav.stock", permission: PERMISSIONS.libraryManage },
  { href: "/app/library/import", labelKey: "library.nav.import", permission: PERMISSIONS.libraryManage },
  { href: "/app/library/settings", labelKey: "library.nav.settings", permission: PERMISSIONS.libraryManage },
];

/** The library's own navigation under each library page's heading; nothing for staff who may only search. */
export function LibraryNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = LIBRARY_LINKS.filter((link) => hasPermission(me, link.permission));
  if (links.length < 2) return null;
  return (
    <nav className="subnav no-print" aria-label={t("library.nav.label")}>
      {links.map((link) => {
        const active =
          pathname === link.href ||
          (link.href !== "/app/library" && pathname.startsWith(`${link.href}/`)) ||
          (link.also !== undefined && pathname.startsWith(link.also));
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

/** True when the signed-in person runs the library (library.manage). */
export function useCanManageLibrary(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.libraryManage);
}

/* ------------------------------------------------------------------ labels and pills */

export const COPY_TONE: Record<CopyStatus, PillTone> = {
  AVAILABLE: "good",
  ISSUED: "info",
  RESERVED: "accent",
  LOST: "bad",
  DAMAGED: "warn",
  WITHDRAWN: "neutral",
};

export const FINE_TONE: Record<FineStatus, PillTone> = { DUE: "warn", PAID: "good", WAIVED: "neutral" };

export const HOLD_TONE: Record<ReservationStatus, PillTone> = {
  WAITING: "info",
  READY: "good",
  FULFILLED: "neutral",
  CANCELLED: "neutral",
  EXPIRED: "neutral",
};

export const copyStatusLabel = (t: Translate, status: CopyStatus) =>
  translateOr(t, `library.copy.status.${status}`, status);
export const fineStatusLabel = (t: Translate, status: FineStatus) =>
  translateOr(t, `library.fine.status.${status}`, status);
export const holdStatusLabel = (t: Translate, status: ReservationStatus) =>
  translateOr(t, `library.hold.status.${status}`, status);
export const fineKindLabel = (t: Translate, kind: FineKind) => translateOr(t, `library.fine.kind.${kind}`, kind);
export const modeLabel = (t: Translate, mode: LibraryPaymentMode) => translateOr(t, `library.mode.${mode}`, mode);
export const memberTypeLabel = (t: Translate, type: MemberType) => translateOr(t, `library.memberType.${type}`, type);
export const sourceLabel = (t: Translate, source: CopySource) => translateOr(t, `library.source.${source}`, source);

export function CopyStatusPill({ status }: { status: CopyStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={COPY_TONE[status]} dot>
      {copyStatusLabel(t, status)}
    </Pill>
  );
}

export function FineStatusPill({ status }: { status: FineStatus }) {
  const { t } = useI18n();
  return <Pill tone={FINE_TONE[status]}>{fineStatusLabel(t, status)}</Pill>;
}

export function HoldStatusPill({ status }: { status: ReservationStatus }) {
  const { t } = useI18n();
  return <Pill tone={HOLD_TONE[status]}>{holdStatusLabel(t, status)}</Pill>;
}

/** "LIB-1 · Class 5 A" for a student, "AKS-203 · Librarian" for staff. */
export function memberSub(member: LibraryMember | null | undefined): string {
  if (!member) return "";
  return [member.code, member.detail].filter(Boolean).join(" · ");
}

export function memberName(t: Translate, member: LibraryMember | null | undefined): string {
  return member?.name ?? t("library.member.unknown");
}

/* ------------------------------------------------------------------ checks shared with the API */

/** The API's limits (LibraryForms.java). */
export const MAX_COPIES_AT_ONCE = 100;
export const MAX_PRICE_PAISE = 100_000_000;
export const MAX_BACKDATE_DAYS = 60;
export const ACCESSION_PREFIX_PATTERN = /^[A-Z0-9/-]{0,8}$/;

const ISBN_SEPARATORS = /[\s\-‐‑‒–]/g;

/** An ISBN as printed ("81-7371-410-X") without spaces and hyphens, x made X. */
export function cleanIsbn(value: string): string {
  return value.replace(ISBN_SEPARATORS, "").toUpperCase();
}

function check10(first9: string): string {
  let sum = 0;
  for (let i = 0; i < 9; i++) sum += (10 - i) * Number(first9[i]);
  const check = (11 - (sum % 11)) % 11;
  return check === 10 ? "X" : String(check);
}

function check13(first12: string): string {
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += (i % 2 === 0 ? 1 : 3) * Number(first12[i]);
  return String((10 - (sum % 10)) % 10);
}

/**
 * What is wrong with an ISBN as a message key, or null when it is a valid ISBN-10 or ISBN-13 (the API's rules: the
 * check digit adds up, an ISBN-13 starts with 978 or 979). Pure and exported for tests.
 */
export function isbnProblem(value: string): MessageKey | null {
  const isbn = cleanIsbn(value);
  if (/^\d{9}[\dX]$/.test(isbn)) return check10(isbn.slice(0, 9)) === isbn[9] ? null : "library.v.isbnCheck";
  if (/^\d{13}$/.test(isbn)) {
    if (!isbn.startsWith("978") && !isbn.startsWith("979")) return "library.v.isbnPrefix";
    return check13(isbn.slice(0, 12)) === isbn[12] ? null : "library.v.isbnCheck";
  }
  return "library.v.isbnDigits";
}

/** The ISBN-13 of a valid ISBN-10 or ISBN-13, as the catalogue stores it. Pure and exported for tests. */
export function toIsbn13(value: string): string | null {
  if (isbnProblem(value)) return null;
  const isbn = cleanIsbn(value);
  if (isbn.length === 13) return isbn;
  const first12 = `978${isbn.slice(0, 9)}`;
  return first12 + check13(first12);
}

/** "2026-10-09" moved by n days. */
export function shiftDate(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Shortens a title for a label: whole words up to `max` characters, with an ellipsis when cut. */
export function shortTitle(title: string, max = 30): string {
  const clean = title.replace(/\s+/g, " ").trim();
  if (clean.length <= max) return clean;
  const cut = clean.slice(0, max - 1);
  const space = cut.lastIndexOf(" ");
  return `${(space > max / 2 ? cut.slice(0, space) : cut).replace(/[\s,;:.-]+$/, "")}…`;
}

/* ------------------------------------------------------------------ member search */

/**
 * Finds an active student or member of staff by name, admission number or employee code (GET /api/library/members)
 * and hands the pick to `onPick`. Searches 250 ms after typing stops, from two characters.
 */
export function MemberPicker({
  onPick,
  label,
  autoFocus = false,
}: {
  onPick: (member: LibraryMember) => void;
  label?: string;
  autoFocus?: boolean;
}) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const q = query.trim();
  const ready = q.length >= 2;
  const results = useApiData(ready ? `library:members:${q}` : null, () => libraryApi.members(q));

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

  const placeholder = label ?? t("library.member.search");
  const hits = results.data ?? [];

  return (
    <div className="flex flex-col gap-2" data-testid="member-picker">
      <label className="search max-w-none flex-none">
        <Search size={18} aria-hidden="true" />
        <span className="sr-only">{placeholder}</span>
        <input
          type="search"
          name="memberSearch"
          value={text}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          maxLength={100}
          autoComplete="off"
          autoFocus={autoFocus}
          data-autofocus={autoFocus ? true : undefined}
        />
      </label>
      {!q ? null : !ready ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("library.member.searchMore")}
        </p>
      ) : results.error && !results.data ? (
        <ErrorState error={results.error} onRetry={results.reload} />
      ) : results.loading && !results.data ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("common.loading")}
        </p>
      ) : hits.length === 0 ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("library.member.none", { query: q })}
        </p>
      ) : (
        <ul className="pick-list" aria-label={t("library.member.results")}>
          {hits.map((hit) => (
            <li key={`${hit.type}:${hit.id}`}>
              <button type="button" className="pick" onClick={() => onPick(hit)}>
                <span className="avatar" aria-hidden="true">
                  {initials(hit.name ?? "?")}
                </span>
                <span className="min-w-0 flex-1 text-left">
                  <b className="block truncate font-semibold">{memberName(t, hit)}</b>
                  <span className="block truncate text-[12.5px] text-ink-3">{memberSub(hit)}</span>
                </span>
                <Pill tone={hit.type === "STUDENT" ? "info" : "accent"}>{memberTypeLabel(t, hit.type)}</Pill>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
