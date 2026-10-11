"use client";

import { ArrowLeft, Clock, LocateFixed, LocateOff, MapPin, Play, Square, TriangleAlert } from "lucide-react";
import { useEffect, useEffectEvent, useState, useSyncExternalStore } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatLongDate } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { POSITION_INTERVAL_MS, timeOfDay, transportApi } from "@/lib/transport-api";
import type { BoardingStatus, MyRoute, Trip, TripKind, TripStudent } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { BoardingPill, boardingLabel, clockTime, positionText, RouteLine, tripKindLabel } from "./transport-shared";

/* /app/transport/trip: the driver's or attendant's phone screen for today's trips (docs/api/phase-2-transport.md,
   "Trips"). While a trip runs and this screen is open, the phone's location goes to the school every 30 seconds. */

type Selection = { routeId: string; kind: TripKind };

/** The marks a trip takes: boarded or absent in the morning; boarded (at school), dropped or absent after school. */
export function marksFor(kind: TripKind): BoardingStatus[] {
  return kind === "MORNING" ? ["BOARDED", "ABSENT"] : ["BOARDED", "DROPPED", "ABSENT"];
}

/** The trip to open on arrival: the one already running, if any. Pure and exported for tests. */
export function runningTrip(routes: MyRoute[]): Selection | null {
  for (const route of routes) {
    if (route.morning?.status === "RUNNING") return { routeId: route.routeId, kind: "MORNING" };
    if (route.afternoon?.status === "RUNNING") return { routeId: route.routeId, kind: "AFTERNOON" };
  }
  return null;
}

export function TripScreen() {
  const { t, lang } = useI18n();
  const mine = useApiData("transport:my-trips", transportApi.myTrips);
  // undefined: not chosen yet (open the running trip, if any); null: the list.
  const [picked, setPicked] = useState<Selection | null | undefined>(undefined);
  const routes = mine.data?.routes ?? [];
  const selection = picked === undefined ? runningTrip(routes) : picked;
  const route = selection ? routes.find((r) => r.routeId === selection.routeId) : undefined;

  if (mine.error && !mine.data) {
    return (
      <>
        <PageHead title={t("transport.trip.title")} />
        <ErrorState error={mine.error} onRetry={mine.reload} />
      </>
    );
  }
  if (!mine.data) {
    return (
      <>
        <PageHead title={t("transport.trip.title")} />
        <LoadingRows rows={4} />
      </>
    );
  }
  if (selection && route) {
    return (
      <TripPanel
        key={`${selection.routeId}:${selection.kind}`}
        route={route}
        kind={selection.kind}
        onBack={() => {
          setPicked(null);
          mine.reload();
        }}
      />
    );
  }
  return (
    <>
      <PageHead eyebrow={formatLongDate(mine.data.today, localeFor(lang))} title={t("transport.trip.title")} />
      {routes.length === 0 ? (
        <section className="card flex flex-col gap-2" data-testid="trip-no-routes">
          <p className="empty">{t("transport.trip.noRoutes")}</p>
          <p className="text-[13px] text-ink-3">{t("transport.trip.noRoutesHint")}</p>
        </section>
      ) : (
        routes.map((r) => <RouteCard key={r.routeId} route={r} onOpen={(kind) => setPicked({ routeId: r.routeId, kind })} />)
      )}
    </>
  );
}

const ROLE_KEYS: Record<string, MessageKey> = {
  DRIVER: "transport.trip.role.DRIVER",
  ATTENDANT: "transport.trip.role.ATTENDANT",
  MANAGER: "transport.trip.role.MANAGER",
};

