"use client";

import { Bus, Clock, MapPin } from "lucide-react";
import { useEffect } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import {
  BoardingPill,
  clockTime,
  eventLabel,
  positionText,
  tripKindLabel,
  vehicleTypeLabel,
} from "@/components/views/transport/transport-shared";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n, type Translate } from "@/lib/i18n";
import { timeOfDay, transportApi } from "@/lib/transport-api";
import type { ChildBus, FamilyBusEvent, FamilyTrip } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* /app/family/bus: each child's school bus (docs/api/phase-2-transport.md, "Families"): route, stop and times,
   today's trips, where the bus is (the nearest stop, no map) and today's boarding events. */

/** How often the page refreshes while it is open. */
const REFRESH_MS = 30_000;

const STATUS_TONE: Record<FamilyTrip["status"], PillTone> = { NOT_STARTED: "neutral", RUNNING: "good", COMPLETED: "info" };

/** "2 stops away", "At your stop" or "" (only while the trip runs). Pure and exported for tests. */
export function stopsAwayText(t: Translate, trip: Pick<FamilyTrip, "status" | "stopsAway">): string {
  if (trip.status !== "RUNNING" || trip.stopsAway === null) return "";
  if (trip.stopsAway <= 0) return t("transport.family.atStop");
  return plural(t, "transport.family.stopsAway", trip.stopsAway);
}

/** One line of today's log: "Boarded at Market Stop", "Delayed 15 min: tyre puncture". Pure and exported for tests. */
export function eventText(t: Translate, event: FamilyBusEvent): string {
  if (event.kind === "DELAYED") {
    return t("transport.family.delayEvent", { minutes: event.minutes ?? 0, reason: event.note ?? "" });
  }
  return event.stopName ? t("transport.family.eventAt", { what: eventLabel(t, event.kind), stop: event.stopName }) : eventLabel(t, event.kind);
}

