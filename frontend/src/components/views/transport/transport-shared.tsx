"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Pill, type PillTone } from "@/components/ui/pill";
import { useAuth } from "@/lib/auth";
import { DISPLAY_TIME_ZONE, formatPlainDate } from "@/lib/format";
import { plural, translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { timeOfDay } from "@/lib/transport-api";
import type {
  BoardingStatus,
  BusPosition,
  CrewKind,
  DocumentKind,
  DocumentState,
  StopRef,
  TripEventKind,
  TripKind,
  VehicleStatus,
  VehicleType,
} from "@/lib/types";

/* Parts shared by the transport screens (docs/api/phase-2-transport.md). */

type TransportLink = { href: string; labelKey: MessageKey; permission: string; also?: string };

const TRANSPORT_LINKS: TransportLink[] = [
  { href: "/app/transport", labelKey: "transport.nav.routes", permission: PERMISSIONS.transportRead, also: "/app/transport/routes" },
  { href: "/app/transport/live", labelKey: "transport.nav.live", permission: PERMISSIONS.transportRead },
  { href: "/app/transport/allocations", labelKey: "transport.nav.students", permission: PERMISSIONS.transportRead },
  { href: "/app/transport/vehicles", labelKey: "transport.nav.vehicles", permission: PERMISSIONS.transportRead },
  { href: "/app/transport/crew", labelKey: "transport.nav.crew", permission: PERMISSIONS.transportRead },
  { href: "/app/transport/settings", labelKey: "transport.nav.settings", permission: PERMISSIONS.transportRead },
];

/** The transport section's own navigation under each page heading. */
export function TransportNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = TRANSPORT_LINKS.filter((link) => hasPermission(me, link.permission));
  if (links.length < 2) return null;
  return (
    <nav className="subnav no-print" aria-label={t("transport.nav.label")}>
      {links.map((link) => {
        const active =
          pathname === link.href ||
          (link.href !== "/app/transport" && pathname.startsWith(`${link.href}/`)) ||
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

/** True when the signed-in person manages transport (transport.manage). */
export function useCanManageTransport(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.transportManage);
}

/* ------------------------------------------------------------------ labels */

export const vehicleTypeLabel = (t: Translate, type: VehicleType) => translateOr(t, `transport.vehicleType.${type}`, type);
export const vehicleStatusLabel = (t: Translate, status: VehicleStatus) =>
  translateOr(t, `transport.vehicleStatus.${status}`, status);
export const documentLabel = (t: Translate, kind: DocumentKind) => translateOr(t, `transport.document.${kind}`, kind);
export const crewKindLabel = (t: Translate, kind: CrewKind) => translateOr(t, `transport.crewKind.${kind}`, kind);
export const tripKindLabel = (t: Translate, kind: TripKind) => translateOr(t, `transport.tripKind.${kind}`, kind);
export const boardingLabel = (t: Translate, status: BoardingStatus) => translateOr(t, `transport.boarding.${status}`, status);
export const eventLabel = (t: Translate, kind: TripEventKind) => translateOr(t, `transport.event.${kind}`, kind);
export const flagLabel = (t: Translate, flag: string) => translateOr(t, `transport.flag.${flag}`, flag);

const BAD_FLAGS = new Set([
  "FITNESS_INVALID",
  "DRIVER_LICENCE_INVALID",
  "LICENCE_INVALID",
  "DOCUMENT_EXPIRED",
  "OVER_CAPACITY",
  "NO_DRIVER",
  "VEHICLE_UNAVAILABLE",
  "LEFT",
]);

export const flagTone = (flag: string): PillTone => (BAD_FLAGS.has(flag) ? "bad" : "warn");

/** Warnings on a route, vehicle or crew member, worst first. */
export function FlagPills({ flags }: { flags: string[] }) {
  const { t } = useI18n();
  if (flags.length === 0) return null;
  const sorted = [...flags].sort((a, b) => Number(BAD_FLAGS.has(b)) - Number(BAD_FLAGS.has(a)));
  return (
    <span className="flex flex-wrap gap-1" data-testid="transport-flags">
      {sorted.map((flag) => (
        <Pill key={flag} tone={flagTone(flag)}>
          {flagLabel(t, flag)}
        </Pill>
      ))}
    </span>
  );
}

const STATE_TONE: Record<DocumentState, PillTone> = { VALID: "good", EXPIRING: "warn", EXPIRED: "bad", MISSING: "neutral" };

/** "Expires in 12 days", "Expired 3 days ago", "Valid" or "No date on record". Pure and exported for tests. */
export function documentStateText(t: Translate, state: DocumentState, daysLeft: number | null): string {
  if (state === "MISSING") return t("transport.docState.MISSING");
  if (state === "EXPIRED") {
    return daysLeft !== null && daysLeft < 0 ? plural(t, "transport.docState.expiredAgo", -daysLeft) : t("transport.docState.EXPIRED");
  }
  if (state === "EXPIRING" && daysLeft !== null) {
    return daysLeft === 0 ? t("transport.docState.today") : plural(t, "transport.docState.expiresIn", daysLeft);
  }
  return t("transport.docState.VALID");
}

export function DocumentStatePill({ state, daysLeft }: { state: DocumentState; daysLeft: number | null }) {
  const { t } = useI18n();
  return <Pill tone={STATE_TONE[state]}>{documentStateText(t, state, daysLeft)}</Pill>;
}

/** "10:42 am": the time of day in India. */
export function clockTime(value: string | Date, locale: string): string {
  return new Intl.DateTimeFormat(locale, { hour: "numeric", minute: "2-digit", timeZone: DISPLAY_TIME_ZONE }).format(
    typeof value === "string" ? new Date(value) : value,
  );
}

/** "14 Oct 2026" or "—" for a document date. */
export function dateOrDash(value: string | null | undefined, locale: string): string {
  return value ? formatPlainDate(value, locale) : "—";
}

export const BOARDING_TONE: Record<BoardingStatus, PillTone> = { BOARDED: "good", DROPPED: "info", ABSENT: "neutral" };

export function BoardingPill({ status }: { status: BoardingStatus | null }) {
  const { t } = useI18n();
  if (!status) return <Pill>{t("transport.boarding.pending")}</Pill>;
  return (
    <Pill tone={BOARDING_TONE[status]} dot>
      {boardingLabel(t, status)}
    </Pill>
  );
}

/** "07:10 – 15:10" for a stop's pickup and drop times. */
export function stopTimes(pickup: string | null, drop: string | null): string {
  return [timeOfDay(pickup), timeOfDay(drop)].filter(Boolean).join(" – ");
}

/**
 * Where the bus is, in words: "Near Market Stop · 2 min ago", "Updated just now", or "No recent position" when
 * nothing came in the last 5 minutes. Pure and exported for tests.
 */
export function positionText(
  t: Translate,
  position: Pick<BusPosition, "stale" | "minutesAgo" | "nearestStop"> | null | undefined,
): string {
  if (!position || position.stale) return t("transport.position.none");
  const ago =
    position.minutesAgo === null || position.minutesAgo <= 0
      ? t("transport.position.justNow")
      : plural(t, "transport.position.minutesAgo", position.minutesAgo);
  return position.nearestStop ? t("transport.position.near", { stop: position.nearestStop.name, ago }) : ago;
}

/**
 * The route as a line of stops (no map): reached stops filled, the next one ringed, and the bus drawn at the stop
 * nearest its last position. Only an illustration; the same facts are written out beside it.
 */
export function RouteLine({
  stops,
  reachedIds,
  nextId,
  busAt,
  label,
}: {
  stops: StopRef[];
  reachedIds: Set<string>;
  nextId: string | null;
  /** the stop nearest the bus's recent position, or null when there is none */
  busAt: string | null;
  label: string;
}) {
  if (stops.length === 0) return null;
  const gap = 56;
  const width = Math.max(1, stops.length - 1) * gap + 32;
  const y = 30;
  const x = (i: number) => 16 + i * gap;
  const busIndex = busAt ? stops.findIndex((s) => s.id === busAt) : -1;
  return (
    <svg
      className="route-line"
      viewBox={`0 0 ${width} 48`}
      width={width}
      height={48}
      role="img"
      aria-label={label}
      data-testid="route-line"
    >
      <line x1={x(0)} y1={y} x2={x(stops.length - 1)} y2={y} className="route-line-track" />
      {stops.map((stop, i) => {
        const reached = reachedIds.has(stop.id);
        return (
          <g key={stop.id}>
            <title>{`${i + 1}. ${stop.name}`}</title>
            <circle
              cx={x(i)}
              cy={y}
              r={stop.id === nextId ? 7 : 5.5}
              className={reached ? "route-line-stop route-line-reached" : stop.id === nextId ? "route-line-stop route-line-next" : "route-line-stop"}
            />
            <text x={x(i)} y={y + 16} textAnchor="middle" className="route-line-num">
              {i + 1}
            </text>
          </g>
        );
      })}
      {busIndex >= 0 ? (
        <g transform={`translate(${x(busIndex) - 9}, 2)`} data-testid="route-line-bus">
          <rect width="18" height="14" rx="3" className="route-line-bus" />
          <rect x="3" y="3" width="12" height="4" rx="1" className="route-line-bus-window" />
        </g>
      ) : null}
    </svg>
  );
}
