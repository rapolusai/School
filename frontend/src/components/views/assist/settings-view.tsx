"use client";

import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { assistApi } from "@/lib/assist-api";
import { useI18n, type MessageKey } from "@/lib/i18n";
import type { AssistSettings, AssistSettingsRequest, AssistWeights } from "@/lib/types";
import { AssistNav, assistError, useAssistSettings } from "./assist-shared";

const SWITCHES = ["remarksEnabled", "earlyWarningEnabled", "papersEnabled", "writingEnabled"] as const;
const WEIGHTS = ["attendance30", "attendance90", "marksTrend", "feeDues", "homework", "threshold"] as const;

/** The request from the settings shown. Pure, exported for tests. */
export function settingsRequest(s: AssistSettings): AssistSettingsRequest {
  return {
    enabled: s.enabled,
    remarksEnabled: s.remarksEnabled,
    earlyWarningEnabled: s.earlyWarningEnabled,
    papersEnabled: s.papersEnabled,
    writingEnabled: s.writingEnabled,
    monthlyLimit: s.monthlyLimit,
    language: s.language,
    weights: s.weights,
  };
}

/** School Admin: assistance on or off, each feature, the monthly limit, the language and the early warning weights. */
export function AssistSettingsView() {
  const { t } = useI18n();
  const settings = useAssistSettings();
  if (settings.error && !settings.data) return <ErrorState error={settings.error} onRetry={settings.reload} />;
  return (
    <div className="flex flex-col gap-4">
      <PageHead eyebrow={t("assist.eyebrow")} title={t("assist.settings.title")} />
      <AssistNav />
      {!settings.data ? <LoadingRows rows={6} /> : <SettingsForm key={settings.data.updatedAt ?? "new"} initial={settings.data} onSaved={settings.reload} />}
    </div>
  );
}

function SettingsForm({ initial, onSaved }: { initial: AssistSettings; onSaved: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [form, setForm] = useState<AssistSettingsRequest>(settingsRequest(initial));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const setWeight = (key: keyof AssistWeights, value: number) => setForm((f) => ({ ...f, weights: { ...f.weights, [key]: value } }));

  const save = async () => {
    setBusy(true);
    setProblem(null);
    try {
      await assistApi.saveSettings(form);
      toast(t("assist.saved"));
      onSaved();
    } catch (error) {
      setProblem(assistError(toApiError(error), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form
      method="post"
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        void save();
      }}
    >
      <section className="card flex flex-col gap-3">
        <p className="text-[14px]">{t("assist.settings.provider", { provider: initial.provider })}</p>
        <p className="text-[13px] text-ink-3">{t("assist.settings.privacy")}</p>
        <label className="check">
          <input type="checkbox" name="enabled" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
          <span>{t("assist.settings.enabled")}</span>
        </label>
        {SWITCHES.map((key) => (
          <label key={key} className="check">
            <input type="checkbox" name={key} checked={form[key]} disabled={!form.enabled} onChange={(e) => setForm({ ...form, [key]: e.target.checked })} />
            <span>{t(`assist.settings.${key}` as MessageKey)}</span>
          </label>
        ))}
        <div className="flex flex-wrap gap-3">
          <label className="field w-40">
            <span className="field-label">{t("assist.settings.limit")}</span>
            <input className="input" type="number" name="monthlyLimit" min={0} max={100000} value={form.monthlyLimit} onChange={(e) => setForm({ ...form, monthlyLimit: Number(e.target.value) })} />
          </label>
          <label className="field w-40">
            <span className="field-label">{t("assist.settings.language")}</span>
            <select className="input" name="language" value={form.language} onChange={(e) => setForm({ ...form, language: e.target.value === "hi" ? "hi" : "en" })}>
              <option value="en">English</option>
              <option value="hi">हिन्दी</option>
            </select>
          </label>
        </div>
        <p className="text-[13px] text-ink-3">{t("assist.settings.used", { used: initial.usedThisMonth, limit: initial.monthlyLimit })}</p>
      </section>
      <section className="card flex flex-col gap-3">
        <h2 className="font-semibold">{t("assist.settings.weights")}</h2>
        <p className="text-[13px] text-ink-3">{t("assist.settings.weightsHint")}</p>
        <div className="flex flex-wrap gap-3">
          {WEIGHTS.map((key) => (
            <label key={key} className="field w-36">
              <span className="field-label">{t(`assist.weight.${key}` as MessageKey)}</span>
              <input className="input" type="number" name={key} min={key === "threshold" ? 1 : 0} max={100} value={form.weights[key]} onChange={(e) => setWeight(key, Number(e.target.value))} />
            </label>
          ))}
        </div>
        <p className="text-[13px] text-ink-3">{t("assist.settings.notUsed")}</p>
      </section>
      <FormAlert message={problem} />
      <button type="submit" className="btn btn-primary self-start" disabled={busy}>
        {t("assist.save")}
      </button>
    </form>
  );
}
