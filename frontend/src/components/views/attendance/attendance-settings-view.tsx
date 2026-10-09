"use client";

import { ArrowLeft, Info } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { notificationsApi } from "@/lib/notifications-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { ALERT_CHANNELS, type NotificationSettings } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Mirrors the API's checks (docs/api/phase-1-attendance.md). Exported for tests. */
export function validateSettings(values: NotificationSettings): Problems {
  const problems: Problems = {};
  if (!TIME.test(values.quietHoursStart)) problems.quietHoursStart = "alerts.v.time";
  if (!TIME.test(values.quietHoursEnd)) problems.quietHoursEnd = "alerts.v.time";
  if (
    values.quietHoursEnabled &&
    !problems.quietHoursStart &&
    !problems.quietHoursEnd &&
    values.quietHoursStart === values.quietHoursEnd
  ) {
    problems.quietHoursEnd = "alerts.v.sameTime";
  }
  return problems;
}

function SettingsForm({ settings, onSaved }: { settings: NotificationSettings; onSaved: (s: NotificationSettings) => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<NotificationSettings>(settings);
  const v = form.values;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateSettings(v), t, event.currentTarget)) return;
    let saved: NotificationSettings | undefined;
    const ok = await form.submit(t, async () => {
      saved = await notificationsApi.updateSettings(v);
    });
    if (ok && saved) {
      toast(t("alerts.saved"));
      onSaved(saved);
    }
  };

  return (
    <form method="post" className="flex max-w-2xl flex-col gap-4" onSubmit={onSubmit} noValidate>
      <FormAlert message={form.formError} />
      <label className="check">
        <input
          type="checkbox"
          name="absenceAlertsEnabled"
          checked={v.absenceAlertsEnabled}
          onChange={(e) => form.set("absenceAlertsEnabled", e.target.checked)}
        />
        <span>
          <b className="block font-semibold">{t("alerts.enabled")}</b>
          <span className="block text-[13px] text-ink-3">{t("alerts.enabled.hint")}</span>
        </span>
      </label>

      <fieldset className="fieldset" disabled={!v.absenceAlertsEnabled}>
        <legend>{t("alerts.how")}</legend>
        <div className="field">
          <span className="field-label" id="alert-channel-label">
            {t("alerts.channel")}
          </span>
          <div className="seg" role="radiogroup" aria-labelledby="alert-channel-label">
            {ALERT_CHANNELS.map((channel) => (
              <label key={channel}>
                <input
                  type="radio"
                  name="absenceAlertChannel"
                  value={channel}
                  checked={v.absenceAlertChannel === channel}
                  onChange={() => form.set("absenceAlertChannel", channel)}
                />
                {t(channel === "SMS" ? "alerts.channel.SMS" : "alerts.channel.WHATSAPP_SMS")}
              </label>
            ))}
          </div>
          <p className="field-hint">{t("alerts.channel.hint")}</p>
        </div>
        <SelectField
          label={t("alerts.language")}
          name="alertLanguage"
          className="sm:max-w-xs"
          value={v.alertLanguage}
          onChange={(e) => form.set("alertLanguage", e.target.value === "hi" ? "hi" : "en")}
          options={[
            { value: "en", label: t("alerts.language.en") },
            { value: "hi", label: t("alerts.language.hi") },
          ]}
          error={form.errors.alertLanguage}
        />
      </fieldset>

      <fieldset className="fieldset">
        <legend>{t("alerts.quiet")}</legend>
        <label className="check">
          <input
            type="checkbox"
            name="quietHoursEnabled"
            checked={v.quietHoursEnabled}
            onChange={(e) => form.set("quietHoursEnabled", e.target.checked)}
          />
          <span>{t("alerts.quiet.enabled")}</span>
        </label>
        <div className="grid2">
          <TextField
            label={t("alerts.quiet.start")}
            name="quietHoursStart"
            type="time"
            value={v.quietHoursStart}
            disabled={!v.quietHoursEnabled}
            onChange={(e) => form.set("quietHoursStart", e.target.value)}
            error={form.errors.quietHoursStart}
          />
          <TextField
            label={t("alerts.quiet.end")}
            name="quietHoursEnd"
            type="time"
            value={v.quietHoursEnd}
            disabled={!v.quietHoursEnabled}
            onChange={(e) => form.set("quietHoursEnd", e.target.value)}
            error={form.errors.quietHoursEnd}
          />
        </div>
        <p className="field-hint">{t("alerts.quiet.hint")}</p>
      </fieldset>

      <div className="alert alert-info">
        <Info size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        <span>{t("alerts.backfill")}</span>
      </div>

      <div className="pt-1">
        <button type="submit" className="btn btn-primary" disabled={form.submitting}>
          {form.submitting ? t("common.saving") : t("alerts.save")}
        </button>
      </div>
    </form>
  );
}

/** Absence alert settings (settings.manage): on or off, channel, language and quiet hours. */
export function AttendanceSettingsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const settings = useApiData("notifications:settings", notificationsApi.getSettings);
  const [saved, setSaved] = useState<{ version: number; settings: NotificationSettings } | null>(null);
  const data = saved?.settings ?? settings.data;

  return (
    <>
      <PageHead
        eyebrow={t("attendance.title")}
        title={t("alerts.title")}
        actions={
          <>
            <Link href="/app/attendance" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("attendance.backToMarking")}
            </Link>
            {hasPermission(me, PERMISSIONS.messagesRead) ? (
              <Link href="/app/messages" className="btn">
                {t("alerts.openLog")}
              </Link>
            ) : null}
          </>
        }
      />
      <section className="card" aria-labelledby="alerts-title">
        <div className="card-head">
          <div>
            <h2 id="alerts-title">{t("alerts.absence")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("alerts.sub")}</p>
          </div>
        </div>
        {settings.error && !data ? (
          <ErrorState error={settings.error} onRetry={settings.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : (
          <SettingsForm
            key={saved?.version ?? 0}
            settings={data}
            onSaved={(next) => setSaved((prev) => ({ version: (prev?.version ?? 0) + 1, settings: next }))}
          />
        )}
      </section>
    </>
  );
}
