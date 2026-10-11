"use client";

import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, parseRupees, rupeesInput } from "@/lib/format";
import { plural, useI18n, type MessageKey } from "@/lib/i18n";
import { transportApi } from "@/lib/transport-api";
import type { FeeZone, TransportSettings } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { TransportNav, useCanManageTransport } from "./transport-shared";

/** /app/transport/settings: which bus messages parents get, and the fee zones (distance slabs) with their fees. */
export function TransportSettingsView() {
  const { t } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManageTransport();
  const settings = useApiData("transport:settings", transportApi.settings);
  const zones = useApiData("transport:zones", transportApi.zones);
  const [editing, setEditing] = useState<FeeZone | "new" | null>(null);
  const [deleting, setDeleting] = useState<FeeZone | null>(null);

  return (
    <>
      <PageHead eyebrow={t("nav.transport")} title={t("transport.settings.title")} />
      <TransportNav />
      <section className="card flex flex-col gap-3.5" aria-labelledby="transport-alerts">
        <h2 id="transport-alerts">{t("transport.settings.alerts")}</h2>
        <p className="text-[13px] text-ink-2">{t("transport.settings.alertsIntro")}</p>
        {settings.error && !settings.data ? (
          <ErrorState error={settings.error} onRetry={settings.reload} />
        ) : !settings.data ? (
          <LoadingRows rows={4} />
        ) : (
          <AlertsForm key={JSON.stringify(settings.data)} settings={settings.data} canManage={canManage} onSaved={settings.reload} />
        )}
      </section>

      <section className="card flex flex-col gap-3" aria-labelledby="transport-zones" data-testid="fee-zones">
        <div className="card-head">
          <h2 id="transport-zones">{t("transport.zones.title")}</h2>
          {canManage ? (
            <button type="button" className="btn btn-sm" onClick={() => setEditing("new")} data-testid="zone-new">
              <Plus size={16} aria-hidden="true" />
              {t("transport.zones.new")}
            </button>
          ) : null}
        </div>
        <p className="text-[13px] text-ink-2">{t("transport.zones.intro")}</p>
        {zones.error && !zones.data ? (
          <ErrorState error={zones.error} onRetry={zones.reload} />
        ) : !zones.data ? (
          <LoadingRows rows={3} />
        ) : zones.data.length === 0 ? (
          <p className="empty">{t("transport.zones.empty")}</p>
        ) : (
          <ul className="rowcards">
            {zones.data.map((zone) => (
              <li key={zone.id} className="rowcard flex-wrap" data-testid="zone-row">
                <span className="min-w-0 flex-1">
                  <b className="block">{zone.name}</b>
                  <span className="block text-[12.5px] text-ink-3">
                    {zone.maxKm === null
                      ? t("transport.zones.over", { min: zone.minKm })
                      : t("transport.zones.range", { min: zone.minKm, max: zone.maxKm })}{" "}
                    · {plural(t, "transport.zones.stops", zone.stops)}
                  </span>
                </span>
                <b className="num">{t("transport.zone.perYear", { amount: formatPaise(zone.annualFeePaise) })}</b>
                {canManage ? (
                  <span className="flex gap-1.5">
                    <button type="button" className="iconbtn" onClick={() => setEditing(zone)} aria-label={t("transport.zones.editTitle", { name: zone.name })}>
                      <Pencil size={16} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="iconbtn"
                      onClick={() => setDeleting(zone)}
                      disabled={zone.stops > 0}
                      title={zone.stops > 0 ? t("transport.zones.inUse") : undefined}
                      aria-label={t("transport.zones.deleteTitle", { name: zone.name })}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </button>
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        <p className="text-[12.5px] text-ink-3">{t("transport.zones.feeNote")}</p>
      </section>

      {editing ? (
        <ZoneDialog
          zone={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("transport.zones.saved"));
            zones.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting !== null}
        title={t("transport.zones.deleteTitle", { name: deleting?.name ?? "" })}
        body={t("transport.zones.deleteBody")}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          await transportApi.deleteZone(deleting.id);
          setDeleting(null);
          toast(t("common.deleted"));
          zones.reload();
        }}
      />
    </>
  );
}

const ALERTS: { key: keyof TransportSettings; label: MessageKey; hint: MessageKey }[] = [
  { key: "boardedAlerts", label: "transport.settings.boarded", hint: "transport.settings.boardedHint" },
  { key: "droppedAlerts", label: "transport.settings.dropped", hint: "transport.settings.droppedHint" },
  { key: "approachingAlerts", label: "transport.settings.approaching", hint: "transport.settings.approachingHint" },
  { key: "delayAlerts", label: "transport.settings.delayed", hint: "transport.settings.delayedHint" },
];

function AlertsForm({ settings, canManage, onSaved }: { settings: TransportSettings; canManage: boolean; onSaved: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<TransportSettings>(settings);
  const { values, set } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const ok = await form.submit(t, async () => {
      await transportApi.saveSettings(values);
    });
    if (ok) {
      toast(t("transport.settings.saved"));
      onSaved();
    }
  };
  return (
    <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate data-testid="transport-alerts-form">
      <FormAlert message={form.formError} />
      {ALERTS.map((alert) => (
        <div key={alert.key} className="field">
          <label className="check">
            <input
              type="checkbox"
              name={alert.key}
              checked={values[alert.key]}
              disabled={!canManage}
              onChange={(e) => set(alert.key, e.target.checked)}
            />
            <span>{t(alert.label)}</span>
          </label>
          <p className="field-hint">{t(alert.hint)}</p>
        </div>
      ))}
      <p className="text-[12.5px] text-ink-3">{t("transport.settings.channelNote")}</p>
      {canManage ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}

type ZoneValues = { name: string; minKm: string; maxKm: string; annualFee: string };

const KM = /^\d{1,3}(\.\d)?$/;

/** The same checks the API makes on a fee zone. Pure and exported for tests. */
export function validateZone(values: ZoneValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 60) problems.name = "validation.tooLong";
  if (!KM.test(values.minKm.trim())) problems.minKm = "transport.v.km";
  if (values.maxKm.trim()) {
    if (!KM.test(values.maxKm.trim())) problems.maxKm = "transport.v.km";
    else if (!problems.minKm && Number(values.maxKm) <= Number(values.minKm)) problems.maxKm = "transport.v.maxKm";
  }
  const fee = parseRupees(values.annualFee);
  if (fee === null || fee > 100_000_000) problems.annualFeePaise = "transport.v.fee";
  return problems;
}

function ZoneDialog({ zone, onClose, onSaved }: { zone: FeeZone | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<ZoneValues>({
    name: zone?.name ?? "",
    minKm: zone ? String(zone.minKm) : "0",
    maxKm: zone?.maxKm === null || zone?.maxKm === undefined ? "" : String(zone.maxKm),
    annualFee: zone ? rupeesInput(zone.annualFeePaise) : "",
  });
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateZone(values), t, event.currentTarget)) return;
    const body = {
      name: values.name.trim(),
      minKm: Number(values.minKm.trim()),
      maxKm: values.maxKm.trim() ? Number(values.maxKm.trim()) : null,
      annualFeePaise: parseRupees(values.annualFee) ?? 0,
    };
    const ok = await form.submit(t, async () => {
      if (zone) await transportApi.updateZone(zone.id, body);
      else await transportApi.createZone(body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={zone ? t("transport.zones.editTitle", { name: zone.name }) : t("transport.zones.new")}
      description={t("transport.zones.dialogIntro")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="zone-form">
        <FormAlert message={form.formError} />
        <TextField
          label={t("transport.zones.name")}
          name="name"
          value={values.name}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
          maxLength={60}
          autoComplete="off"
        />
        <div className="grid2">
          <TextField
            label={t("transport.zones.minKm")}
            name="minKm"
            inputMode="decimal"
            value={values.minKm}
            onChange={(e) => set("minKm", e.target.value)}
            error={errors.minKm}
            maxLength={5}
          />
          <TextField
            label={t("transport.zones.maxKm")}
            name="maxKm"
            inputMode="decimal"
            value={values.maxKm}
            onChange={(e) => set("maxKm", e.target.value)}
            error={errors.maxKm}
            hint={t("transport.zones.maxKmHint")}
            maxLength={5}
          />
        </div>
        <TextField
          label={t("transport.zones.fee")}
          name="annualFeePaise"
          inputMode="decimal"
          value={values.annualFee}
          onChange={(e) => set("annualFee", e.target.value)}
          error={errors.annualFeePaise}
          hint={t("transport.zones.feeHint")}
          maxLength={12}
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
