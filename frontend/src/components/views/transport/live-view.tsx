"use client";

import { Clock, MapPin, RefreshCw } from "lucide-react";
import { useEffect } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatLongDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { transportApi } from "@/lib/transport-api";
import type { LiveRoute, LiveTrip } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { boardingLabel, clockTime, positionText, RouteLine, TransportNav, tripKindLabel } from "./transport-shared";

/** How often the board refreshes while it is open. */
const REFRESH_MS = 30_000;

/** /app/transport/live: today's trips on every route, where each bus is (no map) and how boarding is going. */
export function LiveView() {
  const { t, lang } = useI18n();
  const live = useApiData("transport:live", transportApi.live);
  const { reload } = live;
  useEffect(() => {
    const timer = window.setInterval(reload, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [reload]);
  const routes = live.data?.routes ?? [];

  return (
    <>
      <PageHead
        eyebrow={live.data ? formatLongDate(live.data.today, localeFor(lang)) : t("nav.transport")}
        title={t("transport.live.title")}
        actions={
          <button type="button" className="btn" onClick={reload} disabled={live.loading}>
            <RefreshCw size={18} aria-hidden="true" />
            {t("transport.live.refresh")}
          </button>
        }
      />
      <TransportNav />
      {live.error && !live.data ? (
        <ErrorState error={live.error} onRetry={reload} />
      ) : !live.data ? (
        <LoadingRows rows={4} />
      ) : routes.length === 0 ? (
        <section className="card">
          <p className="empty">{t("transport.live.empty")}</p>
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2" data-testid="live-board">
          {routes.map((route) => (
            <LiveRouteCard key={route.route.id} route={route} />
          ))}
        </div>
      )}
      <p className="text-[12.5px] text-ink-3">{t("transport.live.note")}</p>
    </>
  );
}

function LiveRouteCard({ route }: { route: LiveRoute }) {
  const { t } = useI18n();
  const titleId = `live-${route.route.id}`;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={titleId} data-testid="live-route">
      <div className="card-head">
        <h2 id={titleId}>{route.route.name}</h2>
        {route.vehicleRegistrationNo ? <span className="mono text-[13px] text-ink-2">{route.vehicleRegistrationNo}</span> : null}
      </div>
      {route.trips.length === 0 ? (
        <p className="text-[13px] text-ink-3">{t("transport.live.notStarted")}</p>
      ) : (
        route.trips.map((trip) => <LiveTripBlock key={trip.id} trip={trip} route={route} />)
      )}
    </section>
  );
}

function LiveTripBlock({ trip, route }: { trip: LiveTrip; route: LiveRoute }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const running = trip.status === "RUNNING";
  // The afternoon trip runs the stops in reverse.
  const stops = trip.kind === "AFTERNOON" ? [...route.stops].reverse() : route.stops;
  const reachedIndex = trip.reachedStop ? stops.findIndex((s) => s.id === trip.reachedStop?.id) : -1;
  const reached = new Set(stops.slice(0, reachedIndex + 1).map((s) => s.id));
  const busAt = running && trip.position && !trip.position.stale ? (trip.position.nearestStop?.id ?? null) : null;
  return (
    <div className="flex flex-col gap-2 border-t border-line pt-3 first:border-t-0 first:pt-0" data-testid={`live-trip-${trip.kind}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-[14px] font-semibold">{tripKindLabel(t, trip.kind)}</h3>
        {running ? (
          <Pill tone="good" dot>
            {t("transport.trip.running")}
          </Pill>
        ) : (
          <Pill>{t("transport.live.endedAt", { time: trip.endedAt ? clockTime(trip.endedAt, locale) : "—" })}</Pill>
        )}
      </div>
      {running ? (
        <p className="flex items-start gap-2 text-[13.5px]" data-testid="live-position">
          <MapPin size={16} aria-hidden="true" className="mt-0.5 flex-none text-ink-3" />
          <span>
            <b className={`block ${!trip.position || trip.position.stale ? "text-warn" : ""}`}>{positionText(t, trip.position)}</b>
            {trip.nextStop ? <span className="block text-[12.5px] text-ink-2">{t("transport.trip.nextStop", { stop: trip.nextStop.name })}</span> : null}
          </span>
        </p>
      ) : null}
      <div className="overflow-x-auto">
        <RouteLine stops={stops} reachedIds={reached} nextId={running ? (trip.nextStop?.id ?? null) : null} busAt={busAt} label={positionText(t, trip.position)} />
      </div>
      <p className="text-[12.5px] text-ink-2">
        {[
          `${boardingLabel(t, "BOARDED")} ${trip.counts.boarded}`,
          trip.kind === "AFTERNOON" ? `${boardingLabel(t, "DROPPED")} ${trip.counts.dropped}` : null,
          `${boardingLabel(t, "ABSENT")} ${trip.counts.absent}`,
          `${t("transport.trip.toMark")} ${trip.counts.pending}`,
        ]
          .filter(Boolean)
          .join(" · ")}
      </p>
      {trip.delay ? (
        <p className="alert alert-warn">
          <Clock size={16} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>{t("transport.trip.delayed", { minutes: trip.delay.minutes, reason: trip.delay.reason })}</span>
        </p>
      ) : null}
    </div>
  );
}
