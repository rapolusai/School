"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { plural, useI18n } from "@/lib/i18n";
import { timeOfDay, transportApi } from "@/lib/transport-api";
import type { RouteDetail, RouteRequest, RouteSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { FlagPills, TransportNav, useCanManageTransport } from "./transport-shared";

/** "12 / 40" seats taken, or "12" when the route has no vehicle. */
function seatsText(route: Pick<RouteSummary, "allocated" | "seats">): string {
  return route.seats ? `${route.allocated} / ${route.seats}` : String(route.allocated);
}

function SeatMeter({ route }: { route: RouteSummary }) {
  if (!route.seats) return null;
  const share = Math.min(100, Math.round((route.allocated / route.seats) * 100));
  return (
    <span className={`meter mt-1 w-24 ${route.allocated >= route.seats ? "meter-full" : ""}`} aria-hidden="true">
      <span style={{ width: `${share}%` }} />
    </span>
  );
}

/** /app/transport: every route with its vehicle, crew, stops, seats taken and warnings. */
export function RoutesView() {
  const { t } = useI18n();
  const router = useRouter();
  const { toast } = useToast();
  const canManage = useCanManageTransport();
  const routes = useApiData("transport:routes", transportApi.routes);
  const [creating, setCreating] = useState(false);
  const list = routes.data ?? [];

  return (
    <>
      <PageHead
        eyebrow={routes.data ? plural(t, "transport.routes.eyebrow", list.length) : t("nav.transport")}
        title={t("transport.routes.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} data-testid="route-new">
              <Plus size={18} aria-hidden="true" />
              {t("transport.routes.new")}
            </button>
          ) : null
        }
      />
      <TransportNav />
      <section className="card flex flex-col gap-3" aria-label={t("transport.routes.title")} data-testid="routes">
        {routes.error && !routes.data ? (
          <ErrorState error={routes.error} onRetry={routes.reload} />
        ) : !routes.data ? (
          <LoadingRows rows={4} />
        ) : list.length === 0 ? (
          <div className="empty flex flex-col items-center gap-2">
            <p>{t("transport.routes.empty")}</p>
            {canManage ? <p className="text-[13px]">{t("transport.routes.emptyHint")}</p> : null}
          </div>
        ) : (
          <>
            <div className="table-wrap hidden md:block">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">{t("transport.col.route")}</th>
                    <th scope="col">{t("transport.col.vehicle")}</th>
                    <th scope="col">{t("transport.col.crew")}</th>
                    <th scope="col" className="r">
                      {t("transport.col.stops")}
                    </th>
                    <th scope="col" className="r">
                      {t("transport.col.seats")}
                    </th>
                    <th scope="col">{t("transport.col.warnings")}</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((route) => (
                    <tr key={route.id}>
                      <td>
                        <Link className="row-title" href={`/app/transport/routes/${route.id}`}>
                          <b>{route.name}</b>
                        </Link>
                        {!route.active ? (
                          <span className="ml-2">
                            <Pill>{t("transport.routes.inactive")}</Pill>
                          </span>
                        ) : null}
                        <span className="block text-[12.5px] text-ink-3">{tripsText(t, route)}</span>
                      </td>
                      <td>
                        {route.vehicle ? <span className="mono">{route.vehicle.registrationNo}</span> : <span className="text-ink-3">—</span>}
                      </td>
                      <td>
                        <span className="block">{route.driver?.name ?? "—"}</span>
                        <span className="block text-[12.5px] text-ink-3">{route.attendant?.name ?? "—"}</span>
                      </td>
                      <td className="r num">
                        {route.stopCount}
                        {route.firstPickup ? (
                          <span className="block text-[12.5px] text-ink-3">{t("transport.routes.from", { time: timeOfDay(route.firstPickup) })}</span>
                        ) : null}
                      </td>
                      <td className="r num">
                        {seatsText(route)}
                        <SeatMeter route={route} />
                      </td>
                      <td>
                        <FlagPills flags={route.flags} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden">
              {list.map((route) => (
                <li key={route.id}>
                  <Link href={`/app/transport/routes/${route.id}`} className="rowcard items-start">
                    <span className="min-w-0 flex-1">
                      <b className="block">
                        {route.name}
                        {!route.active ? <span className="ml-2 text-[12px] font-normal text-ink-3">{t("transport.routes.inactive")}</span> : null}
                      </b>
                      <span className="block text-[13px] text-ink-2">
                        {[route.vehicle?.registrationNo, plural(t, "transport.routes.stops", route.stopCount)].filter(Boolean).join(" · ")}
                      </span>
                      <span className="block text-[12.5px] text-ink-3">
                        {[route.driver?.name, route.attendant?.name].filter(Boolean).join(" · ") || t("transport.routes.noCrew")}
                      </span>
                      <span className="mt-1 block">
                        <FlagPills flags={route.flags} />
                      </span>
                    </span>
                    <span className="num flex flex-col items-end text-[13px]">
                      {seatsText(route)}
                      <SeatMeter route={route} />
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
      {creating ? (
        <RouteDialog
          route={null}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false);
            toast(t("transport.routes.created", { name: saved.name }));
            router.push(`/app/transport/routes/${saved.id}`);
          }}
        />
      ) : null}
    </>
  );
}

/** "Morning and afternoon", "Morning only" or "No trips". */
export function tripsText(t: ReturnType<typeof useI18n>["t"], route: Pick<RouteSummary, "runsMorning" | "runsAfternoon">): string {
  if (route.runsMorning && route.runsAfternoon) return t("transport.routes.bothTrips");
  if (route.runsMorning) return t("transport.routes.morningOnly");
  if (route.runsAfternoon) return t("transport.routes.afternoonOnly");
  return t("transport.routes.noTrips");
}

type RouteValues = {
  name: string;
  vehicleId: string;
  driverId: string;
  attendantId: string;
  runsMorning: boolean;
  runsAfternoon: boolean;
  active: boolean;
};

/** The same checks the API makes on a route. Pure and exported for tests. */
export function validateRoute(values: RouteValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 60) problems.name = "validation.tooLong";
  if (!values.runsMorning && !values.runsAfternoon) problems.runsMorning = "transport.v.trips";
  return problems;
}

/** Creates a route, or edits its name, vehicle, crew, trips and whether it runs. */
export function RouteDialog({
  route,
  onClose,
  onSaved,
}: {
  route: RouteSummary | null;
  onClose: () => void;
  onSaved: (route: RouteDetail) => void;
}) {
  const { t } = useI18n();
  const vehicles = useApiData("transport:vehicles", transportApi.vehicles);
  const crew = useApiData("transport:crew", transportApi.crew);
  const form = useForm<RouteValues>({
    name: route?.name ?? "",
    vehicleId: route?.vehicle?.id ?? "",
    driverId: route?.driver?.id ?? "",
    attendantId: route?.attendant?.id ?? "",
    runsMorning: route?.runsMorning ?? true,
    runsAfternoon: route?.runsAfternoon ?? true,
    active: route?.active ?? true,
  });
  const { values, set, errors } = form;
  const none = { value: "", label: t("transport.routes.none") };
  const vehicleOptions = [
    none,
    ...(vehicles.data ?? [])
      .filter((v) => v.status !== "RETIRED" || v.id === values.vehicleId)
      .map((v) => ({ value: v.id, label: `${v.registrationNo} · ${plural(t, "transport.vehicles.seats", v.seats)}` })),
  ];
  const crewOptions = (kind: "DRIVER" | "ATTENDANT", current: string) => [
    none,
    ...(crew.data ?? [])
      .filter((c) => (c.kind === kind && c.staffActive) || c.id === current)
      .map((c) => ({ value: c.id, label: c.name })),
  ];

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateRoute(values), t, event.currentTarget)) return;
    const body: RouteRequest = {
      name: values.name.trim(),
      vehicleId: values.vehicleId || null,
      driverId: values.driverId || null,
      attendantId: values.attendantId || null,
      runsMorning: values.runsMorning,
      runsAfternoon: values.runsAfternoon,
      active: values.active,
    };
    const result: { route?: RouteDetail } = {};
    const ok = await form.submit(t, async () => {
      result.route = route ? await transportApi.updateRoute(route.id, body) : await transportApi.createRoute(body);
    });
    if (ok && result.route) onSaved(result.route);
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={route ? t("transport.routes.editTitle", { name: route.name }) : t("transport.routes.new")}
      description={t("transport.routes.dialogIntro")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="route-form">
        <FormAlert message={form.formError} />
        {vehicles.error || crew.error ? <FormAlert message={t("transport.routes.optionsFailed")} /> : null}
        <TextField
          label={t("transport.routes.name")}
          name="name"
          value={values.name}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
          hint={t("transport.routes.nameHint")}
          maxLength={60}
          autoComplete="off"
        />
        <SelectField
          label={t("transport.col.vehicle")}
          name="vehicleId"
          value={values.vehicleId}
          onChange={(e) => set("vehicleId", e.target.value)}
          error={errors.vehicleId}
          options={vehicleOptions}
        />
        <div className="grid2">
          <SelectField
            label={t("transport.crewKind.DRIVER")}
            name="driverId"
            value={values.driverId}
            onChange={(e) => set("driverId", e.target.value)}
            error={errors.driverId}
            options={crewOptions("DRIVER", values.driverId)}
          />
          <SelectField
            label={t("transport.crewKind.ATTENDANT")}
            name="attendantId"
            value={values.attendantId}
            onChange={(e) => set("attendantId", e.target.value)}
            error={errors.attendantId}
            options={crewOptions("ATTENDANT", values.attendantId)}
          />
        </div>
        <fieldset className="fieldset" aria-describedby={errors.runsMorning ? "route-trips-error" : undefined}>
          <legend>{t("transport.routes.trips")}</legend>
          <label className="check">
            <input type="checkbox" name="runsMorning" checked={values.runsMorning} onChange={(e) => set("runsMorning", e.target.checked)} />
            <span>{t("transport.tripKind.MORNING")}</span>
          </label>
          <label className="check">
            <input
              type="checkbox"
              name="runsAfternoon"
              checked={values.runsAfternoon}
              onChange={(e) => set("runsAfternoon", e.target.checked)}
            />
            <span>{t("transport.tripKind.AFTERNOON")}</span>
          </label>
          {errors.runsMorning ? (
            <p id="route-trips-error" className="field-error">
              {errors.runsMorning}
            </p>
          ) : null}
        </fieldset>
        {route ? (
          <div className="field">
            <label className="check">
              <input type="checkbox" name="active" checked={values.active} onChange={(e) => set("active", e.target.checked)} />
              <span>{t("transport.routes.activeLabel")}</span>
            </label>
            <p className="field-hint">{t("transport.routes.activeHint")}</p>
          </div>
        ) : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
