"use client";

import { ArrowDown, ArrowLeft, ArrowUp, Pencil, Plus, Trash2, UserPlus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey } from "@/lib/i18n";
import { timeOfDay, transportApi } from "@/lib/transport-api";
import type { FeeZone, RouteDetail, StopRequest, TransportStop } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { RouteDialog, tripsText } from "./routes-view";
import { FlagPills, stopTimes, TransportNav, useCanManageTransport } from "./transport-shared";

/** /app/transport/routes/[id]: a route's vehicle and crew, its stops in pickup order and its students. */
export function RouteDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageTransport();
  const route = useApiData(`transport:route:${id}`, () => transportApi.route(id));
  const riders = useApiData(`transport:route:${id}:students`, () => transportApi.allocations({ routeId: id }));
  const [editing, setEditing] = useState(false);
  const [editingStops, setEditingStops] = useState(false);

  if (route.error && !route.data) {
    return (
      <>
        <PageHead eyebrow={t("transport.routes.title")} title={t("transport.route.title")} />
        <ErrorState error={route.error} onRetry={route.reload} />
      </>
    );
  }
  if (!route.data) {
    return (
      <>
        <PageHead eyebrow={t("transport.routes.title")} title={t("common.loading")} />
        <LoadingRows rows={5} />
      </>
    );
  }
  const r = route.data;
  return (
    <>
      <PageHead
        eyebrow={t("transport.routes.title")}
        title={r.name}
        actions={
          <>
            <Link href="/app/transport" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("transport.route.back")}
            </Link>
            {canManage ? (
              <button type="button" className="btn" onClick={() => setEditing(true)} data-testid="route-edit">
                <Pencil size={16} aria-hidden="true" />
                {t("transport.route.edit")}
              </button>
            ) : null}
          </>
        }
      />
      <TransportNav />

      <section className="card flex flex-col gap-3" aria-labelledby="route-facts" data-testid="route-facts">
        <div className="card-head">
          <h2 id="route-facts">{t("transport.route.facts")}</h2>
          {r.active ? <Pill tone="good">{t("transport.route.active")}</Pill> : <Pill>{t("transport.routes.inactive")}</Pill>}
        </div>
        <dl className="kv">
          <dt>{t("transport.col.vehicle")}</dt>
          <dd>
            {r.vehicle ? (
              <>
                <span className="mono">{r.vehicle.registrationNo}</span> · {plural(t, "transport.vehicles.seats", r.vehicle.seats)}
              </>
            ) : (
              "—"
            )}
          </dd>
          <dt>{t("transport.crewKind.DRIVER")}</dt>
          <dd>{r.driver?.name ?? "—"}</dd>
          <dt>{t("transport.crewKind.ATTENDANT")}</dt>
          <dd>{r.attendant?.name ?? "—"}</dd>
          <dt>{t("transport.routes.trips")}</dt>
          <dd>{tripsText(t, r)}</dd>
          <dt>{t("transport.col.seats")}</dt>
          <dd className="num">{r.seats ? `${r.allocated} / ${r.seats}` : r.allocated}</dd>
        </dl>
        <FlagPills flags={r.flags} />
      </section>

      <section className="card flex flex-col gap-3" aria-labelledby="route-stops" data-testid="route-stops">
        <div className="card-head">
          <h2 id="route-stops">{t("transport.route.stops")}</h2>
          {canManage && !editingStops ? (
            <button type="button" className="btn btn-sm" onClick={() => setEditingStops(true)} data-testid="stops-edit">
              <Pencil size={16} aria-hidden="true" />
              {t("transport.route.editStops")}
            </button>
          ) : null}
        </div>
        {editingStops ? (
          <StopsEditor
            route={r}
            onCancel={() => setEditingStops(false)}
            onSaved={() => {
              setEditingStops(false);
              toast(t("transport.route.stopsSaved"));
              route.reload();
              riders.reload();
            }}
          />
        ) : r.stops.length === 0 ? (
          <p className="empty">{t("transport.route.noStops")}</p>
        ) : (
          <StopList stops={r.stops} />
        )}
      </section>

      <section className="card flex flex-col gap-3" aria-labelledby="route-riders" data-testid="route-riders">
        <div className="card-head">
          <h2 id="route-riders">{t("transport.route.students")}</h2>
          {canManage ? (
            <Link className="btn btn-sm" href={`/app/transport/allocations?routeId=${encodeURIComponent(r.id)}`}>
              <UserPlus size={16} aria-hidden="true" />
              {t("transport.route.allocate")}
            </Link>
          ) : null}
        </div>
        {riders.error && !riders.data ? (
          <ErrorState error={riders.error} onRetry={riders.reload} />
        ) : !riders.data ? (
          <LoadingRows rows={3} />
        ) : riders.data.length === 0 ? (
          <p className="text-[13px] text-ink-3">{t("transport.route.noStudents")}</p>
        ) : (
          <ul className="rowcards">
            {riders.data.map((a) => (
              <li key={a.id} className="rowcard items-start" data-testid="route-rider">
                <span className="min-w-0 flex-1">
                  <b className="block">{a.student.name}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {[a.student.classLabel, a.student.admissionNo].filter(Boolean).join(" · ")}
                  </span>
                </span>
                <span className="flex flex-col items-end text-right text-[13px]">
                  <span>{a.stop.name}</span>
                  <span className="text-[12.5px] text-ink-3">
                    {a.current
                      ? t("transport.alloc.since", { date: formatPlainDate(a.startDate, locale) })
                      : t("transport.alloc.from", { date: formatPlainDate(a.startDate, locale) })}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {editing ? (
        <RouteDialog
          route={r}
          onClose={() => setEditing(false)}
          onSaved={() => {
            setEditing(false);
            toast(t("transport.route.saved"));
            route.reload();
          }}
        />
      ) : null}
    </>
  );
}

function StopList({ stops }: { stops: TransportStop[] }) {
  const { t } = useI18n();
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table">
          <thead>
            <tr>
              <th scope="col" className="r">
                #
              </th>
              <th scope="col">{t("transport.col.stop")}</th>
              <th scope="col">{t("transport.stop.pickup")}</th>
              <th scope="col">{t("transport.stop.drop")}</th>
              <th scope="col">{t("transport.stop.zone")}</th>
              <th scope="col" className="r">
                {t("transport.col.students")}
              </th>
            </tr>
          </thead>
          <tbody>
            {stops.map((stop) => (
              <tr key={stop.id} data-testid="stop-row">
                <td className="r num">{stop.seq}</td>
                <td>
                  <b className="block">{stop.name}</b>
                  {stop.landmark ? <span className="block text-[12.5px] text-ink-3">{stop.landmark}</span> : null}
                  {stop.latitude === null ? <span className="block text-[12px] text-ink-3">{t("transport.stop.noLocation")}</span> : null}
                </td>
                <td className="num">{timeOfDay(stop.pickupTime) || "—"}</td>
                <td className="num">{timeOfDay(stop.dropTime) || "—"}</td>
                <td>
                  {stop.feeZone ? (
                    <>
                      <span className="block">{stop.feeZone.name}</span>
                      <span className="block text-[12.5px] text-ink-3">{t("transport.zone.perYear", { amount: formatPaise(stop.feeZone.annualFeePaise) })}</span>
                    </>
                  ) : (
                    "—"
                  )}
                </td>
                <td className="r num">{stop.students}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ol className="rowcards md:hidden">
        {stops.map((stop) => (
          <li key={stop.id} className="rowcard items-start" data-testid="stop-card">
            <span className="num w-6 flex-none text-ink-3">{stop.seq}</span>
            <span className="min-w-0 flex-1">
              <b className="block">{stop.name}</b>
              {stop.landmark ? <span className="block text-[12.5px] text-ink-3">{stop.landmark}</span> : null}
              <span className="block text-[12.5px] text-ink-2">
                {[stopTimes(stop.pickupTime, stop.dropTime), stop.feeZone?.name].filter(Boolean).join(" · ")}
              </span>
            </span>
            <span className="num text-[13px]">{plural(t, "transport.stop.students", stop.students)}</span>
          </li>
        ))}
      </ol>
    </>
  );
}

/* ------------------------------------------------------------------ the stops editor */

export type StopRow = {
  key: string;
  id: string | null;
  name: string;
  landmark: string;
  pickupTime: string;
  dropTime: string;
  latitude: string;
  longitude: string;
  feeZoneId: string;
  students: number;
};

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;
const DECIMAL = /^-?\d{1,3}(\.\d{1,7})?$/;

/** Field problems of each stop row ("stops[0].name" style, as the API names them). Pure and exported for tests. */
export function validateStops(rows: StopRow[]): Record<string, MessageKey> {
  const problems: Record<string, MessageKey> = {};
  rows.forEach((row, i) => {
    const at = (field: string) => `stops[${i}].${field}`;
    if (!row.name.trim()) problems[at("name")] = "validation.required";
    else if (row.name.trim().length > 100) problems[at("name")] = "validation.tooLong";
    if (row.landmark.trim().length > 150) problems[at("landmark")] = "validation.tooLong";
    if (row.pickupTime && !TIME.test(row.pickupTime)) problems[at("pickupTime")] = "transport.v.time";
    if (row.dropTime && !TIME.test(row.dropTime)) problems[at("dropTime")] = "transport.v.time";
    const lat = row.latitude.trim();
    const lng = row.longitude.trim();
    if (lat || lng) {
      if (!DECIMAL.test(lat) || Math.abs(Number(lat)) > 90) problems[at("latitude")] = "transport.v.latitude";
      if (!DECIMAL.test(lng) || Math.abs(Number(lng)) > 180) problems[at("longitude")] = "transport.v.longitude";
    }
  });
  return problems;
}

/** The request body for the rows, in their order. Pure and exported for tests. */
export function stopRequests(rows: StopRow[]): StopRequest[] {
  return rows.map((row) => ({
    id: row.id,
    name: row.name.trim(),
    landmark: row.landmark.trim() || null,
    pickupTime: row.pickupTime || null,
    dropTime: row.dropTime || null,
    latitude: row.latitude.trim() ? Number(row.latitude.trim()) : null,
    longitude: row.longitude.trim() ? Number(row.longitude.trim()) : null,
    feeZoneId: row.feeZoneId || null,
  }));
}

let nextKey = 0;
const newKey = () => `new-${++nextKey}`;

function toRow(stop: TransportStop): StopRow {
  return {
    key: stop.id,
    id: stop.id,
    name: stop.name,
    landmark: stop.landmark ?? "",
    pickupTime: timeOfDay(stop.pickupTime),
    dropTime: timeOfDay(stop.dropTime),
    latitude: stop.latitude === null ? "" : String(stop.latitude),
    longitude: stop.longitude === null ? "" : String(stop.longitude),
    feeZoneId: stop.feeZone?.id ?? "",
    students: stop.students,
  };
}

function StopsEditor({ route, onCancel, onSaved }: { route: RouteDetail; onCancel: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const zones = useApiData("transport:zones", transportApi.zones);
  const [rows, setRows] = useState<StopRow[]>(() => route.stops.map(toRow));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const update = (key: string, patch: Partial<StopRow>) =>
    setRows((prev) => prev.map((row) => (row.key === key ? { ...row, ...patch } : row)));
  const move = (index: number, by: number) =>
    setRows((prev) => {
      const next = [...prev];
      const [row] = next.splice(index, 1);
      next.splice(index + by, 0, row);
      return next;
    });
  const add = () =>
    setRows((prev) => [
      ...prev,
      { key: newKey(), id: null, name: "", landmark: "", pickupTime: "", dropTime: "", latitude: "", longitude: "", feeZoneId: "", students: 0 },
    ]);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems = validateStops(rows);
    if (Object.keys(problems).length > 0) {
      setErrors(Object.fromEntries(Object.entries(problems).map(([field, key]) => [field, t(key)])));
      setFormError(t("validation.fixErrors"));
      return;
    }
    setSaving(true);
    setErrors({});
    setFormError(null);
    try {
      await transportApi.saveStops(route.id, stopRequests(rows));
      onSaved();
    } catch (caught) {
      const error = toApiError(caught);
      setErrors(error.errors ?? {});
      setFormError(error.errors && error.status !== 409 ? t("validation.fixErrors") : errorMessage(error, t));
    } finally {
      setSaving(false);
    }
  };

  const zoneOptions = (zones.data ?? []) as FeeZone[];
  return (
    <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate data-testid="stops-form">
      <p className="text-[13px] text-ink-2">{t("transport.route.stopsIntro")}</p>
      <FormAlert message={formError} />
      {errors.stops ? <FormAlert message={errors.stops} /> : null}
      {rows.length === 0 ? <p className="text-[13px] text-ink-3">{t("transport.route.noStops")}</p> : null}
      <ol className="flex flex-col gap-3">
        {rows.map((row, i) => {
          const at = (field: string) => errors[`stops[${i}].${field}`];
          return (
            <li key={row.key} className="fieldset" data-testid="stop-edit-row">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <b>{t("transport.route.stopN", { n: i + 1 })}</b>
                <span className="flex gap-1">
                  <button
                    type="button"
                    className="iconbtn"
                    onClick={() => move(i, -1)}
                    disabled={i === 0}
                    aria-label={t("transport.route.moveUp", { name: row.name || String(i + 1) })}
                  >
                    <ArrowUp size={16} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className="iconbtn"
                    onClick={() => move(i, 1)}
                    disabled={i === rows.length - 1}
                    aria-label={t("transport.route.moveDown", { name: row.name || String(i + 1) })}
                  >
                    <ArrowDown size={16} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className="iconbtn"
                    onClick={() => setRows((prev) => prev.filter((r) => r.key !== row.key))}
                    disabled={row.students > 0}
                    title={row.students > 0 ? t("transport.route.stopInUse") : undefined}
                    aria-label={t("transport.route.removeStop", { name: row.name || String(i + 1) })}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </span>
              </div>
              <div className="grid2">
                <TextField
                  label={t("transport.stop.name")}
                  name={`stops[${i}].name`}
                  value={row.name}
                  onChange={(e) => update(row.key, { name: e.target.value })}
                  error={at("name")}
                  maxLength={100}
                  autoComplete="off"
                />
                <TextField
                  label={t("transport.stop.landmark")}
                  name={`stops[${i}].landmark`}
                  value={row.landmark}
                  onChange={(e) => update(row.key, { landmark: e.target.value })}
                  error={at("landmark")}
                  maxLength={150}
                  autoComplete="off"
                />
              </div>
              <div className="grid2">
                <TextField
                  label={t("transport.stop.pickup")}
                  name={`stops[${i}].pickupTime`}
                  type="time"
                  value={row.pickupTime}
                  onChange={(e) => update(row.key, { pickupTime: e.target.value })}
                  error={at("pickupTime")}
                />
                <TextField
                  label={t("transport.stop.drop")}
                  name={`stops[${i}].dropTime`}
                  type="time"
                  value={row.dropTime}
                  onChange={(e) => update(row.key, { dropTime: e.target.value })}
                  error={at("dropTime")}
                />
              </div>
              <div className="grid2">
                <TextField
                  label={t("transport.stop.latitude")}
                  name={`stops[${i}].latitude`}
                  inputMode="decimal"
                  value={row.latitude}
                  onChange={(e) => update(row.key, { latitude: e.target.value })}
                  error={at("latitude")}
                  maxLength={12}
                  autoComplete="off"
                />
                <TextField
                  label={t("transport.stop.longitude")}
                  name={`stops[${i}].longitude`}
                  inputMode="decimal"
                  value={row.longitude}
                  onChange={(e) => update(row.key, { longitude: e.target.value })}
                  error={at("longitude")}
                  maxLength={12}
                  autoComplete="off"
                />
              </div>
              <SelectField
                label={t("transport.stop.zone")}
                name={`stops[${i}].feeZoneId`}
                value={row.feeZoneId}
                onChange={(e) => update(row.key, { feeZoneId: e.target.value })}
                error={at("feeZoneId")}
                options={[
                  { value: "", label: t("transport.stop.noZone") },
                  ...zoneOptions.map((zone) => ({
                    value: zone.id,
                    label: `${zone.name} · ${t("transport.zone.perYear", { amount: formatPaise(zone.annualFeePaise) })}`,
                  })),
                ]}
              />
              {row.students > 0 ? <p className="field-hint">{plural(t, "transport.stop.students", row.students)}</p> : null}
            </li>
          );
        })}
      </ol>
      <p className="text-[12.5px] text-ink-3">{t("transport.route.locationHint")}</p>
      <div className="flex flex-wrap justify-between gap-2">
        <button type="button" className="btn" onClick={add} data-testid="stop-add">
          <Plus size={16} aria-hidden="true" />
          {t("transport.route.addStop")}
        </button>
        <span className="flex gap-2">
          <button type="button" className="btn" onClick={onCancel}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? t("common.saving") : t("transport.route.saveStops")}
          </button>
        </span>
      </div>
    </form>
  );
}
