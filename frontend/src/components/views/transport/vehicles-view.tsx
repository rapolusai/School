"use client";

import { KeyRound, Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { REGISTRATION_PATTERN, transportApi } from "@/lib/transport-api";
import { VEHICLE_STATUSES, VEHICLE_TYPES, type DeviceToken, type Vehicle, type VehicleRequest, type VehicleStatus, type VehicleType } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";
import {
  dateOrDash,
  DocumentStatePill,
  documentLabel,
  FlagPills,
  TransportNav,
  useCanManageTransport,
  vehicleStatusLabel,
  vehicleTypeLabel,
} from "./transport-shared";

/** /app/transport/vehicles: the school's vehicles, their papers (warned 30 days before expiry) and GPS devices. */
export function VehiclesView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canManage = useCanManageTransport();
  const vehicles = useApiData("transport:vehicles", transportApi.vehicles);
  const [editing, setEditing] = useState<Vehicle | "new" | null>(null);
  const [issuing, setIssuing] = useState<Vehicle | null>(null);
  const [revoking, setRevoking] = useState<Vehicle | null>(null);
  const list = vehicles.data ?? [];

  return (
    <>
      <PageHead
        eyebrow={vehicles.data ? plural(t, "transport.vehicles.eyebrow", list.length) : t("nav.transport")}
        title={t("transport.vehicles.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="vehicle-new">
              <Plus size={18} aria-hidden="true" />
              {t("transport.vehicles.new")}
            </button>
          ) : null
        }
      />
      <TransportNav />
      {vehicles.error && !vehicles.data ? (
        <ErrorState error={vehicles.error} onRetry={vehicles.reload} />
      ) : !vehicles.data ? (
        <LoadingRows rows={4} />
      ) : list.length === 0 ? (
        <section className="card">
          <p className="empty">{t("transport.vehicles.empty")}</p>
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2" data-testid="vehicles">
          {list.map((v) => (
            <section key={v.id} className="card flex flex-col gap-3" aria-labelledby={`vehicle-${v.id}`} data-testid="vehicle-card">
              <div className="card-head">
                <h2 id={`vehicle-${v.id}`} className="mono">
                  {v.registrationNo}
                </h2>
                <span className="flex flex-wrap items-center gap-1.5">
                  {v.status !== "ACTIVE" ? <Pill tone={v.status === "RETIRED" ? "neutral" : "warn"}>{vehicleStatusLabel(t, v.status)}</Pill> : null}
                  {canManage ? (
                    <button type="button" className="btn btn-sm" onClick={() => setEditing(v)} aria-label={t("transport.vehicles.editTitle", { reg: v.registrationNo })}>
                      <Pencil size={16} aria-hidden="true" />
                      {t("common.edit")}
                    </button>
                  ) : null}
                </span>
              </div>
              <p className="text-[13px] text-ink-2">
                {vehicleTypeLabel(t, v.type)} · {plural(t, "transport.vehicles.seats", v.seats)}
                {v.routes.length > 0 ? ` · ${v.routes.map((r) => r.name).join(", ")}` : ` · ${t("transport.vehicles.noRoute")}`}
              </p>
              <FlagPills flags={v.flags} />
              <ul className="rowcards" aria-label={t("transport.vehicles.documents")}>
                {v.documents.map((doc) => (
                  <li key={doc.kind} className="rowcard" data-testid={`vehicle-doc-${doc.kind}`}>
                    <span className="min-w-0 flex-1">
                      <span className="block text-[13.5px]">{documentLabel(t, doc.kind)}</span>
                      <span className="block text-[12.5px] text-ink-3">{dateOrDash(doc.expiresOn, locale)}</span>
                    </span>
                    <DocumentStatePill state={doc.state} daysLeft={doc.daysLeft} />
                  </li>
                ))}
              </ul>
              <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line pt-3 text-[13px]">
                <span className="min-w-0 text-ink-2">
                  {v.deviceToken
                    ? t("transport.vehicles.deviceOn", { hint: v.deviceToken.hint, date: formatDate(v.deviceToken.issuedAt, locale) })
                    : t("transport.vehicles.deviceOff")}
                  {v.gpsDeviceId ? <span className="block text-[12.5px] text-ink-3">{t("transport.vehicles.deviceId", { id: v.gpsDeviceId })}</span> : null}
                  {v.lastPositionAt ? (
                    <span className="block text-[12.5px] text-ink-3">{t("transport.vehicles.lastPosition", { date: formatDate(v.lastPositionAt, locale) })}</span>
                  ) : null}
                </span>
                {canManage ? (
                  <span className="flex gap-1.5">
                    <button type="button" className="btn btn-sm" onClick={() => setIssuing(v)}>
                      <KeyRound size={16} aria-hidden="true" />
                      {v.deviceToken ? t("transport.vehicles.newToken") : t("transport.vehicles.issueToken")}
                    </button>
                    {v.deviceToken ? (
                      <button type="button" className="btn btn-sm" onClick={() => setRevoking(v)}>
                        {t("transport.vehicles.revoke")}
                      </button>
                    ) : null}
                  </span>
                ) : null}
              </div>
            </section>
          ))}
        </div>
      )}

      {editing ? (
        <VehicleDialog
          vehicle={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            toast(t("transport.vehicles.saved", { reg: saved.registrationNo }));
            vehicles.reload();
          }}
        />
      ) : null}
      {issuing ? (
        <TokenDialog
          vehicle={issuing}
          onClose={() => {
            setIssuing(null);
            vehicles.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={revoking !== null}
        title={t("transport.vehicles.revokeTitle", { reg: revoking?.registrationNo ?? "" })}
        body={t("transport.vehicles.revokeBody")}
        confirmLabel={t("transport.vehicles.revoke")}
        onClose={() => setRevoking(null)}
        onConfirm={async () => {
          if (!revoking) return;
          await transportApi.revokeDeviceToken(revoking.id);
          setRevoking(null);
          toast(t("transport.vehicles.revoked"));
          vehicles.reload();
        }}
      />
    </>
  );
}

type VehicleValues = {
  registrationNo: string;
  type: VehicleType;
  seats: string;
  gpsDeviceId: string;
  insuranceExpiry: string;
  fitnessExpiry: string;
  permitExpiry: string;
  pollutionExpiry: string;
  status: VehicleStatus;
};

const DATE_FIELDS = ["insuranceExpiry", "fitnessExpiry", "permitExpiry", "pollutionExpiry"] as const;

/** The same checks the API makes on a vehicle. Pure and exported for tests. */
export function validateVehicle(values: VehicleValues): Problems {
  const problems: Problems = {};
  const reg = values.registrationNo.trim().replace(/\s+/g, " ");
  if (!reg) problems.registrationNo = "validation.required";
  else if (!REGISTRATION_PATTERN.test(reg)) problems.registrationNo = "transport.v.registration";
  const seats = values.seats.trim();
  if (!/^\d+$/.test(seats) || Number(seats) < 1 || Number(seats) > 100) problems.seats = "transport.v.seats";
  if (values.gpsDeviceId.trim().length > 60) problems.gpsDeviceId = "validation.tooLong";
  for (const field of DATE_FIELDS) {
    if (values[field] && !isPlainDate(values[field])) problems[field] = "validation.date";
  }
  return problems;
}

function VehicleDialog({ vehicle, onClose, onSaved }: { vehicle: Vehicle | null; onClose: () => void; onSaved: (v: Vehicle) => void }) {
  const { t } = useI18n();
  const form = useForm<VehicleValues>({
    registrationNo: vehicle?.registrationNo ?? "",
    type: vehicle?.type ?? "BUS",
    seats: vehicle ? String(vehicle.seats) : "",
    gpsDeviceId: vehicle?.gpsDeviceId ?? "",
    insuranceExpiry: vehicle?.insuranceExpiry ?? "",
    fitnessExpiry: vehicle?.fitnessExpiry ?? "",
    permitExpiry: vehicle?.permitExpiry ?? "",
    pollutionExpiry: vehicle?.pollutionExpiry ?? "",
    status: vehicle?.status ?? "ACTIVE",
  });
  const { values, set, errors } = form;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateVehicle(values), t, event.currentTarget)) return;
    const body: VehicleRequest = {
      registrationNo: values.registrationNo.trim().replace(/\s+/g, " ").toUpperCase(),
      type: values.type,
      seats: Number(values.seats.trim()),
      gpsDeviceId: values.gpsDeviceId.trim() || null,
      insuranceExpiry: values.insuranceExpiry || null,
      fitnessExpiry: values.fitnessExpiry || null,
      permitExpiry: values.permitExpiry || null,
      pollutionExpiry: values.pollutionExpiry || null,
      status: values.status,
    };
    const result: { vehicle?: Vehicle } = {};
    const ok = await form.submit(t, async () => {
      result.vehicle = vehicle ? await transportApi.updateVehicle(vehicle.id, body) : await transportApi.createVehicle(body);
    });
    if (ok && result.vehicle) onSaved(result.vehicle);
  };

  const date = (field: (typeof DATE_FIELDS)[number], kind: "INSURANCE" | "FITNESS" | "PERMIT" | "POLLUTION") => (
    <TextField
      label={t("transport.vehicles.expiry", { document: documentLabel(t, kind) })}
      name={field}
      type="date"
      value={values[field]}
      onChange={(e) => set(field, e.target.value)}
      error={errors[field]}
    />
  );

  return (
    <Dialog
      open
      onClose={onClose}
      title={vehicle ? t("transport.vehicles.editTitle", { reg: vehicle.registrationNo }) : t("transport.vehicles.new")}
      description={t("transport.vehicles.dialogIntro")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="vehicle-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("transport.vehicles.registration")}
            name="registrationNo"
            value={values.registrationNo}
            onChange={(e) => set("registrationNo", e.target.value.toUpperCase())}
            error={errors.registrationNo}
            hint={t("transport.vehicles.registrationHint")}
            maxLength={20}
            autoComplete="off"
          />
          <TextField
            label={t("transport.vehicles.seatsLabel")}
            name="seats"
            inputMode="numeric"
            value={values.seats}
            onChange={(e) => set("seats", e.target.value)}
            error={errors.seats}
            maxLength={3}
          />
        </div>
        <div className="grid2">
          <SelectField
            label={t("transport.vehicles.type")}
            name="type"
            value={values.type}
            onChange={(e) => set("type", e.target.value as VehicleType)}
            error={errors.type}
            options={VEHICLE_TYPES.map((type) => ({ value: type, label: vehicleTypeLabel(t, type) }))}
          />
          <SelectField
            label={t("transport.vehicles.status")}
            name="status"
            value={values.status}
            onChange={(e) => set("status", e.target.value as VehicleStatus)}
            error={errors.status}
            options={VEHICLE_STATUSES.map((status) => ({ value: status, label: vehicleStatusLabel(t, status) }))}
          />
        </div>
        <div className="grid2">
          {date("fitnessExpiry", "FITNESS")}
          {date("insuranceExpiry", "INSURANCE")}
        </div>
        <div className="grid2">
          {date("permitExpiry", "PERMIT")}
          {date("pollutionExpiry", "POLLUTION")}
        </div>
        <TextField
          label={t("transport.vehicles.gpsDevice")}
          name="gpsDeviceId"
          value={values.gpsDeviceId}
          onChange={(e) => set("gpsDeviceId", e.target.value)}
          error={errors.gpsDeviceId}
          hint={t("transport.vehicles.gpsDeviceHint")}
          maxLength={60}
          autoComplete="off"
        />
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

/** Issues a new device token (replacing any earlier one) and shows it once. */
function TokenDialog({ vehicle, onClose }: { vehicle: Vehicle; onClose: () => void }) {
  const { t } = useI18n();
  const [token, setToken] = useState<DeviceToken | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const issue = async () => {
    setBusy(true);
    setError(null);
    try {
      setToken(await transportApi.issueDeviceToken(vehicle.id));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open onClose={onClose} title={t("transport.vehicles.tokenTitle", { reg: vehicle.registrationNo })} closeLabel={t("common.close")}>
      <div className="flex flex-col gap-3.5" data-testid="token-dialog">
        <FormAlert message={error} />
        {token ? (
          <>
            <p className="alert alert-warn">{t("transport.vehicles.tokenOnce")}</p>
            <dl className="kv text-[13px]">
              <dt>{t("transport.vehicles.tokenLabel")}</dt>
              <dd className="mono break-all" data-testid="device-token">
                {token.token}
              </dd>
              <dt>{t("transport.vehicles.tokenEndpoint")}</dt>
              <dd className="mono break-all">POST {token.endpoint}</dd>
              <dt>{t("transport.vehicles.tokenHeader")}</dt>
              <dd className="mono break-all">{token.header}</dd>
            </dl>
            <p className="text-[12.5px] text-ink-3">{t("transport.vehicles.tokenBody")}</p>
            <div className="flex justify-end">
              <button type="button" className="btn btn-primary" onClick={onClose}>
                {t("common.done")}
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="text-ink-2">{vehicle.deviceToken ? t("transport.vehicles.tokenReplace") : t("transport.vehicles.tokenIntro")}</p>
            <div className="flex justify-end gap-2">
              <button type="button" className="btn" onClick={onClose}>
                {t("common.cancel")}
              </button>
              <button type="button" className="btn btn-primary" onClick={issue} disabled={busy}>
                {busy ? t("common.working") : t("transport.vehicles.issueToken")}
              </button>
            </div>
          </>
        )}
      </div>
    </Dialog>
  );
}
