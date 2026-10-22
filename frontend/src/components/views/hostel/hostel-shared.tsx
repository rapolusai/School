"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { SelectField } from "@/components/ui/field";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPlainDate } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { localeFor, translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasAnyPermission, hasPermission, PERMISSIONS } from "@/lib/permissions";
import type {
  ComplaintCategory,
  ComplaintStatus,
  HostelPassStatus,
  HostelType,
  HostelView,
  Meal,
  PassKind,
  RollCallStatus,
  RoomType,
} from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";

/* Shared pieces of the hostel pages (docs/api/phase-3-hostel.md). */

/** The permissions that open the hostel pages: readers, managers and wardens. */
export const HOSTEL_PERMISSIONS = [PERMISSIONS.hostelRead, PERMISSIONS.hostelManage, PERMISSIONS.hostelWarden] as const;
const READ_OR_WARDEN = HOSTEL_PERMISSIONS;

const LINKS: { href: string; labelKey: MessageKey; exact?: boolean }[] = [
  { href: "/app/hostel", labelKey: "hostel.nav.hostels", exact: true },
  { href: "/app/hostel/occupancy", labelKey: "hostel.nav.occupancy" },
  { href: "/app/hostel/boarders", labelKey: "hostel.nav.boarders" },
  { href: "/app/hostel/passes", labelKey: "hostel.nav.passes" },
  { href: "/app/hostel/roll-call", labelKey: "hostel.nav.rollCall" },
  { href: "/app/hostel/mess", labelKey: "hostel.nav.mess" },
  { href: "/app/hostel/issues", labelKey: "hostel.nav.issues" },
];

/** The hostel module's own navigation under each page's heading. */
export function HostelNav() {
  const { t } = useI18n();
  const pathname = usePathname() ?? "";
  return (
    <nav className="subnav no-print" aria-label={t("hostel.nav.label")}>
      {LINKS.map((link) => {
        const active = link.exact ? pathname === link.href : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

/** True when the signed-in person sets hostels up and allots beds (hostel.manage). */
export function useCanManageHostel(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.hostelManage);
}

/** True when the signed-in person may open the hostel pages at all. */
export function useHostelAccess(): boolean {
  const { me } = useAuth();
  return hasAnyPermission(me, READ_OR_WARDEN);
}

/**
 * The hostels in scope and the one chosen (from ?hostel=, else the first). Wardens see only their own, so a single
 * hostel needs no picker.
 */
export function useChosenHostel() {
  const hostels = useApiData("hostel:hostels", hostelApi.hostels);
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const wanted = params?.get("hostel") ?? null;
  const list = hostels.data ?? [];
  const hostel = list.find((h) => h.id === wanted) ?? list[0];
  const choose = useCallback(
    (hostelId: string) => {
      const next = new URLSearchParams(params?.toString());
      next.set("hostel", hostelId);
      router.replace(`${pathname}?${next.toString()}`);
    },
    [params, pathname, router],
  );
  return { hostels, list, hostel, choose };
}

/** A select of the hostels in scope; nothing when there is only one. */
export function HostelPicker({ list, value, onChange }: { list: readonly HostelView[]; value: string | undefined; onChange: (id: string) => void }) {
  const { t } = useI18n();
  if (list.length < 2) return null;
  return (
    <SelectField
      label={t("hostel.field.hostel")}
      name="hostel"
      value={value ?? ""}
      onChange={(e) => onChange(e.target.value)}
      options={list.map((h) => ({ value: h.id, label: h.name }))}
      className="max-w-xs"
      data-testid="hostel-picker"
    />
  );
}

/* ------------------------------------------------------------------ labels and pills */

export const hostelTypeLabel = (t: Translate, type: HostelType) => translateOr(t, `hostel.type.${type}`, type);
export const roomTypeLabel = (t: Translate, type: RoomType) => translateOr(t, `hostel.roomType.${type}`, type);
export const mealLabel = (t: Translate, meal: Meal) => translateOr(t, `hostel.meal.${meal}`, meal);
export const passKindLabel = (t: Translate, kind: PassKind) => translateOr(t, `hostel.passKind.${kind}`, kind);
export const passStatusLabel = (t: Translate, status: HostelPassStatus) => translateOr(t, `hostel.passStatus.${status}`, status);
export const rollCallLabel = (t: Translate, status: RollCallStatus) => translateOr(t, `hostel.rollCall.${status}`, status);
export const complaintCategoryLabel = (t: Translate, category: ComplaintCategory) =>
  translateOr(t, `hostel.complaintCategory.${category}`, category);
export const complaintStatusLabel = (t: Translate, status: ComplaintStatus) =>
  translateOr(t, `hostel.complaintStatus.${status}`, status);
export const weekdayLabel = (t: Translate, dayOfWeek: number) => translateOr(t, `hostel.weekday.${dayOfWeek}`, String(dayOfWeek));

export const PASS_TONE: Record<HostelPassStatus, PillTone> = {
  REQUESTED: "warn",
  APPROVED: "info",
  OUT: "accent",
  RETURNED: "good",
  REJECTED: "bad",
  CANCELLED: "neutral",
};

export const ROLL_CALL_TONE: Record<RollCallStatus, PillTone> = { PRESENT: "good", ON_LEAVE: "info", ABSENT: "bad" };

export const COMPLAINT_TONE: Record<ComplaintStatus, PillTone> = { OPEN: "warn", IN_PROGRESS: "info", RESOLVED: "good" };

export function HostelPassPill({ status, late }: { status: HostelPassStatus; late?: boolean }) {
  const { t } = useI18n();
  return (
    <Pill tone={late ? "bad" : PASS_TONE[status]} dot>
      {late ? t("hostel.pass.late") : passStatusLabel(t, status)}
    </Pill>
  );
}

export function RollCallPill({ status }: { status: RollCallStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={ROLL_CALL_TONE[status]} dot>
      {rollCallLabel(t, status)}
    </Pill>
  );
}

export function ComplaintPill({ status }: { status: ComplaintStatus }) {
  const { t } = useI18n();
  return <Pill tone={COMPLAINT_TONE[status]}>{complaintStatusLabel(t, status)}</Pill>;
}

/** "12 Oct 2026" or "12 Oct 2026 to 15 Oct 2026" for a pass. */
export function passDates(from: string, to: string, locale: string, t: Translate): string {
  if (from === to) return formatPlainDate(from, locale);
  return t("hostel.pass.dates", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) });
}

/** A date-time in India, or an em dash. */
export function when(value: string | null | undefined, locale: string): string {
  return value ? formatDateTime(value, locale) : "—";
}

/** The locale of the current language, for the helpers above. */
export function useLocale(): string {
  const { lang } = useI18n();
  return localeFor(lang);
}

/** Loading, error or empty wrapper used by the list pages. */
export function ListState<T>({ data, empty, children }: { data: ApiData<T[]>; empty: string; children: (rows: T[]) => React.ReactNode }) {
  const rows = data.data;
  if (data.error && !rows) return <ErrorState error={data.error} onRetry={data.reload} />;
  if (!rows) return <LoadingRows rows={3} />;
  if (rows.length === 0) return <p className="empty">{empty}</p>;
  return <>{children(rows)}</>;
}