function RouteCard({ route, onOpen }: { route: MyRoute; onOpen: (kind: TripKind) => void }) {
  const { t } = useI18n();
  const titleId = `trip-route-${route.routeId}`;
  const button = (kind: TripKind) => {
    const brief = kind === "MORNING" ? route.morning : route.afternoon;
    const runs = kind === "MORNING" ? route.runsMorning : route.runsAfternoon;
    if (!runs) return null;
    const label =
      brief?.status === "RUNNING"
        ? t("transport.trip.continue", { kind: tripKindLabel(t, kind) })
        : brief?.status === "COMPLETED"
          ? t("transport.trip.viewDone", { kind: tripKindLabel(t, kind) })
          : t("transport.trip.open", { kind: tripKindLabel(t, kind) });
    return (
      <button
        type="button"
        className={`btn btn-lg ${brief?.status === "COMPLETED" ? "" : "btn-primary"}`}
        onClick={() => onOpen(kind)}
        data-testid={`trip-open-${kind}`}
      >
        {label}
        {brief?.status === "RUNNING" ? <Pill tone="good" dot>{t("transport.trip.running")}</Pill> : null}
      </button>
    );
  };
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={titleId} data-testid="trip-route">
      <div className="card-head">
        <h2 id={titleId}>{route.name}</h2>
        {route.vehicleRegistrationNo ? <span className="mono text-[13px] text-ink-2">{route.vehicleRegistrationNo}</span> : null}
      </div>
      <p className="text-[13px] text-ink-2">
        {plural(t, "transport.trip.students", route.students)} · {t(ROLE_KEYS[route.role] ?? "transport.trip.role.MANAGER")}
      </p>
      <div className="flex flex-col gap-2 sm:flex-row">
        {button("MORNING")}
        {button("AFTERNOON")}
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ location */

type Sharing = { status: "starting" | "sharing" | "denied" | "error"; sentAt: Date | null };

const noopSubscribe = () => () => {};
const hasGeolocation = () => typeof navigator !== "undefined" && "geolocation" in navigator && Boolean(navigator.geolocation);

/**
 * Sends the phone's location for a running trip now and every 30 seconds while the screen is open, handing each
 * updated trip back. Stops when the trip ends or the screen closes.
 */
function useLocationSharing(tripId: string | null, onTrip: (trip: Trip) => void): Sharing & { supported: boolean } {
  const supported = useSyncExternalStore(noopSubscribe, hasGeolocation, () => true);
  const [sharing, setSharing] = useState<Sharing>({ status: "starting", sentAt: null });
  const handTrip = useEffectEvent((trip: Trip) => onTrip(trip));

  useEffect(() => {
    if (!tripId || !hasGeolocation()) return;
    let cancelled = false;
    const tick = () => {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          if (cancelled) return;
          transportApi
            .position(tripId, {
              latitude: pos.coords.latitude,
              longitude: pos.coords.longitude,
              accuracyM: Number.isFinite(pos.coords.accuracy) ? Math.round(pos.coords.accuracy) : null,
              speedKmh:
                pos.coords.speed !== null && Number.isFinite(pos.coords.speed)
                  ? Math.min(300, Math.round(pos.coords.speed * 3.6 * 10) / 10)
                  : null,
            })
            .then(
              (trip) => {
                if (cancelled) return;
                handTrip(trip);
                setSharing({ status: "sharing", sentAt: new Date() });
              },
              () => {
                if (!cancelled) setSharing((prev) => ({ ...prev, status: "error" }));
              },
            );
        },
        (error) => {
          if (!cancelled) setSharing((prev) => ({ ...prev, status: error.code === 1 ? "denied" : "error" }));
        },
        { enableHighAccuracy: true, timeout: 20_000, maximumAge: 10_000 },
      );
    };
    tick();
    const timer = window.setInterval(tick, POSITION_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [tripId]);

  return { ...sharing, supported };
}

function SharingLine({ sharing, t, locale }: { sharing: Sharing & { supported: boolean }; t: Translate; locale: string }) {
  if (!sharing.supported) {
    return (
      <p className="alert alert-warn" data-testid="trip-location">
        <LocateOff size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        <span>{t("transport.trip.location.unsupported")}</span>
      </p>
    );
  }
  if (sharing.status === "denied") {
    return (
      <p className="alert alert-warn" data-testid="trip-location">
        <LocateOff size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        <span>{t("transport.trip.location.denied")}</span>
      </p>
    );
  }
  return (
    <p className="flex items-center gap-2 text-[13px] text-ink-2" data-testid="trip-location">
      <LocateFixed size={16} aria-hidden="true" className="flex-none text-accent" />
      {sharing.status === "error"
        ? t("transport.trip.location.retrying")
        : sharing.sentAt
          ? t("transport.trip.location.sent", { time: clockTime(sharing.sentAt, locale) })
          : t("transport.trip.location.starting")}
    </p>
  );
}

/* ------------------------------------------------------------------ one trip */

function TripPanel({ route, kind, onBack }: { route: MyRoute; kind: TripKind; onBack: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const today = useApiData(`transport:trip:${route.routeId}:${kind}`, () => transportApi.todayTrip(route.routeId, kind));
  const [latest, setLatest] = useState<Trip | null>(null);
  const trip = latest ?? today.data ?? null;
  const running = trip?.status === "RUNNING";
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [delayOpen, setDelayOpen] = useState(false);
  const [ending, setEnding] = useState(false);
  const sharing = useLocationSharing(running ? trip.id : null, setLatest);

  const act = async (key: string, action: () => Promise<Trip>) => {
    setBusy(key);
    setProblem(null);
    try {
      setLatest(await action());
      return true;
    } catch (caught) {
      setProblem(errorMessage(toApiError(caught), t));
      return false;
    } finally {
      setBusy(null);
    }
  };

  const head = (
    <PageHead
      eyebrow={`${route.name}${route.vehicleRegistrationNo ? ` · ${route.vehicleRegistrationNo}` : ""}`}
      title={t("transport.trip.kindTitle", { kind: tripKindLabel(t, kind) })}
      actions={
        <button type="button" className="btn" onClick={onBack}>
          <ArrowLeft size={18} aria-hidden="true" />
          {t("transport.trip.allTrips")}
        </button>
      }
    />
  );

  if (today.error && !today.data && !latest) {
    return (
      <>
        {head}
        <ErrorState error={today.error} onRetry={today.reload} />
      </>
    );
  }
  if (today.loading && !latest) {
    return (
      <>
        {head}
        <LoadingRows rows={5} />
      </>
    );
  }
  if (!trip) {
    return (
      <>
        {head}
        <section className="card flex flex-col items-start gap-3" data-testid="trip-not-started">
          <p className="text-ink-2">{plural(t, "transport.trip.notStarted", route.students)}</p>
          <p className="text-[13px] text-ink-3">{t("transport.trip.locationNote")}</p>
          <FormAlert message={problem} />
          <button
            type="button"
            className="btn btn-primary btn-lg w-full sm:w-auto"
            disabled={busy !== null}
            onClick={async () => {
              if (await act("start", () => transportApi.startTrip(route.routeId, kind))) toast(t("transport.trip.started"));
            }}
          >
            <Play size={18} aria-hidden="true" />
            {busy === "start" ? t("common.working") : t("transport.trip.start", { kind: tripKindLabel(t, kind) })}
          </button>
        </section>
      </>
    );
  }

  const marks = marksFor(kind);
  const reached = new Set(trip.stops.filter((s) => s.reached).map((s) => s.id));
  const next = trip.stops.find((s) => s.next) ?? null;
  const busAt = trip.position && !trip.position.stale ? (trip.position.nearestStop?.id ?? null) : null;

  return (
    <>
      {head}
      <section className="card flex flex-col gap-3" aria-label={t("transport.trip.status")} data-testid="trip-panel">
        <div className="flex flex-wrap items-center justify-between gap-2">
          {running ? (
            <Pill tone="good" dot>
              {t("transport.trip.running")}
            </Pill>
          ) : (
            <Pill tone="neutral">{t("transport.trip.completed")}</Pill>
          )}
          <span className="text-[13px] text-ink-3">
            {t("transport.trip.startedBy", {
              time: formatDateTime(trip.startedAt, locale),
              name: trip.startedByName ?? "—",
            })}
          </span>
        </div>
        {running ? <SharingLine sharing={sharing} t={t} locale={locale} /> : null}
        <p className="flex items-start gap-2 text-[14px]" data-testid="trip-position">
          <MapPin size={18} aria-hidden="true" className="mt-0.5 flex-none text-ink-3" />
          <span>
            <b className="block">{positionText(t, trip.position)}</b>
            {next ? <span className="block text-[13px] text-ink-2">{t("transport.trip.nextStop", { stop: next.name })}</span> : null}
          </span>
        </p>
        <div className="overflow-x-auto">
          <RouteLine
            stops={trip.stops}
            reachedIds={reached}
            nextId={next?.id ?? null}
            busAt={busAt}
            label={positionText(t, trip.position)}
          />
        </div>
        <dl className="att-counts" data-testid="trip-counts">
          <div>
            <dt>{boardingLabel(t, "BOARDED")}</dt>
            <dd>{trip.counts.boarded}</dd>
          </div>
          {kind === "AFTERNOON" ? (
            <div>
              <dt>{boardingLabel(t, "DROPPED")}</dt>
              <dd>{trip.counts.dropped}</dd>
            </div>
          ) : null}
          <div>
            <dt>{boardingLabel(t, "ABSENT")}</dt>
            <dd>{trip.counts.absent}</dd>
          </div>
          <div className={trip.counts.pending > 0 ? "att-count-todo" : undefined}>
            <dt>{t("transport.trip.toMark")}</dt>
            <dd>{trip.counts.pending}</dd>
          </div>
        </dl>
        {trip.delay ? (
          <p className="alert alert-warn" data-testid="trip-delay">
            <Clock size={18} aria-hidden="true" className="mt-0.5 flex-none" />
            <span>{t("transport.trip.delayed", { minutes: trip.delay.minutes, reason: trip.delay.reason })}</span>
          </p>
        ) : null}
        <FormAlert message={problem} />
        {running ? (
          <div className="flex flex-col gap-2 sm:flex-row">
            <button type="button" className="btn" onClick={() => setDelayOpen(true)} data-testid="trip-report-delay">
              <TriangleAlert size={18} aria-hidden="true" />
              {t("transport.trip.reportDelay")}
            </button>
            <button type="button" className="btn btn-danger" onClick={() => setEnding(true)} data-testid="trip-end">
              <Square size={16} aria-hidden="true" />
              {t("transport.trip.end")}
            </button>
          </div>
        ) : null}
      </section>

      {trip.stops.map((stop) => (
        <section
          key={stop.id}
          className="card flex flex-col gap-1"
          aria-labelledby={`trip-stop-${stop.id}`}
          data-testid="trip-stop"
        >
          <div className="card-head">
            <h2 id={`trip-stop-${stop.id}`}>
              <span className="text-ink-3">{stop.seq}.</span> {stop.name}
            </h2>
            <span className="flex items-center gap-2">
              {stop.time ? <span className="num text-[13px] text-ink-2">{timeOfDay(stop.time)}</span> : null}
              {stop.reached ? <Pill tone="good">{t("transport.stop.reached")}</Pill> : stop.next ? <Pill tone="info">{t("transport.stop.next")}</Pill> : null}
            </span>
          </div>
          {stop.landmark ? <p className="text-[12.5px] text-ink-3">{stop.landmark}</p> : null}
          {stop.students.length === 0 ? (
            <p className="text-[13px] text-ink-3">{t("transport.trip.noStudentsHere")}</p>
          ) : (
            <ul className="att-list">
              {stop.students.map((student) => (
                <StudentRow
                  key={student.id}
                  student={student}
                  marks={marks}
                  running={running}
                  busy={busy}
                  onMark={(status) =>
                    act(`${student.id}:${status}`, () => transportApi.mark(trip.id, student.id, status))
                  }
                />
              ))}
            </ul>
          )}
        </section>
      ))}

      {delayOpen ? (
        <DelayDialog
          tripId={trip.id}
          onClose={() => setDelayOpen(false)}
          onSaved={(next) => {
            setLatest(next);
            setDelayOpen(false);
            toast(t("transport.trip.delaySent"));
          }}
        />
      ) : null}
      <ConfirmDialog
        open={ending}
        danger={false}
        title={t("transport.trip.endTitle")}
        body={
          trip.counts.pending > 0
            ? plural(t, "transport.trip.endPending", trip.counts.pending)
            : t("transport.trip.endBody")
        }
        confirmLabel={t("transport.trip.end")}
        onClose={() => setEnding(false)}
        onConfirm={async () => {
          setLatest(await transportApi.endTrip(trip.id));
          setEnding(false);
          toast(t("transport.trip.ended"));
        }}
      />
    </>
  );
}

function StudentRow({
  student,
  marks,
  running,
  busy,
  onMark,
}: {
  student: TripStudent;
  marks: BoardingStatus[];
  running: boolean;
  busy: string | null;
  onMark: (status: BoardingStatus) => void;
}) {
  const { t } = useI18n();
  return (
    <li className="att-row" data-testid="trip-student">
      <span className="person min-w-0">
        <b className="block">{student.name}</b>
        <span className="block text-[12.5px] text-ink-3">
          {[student.classLabel, student.admissionNo].filter(Boolean).join(" · ")}
        </span>
      </span>
      {running ? (
        <span className="att-opts trip-opts" role="group" aria-label={t("transport.trip.markFor", { name: student.name })}>
          {marks.map((status) => (
            <button
              key={status}
              type="button"
              data-v={status}
              aria-pressed={student.status === status}
              disabled={busy !== null}
              onClick={() => onMark(status)}
            >
              {busy === `${student.id}:${status}` ? "…" : boardingLabel(t, status)}
            </button>
          ))}
        </span>
      ) : (
        <BoardingPill status={student.status} />
      )}
    </li>
  );
}

type DelayValues = { minutes: string; reason: string };

/** The same checks the API makes on a delay. Pure and exported for tests. */
export function validateDelay(values: DelayValues): Problems {
  const problems: Problems = {};
  const minutes = values.minutes.trim();
  if (!/^\d+$/.test(minutes) || Number(minutes) < 1 || Number(minutes) > 600) problems.minutes = "transport.v.delayMinutes";
  if (!values.reason.trim()) problems.reason = "validation.required";
  else if (values.reason.trim().length > 300) problems.reason = "validation.tooLong";
  return problems;
}

function DelayDialog({ tripId, onClose, onSaved }: { tripId: string; onClose: () => void; onSaved: (trip: Trip) => void }) {
  const { t } = useI18n();
  const form = useForm<DelayValues>({ minutes: "10", reason: "" });
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateDelay(values), t, event.currentTarget)) return;
    const result: { trip?: Trip } = {};
    const ok = await form.submit(t, async () => {
      result.trip = await transportApi.delay(tripId, { minutes: Number(values.minutes.trim()), reason: values.reason.trim() });
    });
    if (ok && result.trip) onSaved(result.trip);
  };
  return (
    <Dialog open onClose={onClose} title={t("transport.trip.reportDelay")} description={t("transport.trip.delayIntro")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="delay-form">
        <FormAlert message={form.formError} />
        <TextField
          label={t("transport.trip.delayMinutes")}
          name="minutes"
          inputMode="numeric"
          value={values.minutes}
          onChange={(e) => set("minutes", e.target.value)}
          error={errors.minutes}
          maxLength={3}
        />
        <TextAreaField
          label={t("transport.trip.delayReason")}
          name="reason"
          value={values.reason}
          onChange={(e) => set("reason", e.target.value)}
          error={errors.reason}
          hint={t("transport.trip.delayReasonHint")}
          maxLength={300}
          rows={3}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("transport.trip.sendDelay")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
