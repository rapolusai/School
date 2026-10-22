"use client";

import { Save } from "lucide-react";
import { useEffect, useState } from "react";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { certificatesApi } from "@/lib/certificates-api";
import { parseRupees, rupeesInput } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { CERTIFICATE_TYPES, type CertificateSettings, type CertificateTemplate, type CertificateType } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { CertificatesNav, placeholderLabel, typeLabel, useCanIssue } from "./certificates-shared";

/* /app/certificates/templates: the printed header and signatory, the module's settings, and each type's wording. */

type SettingsValues = {
  headerName: string;
  headerAddress: string;
  affiliationNo: string;
  logoText: string;
  signatoryName: string;
  signatoryDesignation: string;
  tcDuesCheck: boolean;
  cardValidMonths: string;
  lostCardFee: string;
};

function settingsValues(s: CertificateSettings): SettingsValues {
  return {
    headerName: s.headerName ?? "",
    headerAddress: s.headerAddress ?? "",
    affiliationNo: s.affiliationNo ?? "",
    logoText: s.logoText ?? "",
    signatoryName: s.signatoryName ?? "",
    signatoryDesignation: s.signatoryDesignation ?? "",
    tcDuesCheck: s.tcDuesCheck,
    cardValidMonths: String(s.cardValidMonths),
    lostCardFee: s.lostCardFeePaise ? rupeesInput(s.lostCardFeePaise) : "",
  };
}

/** The same checks as the API. Exported for tests. */
export function validateSettings(values: SettingsValues): Problems {
  const problems: Problems = {};
  if (values.headerName.trim().length > 200) problems.headerName = "validation.tooLong";
  if (values.headerAddress.trim().length > 500) problems.headerAddress = "validation.tooLong";
  if (values.affiliationNo.trim().length > 60) problems.affiliationNo = "validation.tooLong";
  if (values.logoText.trim().length > 12) problems.logoText = "certificates.v.logoText";
  if (values.signatoryName.trim().length > 200) problems.signatoryName = "validation.tooLong";
  if (values.signatoryDesignation.trim().length > 100) problems.signatoryDesignation = "validation.tooLong";
  const months = Number(values.cardValidMonths);
  if (!Number.isInteger(months) || months < 1 || months > 60) problems.cardValidMonths = "certificates.v.months";
  if (values.lostCardFee.trim()) {
    const paise = parseRupees(values.lostCardFee);
    if (paise === null || paise < 0 || paise > 10_000_000) problems.lostCardFee = "certificates.v.fee";
  }
  return problems;
}

export function validateTemplate(values: { title: string; body: string; serialPrefix: string }): Problems {
  const problems: Problems = {};
  if (!values.title.trim()) problems.title = "validation.required";
  else if (values.title.trim().length > 120) problems.title = "validation.tooLong";
  if (!values.body.trim()) problems.body = "validation.required";
  else if (values.body.length > 10_000) problems.body = "validation.tooLong";
  if (!/^[A-Z0-9-]{1,8}$/.test(values.serialPrefix.trim())) problems.serialPrefix = "certificates.v.prefix";
  return problems;
}

export function TemplatesView() {
  const { t } = useI18n();
  const canIssue = useCanIssue();
  return (
    <>
      <PageHead eyebrow={t("nav.certificates")} title={t("certificates.templates.title")} />
      <CertificatesNav />
      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2">
        <SettingsCard canEdit={canIssue} />
        <TemplateCard canEdit={canIssue} />
      </div>
    </>
  );
}