export function FamilyBusView() {
  const { t } = useI18n();
  const mine = useApiData("me:transport", transportApi.mine);
  const { reload } = mine;
  useEffect(() => {
    const timer = window.setInterval(reload, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [reload]);
  const children = mine.data?.children ?? [];
  return (
    <>
      <PageHead title={t("nav.bus")} />
      {mine.error && !mine.data ? (
        <ErrorState error={mine.error} onRetry={mine.reload} />
      ) : !mine.data ? (
        <LoadingRows rows={4} />
      ) : children.length === 0 ? (
        <p className="empty" data-testid="family-bus-none">
          {t("transport.family.none")}
        </p>
      ) : (
        children.map((child) => <ChildCard key={child.studentId} child={child} />)
      )}
      <p className="text-[12.5px] text-ink-3">{t("transport.family.note")}</p>
    </>
  );
}

function ChildCard({ child }: { child: ChildBus }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const titleId = `family-bus-${child.studentId}`;
  const a = child.allocation;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={titleId} data-testid="family-bus-child">
      <div className="card-head">
        <h2 id={titleId}>
          {child.name}
          {child.classLabel ? <span className="ml-2 text-[13px] font-normal text-ink-3">{child.classLabel}</span> : null}
        </h2>
      </div>
      {!a ? (
        <p className="text-[13.5px] text-ink-3" data-testid="family-bus-no-route">
          {t("transport.family.noRoute")}
        </p>
      ) : (
        <>
          <div className="rowcard items-start" data-testid="family-bus-route">
            <span className="badge-ic" aria-hidden="true">
              <Bus size={18} />
            </span>
            <span className="min-w-0 flex-1">
              <b className="block">{a.routeName}</b>
              <span className="block text-[13px]">
                {t("transport.family.stop", { stop: a.stopName })}
                {a.landmark ? <span className="text-ink-3"> · {a.landmark}</span> : null}
              </span>
              <span className="block text-[12.5px] text-ink-3">
                {[
                  a.pickupTime ? t("transport.family.pickup", { time: timeOfDay(a.pickupTime) }) : null,
                  a.dropTime ? t("transport.family.drop", { time: timeOfDay(a.dropTime) }) : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
              {a.vehicleRegistrationNo ? (
                <span className="block text-[12.5px] text-ink-3">
                  {a.vehicleType ? `${vehicleTypeLabel(t, a.vehicleType)} ` : ""}
                  <span className="mono">{a.vehicleRegistrationNo}</span>
                </span>
              ) : null}
              {a.endDate ? (
                <span className="block text-[12.5px] text-ink-3">
                  {t("transport.family.until", { date: formatPlainDate(a.endDate, locale) })}
                </span>
              ) : null}
            </span>
          </div>
          {child.trips.length === 0 ? (
            <p className="text-[13px] text-ink-3">{t("transport.family.noTripsToday")}</p>
          ) : (
            child.trips.map((trip) => <TripBlock key={trip.kind} trip={trip} />)
          )}
        </>
      )}
    </section>
  );
}

function TripBlock({ trip }: { trip: FamilyTrip }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const away = stopsAwayText(t, trip);
  return (
    <div className="flex flex-col gap-2 border-t border-line pt-3" data-testid={`family-bus-trip-${trip.kind}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-[14px] font-semibold">{tripKindLabel(t, trip.kind)}</h3>
        <span className="flex flex-wrap items-center gap-1.5">
          <Pill tone={STATUS_TONE[trip.status]} dot={trip.status === "RUNNING"}>
            {t(`transport.family.status.${trip.status}`)}
          </Pill>
          {trip.status !== "NOT_STARTED" || trip.childStatus ? (
            <span data-testid="family-bus-child-status">
              <BoardingPill status={trip.childStatus} />
            </span>
          ) : null}
        </span>
      </div>
      {trip.childStatus && trip.childMarkedAt ? (
        <p className="text-[13px] text-ink-2">
          {t("transport.family.markedAt", { what: eventLabel(t, trip.childStatus), time: clockTime(trip.childMarkedAt, locale) })}
        </p>
      ) : null}
      {trip.status === "RUNNING" ? (
        <p className="flex items-start gap-2 text-[13.5px]" data-testid="family-bus-position">
          <MapPin size={16} aria-hidden="true" className="mt-0.5 flex-none text-ink-3" />
          <span>
            <b className="block">{positionText(t, trip.position)}</b>
            {trip.nextStop ? (
              <span className="block text-[12.5px] text-ink-2">{t("transport.trip.nextStop", { stop: trip.nextStop.name })}</span>
            ) : null}
            {away ? <span className="block text-[12.5px] font-semibold text-accent">{away}</span> : null}
          </span>
        </p>
      ) : null}
      {trip.delay ? (
        <p className="alert alert-warn" data-testid="family-bus-delay">
          <Clock size={16} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>{t("transport.trip.delayed", { minutes: trip.delay.minutes, reason: trip.delay.reason })}</span>
        </p>
      ) : null}
      {trip.stops.length > 0 && trip.status === "RUNNING" ? (
        <ol className="bus-stops" aria-label={t("transport.family.stops")}>
          {trip.stops.map((stop) => (
            <li
              key={stop.id}
              className="bus-stop"
              data-reached={stop.reached || undefined}
              data-next={stop.next || undefined}
              data-mine={stop.mine || undefined}
            >
              <span className="bus-stop-dot" aria-hidden="true" />
              <span className="min-w-0 flex-1">
                {stop.name}
                {stop.mine ? <span className="ml-1.5 text-[12px] font-semibold text-accent">{t("transport.family.yourStop")}</span> : null}
              </span>
              <span className="num text-[12.5px] text-ink-3">{timeOfDay(stop.time)}</span>
              <span className="sr-only">
                {stop.reached ? t("transport.stop.reached") : stop.next ? t("transport.stop.next") : ""}
              </span>
            </li>
          ))}
        </ol>
      ) : null}
      {trip.events.length > 0 ? (
        <ul className="flex flex-col gap-1 text-[13px]" aria-label={t("transport.family.today")} data-testid="family-bus-events">
          {trip.events.map((event, i) => (
            <li key={i} className="flex justify-between gap-3">
              <span className="min-w-0">{eventText(t, event)}</span>
              <span className="num flex-none text-ink-3">{clockTime(event.at, locale)}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