function SettingsCard({ canEdit }: { canEdit: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const settings = useApiData("certificates:settings", certificatesApi.settings);
  const form = useForm<SettingsValues>({
    headerName: "",
    headerAddress: "",
    affiliationNo: "",
    logoText: "",
    signatoryName: "",
    signatoryDesignation: "",
    tcDuesCheck: true,
    cardValidMonths: "12",
    lostCardFee: "",
  });
  const { reset } = form;
  useEffect(() => {
    if (settings.data) reset(settingsValues(settings.data));
  }, [settings.data, reset]);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateSettings(form.values), t, event.currentTarget)) return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      const saved = await certificatesApi.saveSettings({
        headerName: v.headerName.trim() || null,
        headerAddress: v.headerAddress.trim() || null,
        affiliationNo: v.affiliationNo.trim() || null,
        logoText: v.logoText.trim() || null,
        signatoryName: v.signatoryName.trim() || null,
        signatoryDesignation: v.signatoryDesignation.trim() || null,
        tcDuesCheck: v.tcDuesCheck,
        cardValidMonths: Number(v.cardValidMonths),
        lostCardFeePaise: v.lostCardFee.trim() ? (parseRupees(v.lostCardFee) ?? 0) : 0,
      });
      reset(settingsValues(saved));
    });
    if (ok) toast(t("certificates.settings.saved"));
  };

  if (settings.error && !settings.data) return <ErrorState error={settings.error} onRetry={settings.reload} />;
  if (!settings.data) return <LoadingRows rows={5} />;

  return (
    <form method="post" className="card flex flex-col gap-3" onSubmit={submit} noValidate aria-labelledby="cert-settings" data-testid="certificate-settings">
      <div className="card-head">
        <h2 id="cert-settings">{t("certificates.settings.title")}</h2>
      </div>
      <FormAlert message={form.formError} />
      <TextField label={t("certificates.settings.headerName")} name="headerName" value={form.values.headerName} onChange={(e) => form.set("headerName", e.target.value)} maxLength={200} error={form.errors.headerName} disabled={!canEdit} hint={t("certificates.settings.headerHint")} />
      <TextAreaField label={t("certificates.settings.headerAddress")} name="headerAddress" value={form.values.headerAddress} onChange={(e) => form.set("headerAddress", e.target.value)} rows={2} maxLength={500} error={form.errors.headerAddress} disabled={!canEdit} />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField label={t("certificates.settings.affiliationNo")} name="affiliationNo" value={form.values.affiliationNo} onChange={(e) => form.set("affiliationNo", e.target.value)} maxLength={60} error={form.errors.affiliationNo} disabled={!canEdit} />
        <TextField label={t("certificates.settings.logoText")} name="logoText" value={form.values.logoText} onChange={(e) => form.set("logoText", e.target.value)} maxLength={12} error={form.errors.logoText} disabled={!canEdit} hint={t("certificates.settings.logoHint")} />
        <TextField label={t("certificates.settings.signatoryName")} name="signatoryName" value={form.values.signatoryName} onChange={(e) => form.set("signatoryName", e.target.value)} maxLength={200} error={form.errors.signatoryName} disabled={!canEdit} />
        <TextField label={t("certificates.settings.signatoryDesignation")} name="signatoryDesignation" value={form.values.signatoryDesignation} onChange={(e) => form.set("signatoryDesignation", e.target.value)} maxLength={100} error={form.errors.signatoryDesignation} disabled={!canEdit} />
        <TextField label={t("certificates.settings.cardValidMonths")} name="cardValidMonths" type="number" inputMode="numeric" min={1} max={60} value={form.values.cardValidMonths} onChange={(e) => form.set("cardValidMonths", e.target.value)} error={form.errors.cardValidMonths} disabled={!canEdit} />
        <TextField label={t("certificates.settings.lostCardFee")} name="lostCardFee" inputMode="decimal" value={form.values.lostCardFee} onChange={(e) => form.set("lostCardFee", e.target.value)} error={form.errors.lostCardFee} disabled={!canEdit} hint={t("certificates.settings.lostCardFeeHint")} />
      </div>
      <label className="check">
        <input type="checkbox" name="tcDuesCheck" checked={form.values.tcDuesCheck} onChange={(e) => form.set("tcDuesCheck", e.target.checked)} disabled={!canEdit} />
        <span>{t("certificates.settings.tcDuesCheck")}</span>
      </label>
      {canEdit ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            <Save size={18} aria-hidden="true" />
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}

function TemplateCard({ canEdit }: { canEdit: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [type, setType] = useState<CertificateType>("BONAFIDE");
  const templates = useApiData("certificates:templates", certificatesApi.templates);
  const [saved, setSaved] = useState<Record<string, CertificateTemplate>>({});
  const template: CertificateTemplate | undefined = saved[type] ?? templates.data?.find((x) => x.type === type);
  const form = useForm<{ title: string; body: string; serialPrefix: string }>({ title: "", body: "", serialPrefix: "" });
  const { reset } = form;
  useEffect(() => {
    if (template) reset({ title: template.title, body: template.body, serialPrefix: template.serialPrefix });
  }, [template, reset]);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateTemplate(form.values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      const next = await certificatesApi.saveTemplate(type, {
        title: form.values.title.trim(),
        body: form.values.body.trim(),
        serialPrefix: form.values.serialPrefix.trim(),
      });
      setSaved((prev) => ({ ...prev, [type]: next }));
    });
    if (ok) toast(t("certificates.template.saved"));
  };

  if (templates.error && !templates.data) return <ErrorState error={templates.error} onRetry={templates.reload} />;
  if (!templates.data || !template) return <LoadingRows rows={5} />;

  return (
    <form method="post" className="card flex flex-col gap-3" onSubmit={submit} noValidate aria-labelledby="cert-template" data-testid="certificate-template">
      <div className="card-head">
        <h2 id="cert-template">{t("certificates.template.title")}</h2>
      </div>
      <FormAlert message={form.formError} />
      <SelectField
        label={t("certificates.col.type")}
        name="templateType"
        value={type}
        onChange={(e) => setType(e.target.value as CertificateType)}
        options={CERTIFICATE_TYPES.map((x) => ({ value: x, label: typeLabel(t, x) }))}
        hint={t(template.custom ? "certificates.template.custom" : "certificates.template.default", { serial: template.nextSerial })}
      />
      <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_8rem]">
        <TextField label={t("certificates.col.title")} name="title" value={form.values.title} onChange={(e) => form.set("title", e.target.value)} maxLength={120} error={form.errors.title} disabled={!canEdit} required />
        <TextField label={t("certificates.template.prefix")} name="serialPrefix" value={form.values.serialPrefix} onChange={(e) => form.set("serialPrefix", e.target.value.toUpperCase())} maxLength={8} error={form.errors.serialPrefix} disabled={!canEdit} className="mono" required />
      </div>
      <TextAreaField label={t("certificates.template.body")} name="body" value={form.values.body} onChange={(e) => form.set("body", e.target.value)} rows={10} maxLength={10_000} error={form.errors.body} disabled={!canEdit} hint={t("certificates.template.bodyHint")} required />
      <div className="flex flex-col gap-1">
        <span className="field-label">{t("certificates.template.placeholders")}</span>
        <ul className="flex flex-wrap gap-1.5" aria-label={t("certificates.template.placeholders")}>
          {template.placeholders.map((key) => (
            <li key={key}>
              <button
                type="button"
                className="pill pill-neutral"
                title={placeholderLabel(t, key)}
                disabled={!canEdit}
                onClick={() => form.set("body", `${form.values.body}${form.values.body.endsWith(" ") || !form.values.body ? "" : " "}{${key}}`)}
              >
                <span className="mono">{`{${key}}`}</span>
                <span className="sr-only"> {placeholderLabel(t, key)}</span>
              </button>
            </li>
          ))}
        </ul>
      </div>
      {canEdit ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="save-template">
            <Save size={18} aria-hidden="true" />
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}
