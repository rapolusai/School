"use client";

import { useState } from "react";
import { TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api } from "@/lib/api";
import { translateOr, useI18n } from "@/lib/i18n";
import type { SchoolProfile, SchoolProfileRequest } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isValidEmail, isValidSchoolPhone, UDISE_PATTERN } from "@/lib/validation";

export function validateProfile(values: SchoolProfileRequest): Problems {
  const problems: Problems = {};
  if (values.address.trim().length > 500) problems.address = "validation.tooLong";
  if (values.phone.trim() && !isValidSchoolPhone(values.phone)) problems.phone = "setup.profile.phoneFormat";
  if (values.contactEmail.trim() && !isValidEmail(values.contactEmail)) problems.contactEmail = "validation.email";
  if (values.udiseCode.trim() && !UDISE_PATTERN.test(values.udiseCode.trim())) problems.udiseCode = "setup.profile.udiseFormat";
  return problems;
}

const toValues = (profile: SchoolProfile): SchoolProfileRequest => ({
  address: profile.address ?? "",
  phone: profile.phone ?? "",
  contactEmail: profile.contactEmail ?? "",
  udiseCode: profile.udiseCode ?? "",
});

function ProfileForm({ profile, onSaved }: { profile: SchoolProfile; onSaved: (p: SchoolProfile) => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<SchoolProfileRequest>(toValues(profile));

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateProfile(form.values), t, event.currentTarget)) return;
    let saved: SchoolProfile | undefined;
    const ok = await form.submit(t, async () => {
      saved = await api.updateSchoolProfile({
        address: form.values.address.trim(),
        phone: form.values.phone.trim(),
        contactEmail: form.values.contactEmail.trim(),
        udiseCode: form.values.udiseCode.trim(),
      });
    });
    if (ok && saved) {
      toast(t("setup.profile.saved"));
      onSaved(saved);
    }
  };

  return (
    <form method="post" className="flex max-w-2xl flex-col gap-3.5" onSubmit={onSubmit} noValidate>
      <FormAlert message={form.formError} />
      <TextAreaField
        label={t("setup.profile.address")}
        name="address"
        rows={3}
        maxLength={500}
        value={form.values.address}
        onChange={(e) => form.set("address", e.target.value)}
        error={form.errors.address}
      />
      <div className="grid2">
        <TextField
          label={t("setup.profile.phone")}
          name="phone"
          type="tel"
          inputMode="tel"
          maxLength={20}
          placeholder={t("setup.profile.phone.placeholder")}
          value={form.values.phone}
          onChange={(e) => form.set("phone", e.target.value)}
          error={form.errors.phone}
        />
        <TextField
          label={t("setup.profile.email")}
          name="contactEmail"
          type="email"
          maxLength={254}
          value={form.values.contactEmail}
          onChange={(e) => form.set("contactEmail", e.target.value)}
          error={form.errors.contactEmail}
        />
      </div>
      <TextField
        label={t("setup.profile.udise")}
        name="udiseCode"
        inputMode="numeric"
        maxLength={11}
        className="[&_input]:font-mono sm:max-w-xs"
        hint={t("setup.profile.udise.hint")}
        value={form.values.udiseCode}
        onChange={(e) => form.set("udiseCode", e.target.value)}
        error={form.errors.udiseCode}
      />
      <div className="pt-1">
        <button type="submit" className="btn btn-primary" disabled={form.submitting}>
          {form.submitting ? t("common.saving") : t("setup.profile.save")}
        </button>
      </div>
    </form>
  );
}

export function ProfilePanel({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const profile = useApiData("school:profile", api.getSchoolProfile);
  // The last saved copy, so the form shows what was saved without waiting for a reload.
  const [saved, setSaved] = useState<{ version: number; profile: SchoolProfile } | null>(null);
  const data = saved?.profile ?? profile.data;

  return (
    <section className="card" aria-labelledby="profile-title">
      <div className="card-head">
        <div>
          <h2 id="profile-title">{t("setup.profile.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("setup.profile.sub")}</p>
        </div>
      </div>
      {profile.error && !data ? (
        <ErrorState error={profile.error} onRetry={profile.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : (
        <div className="flex flex-col gap-5">
          <dl className="kv">
            <dt>{t("setup.profile.name")}</dt>
            <dd>{data.name}</dd>
            <dt>{t("dashboard.school.code")}</dt>
            <dd className="mono">{data.code}</dd>
            <dt>{t("dashboard.school.board")}</dt>
            <dd>{translateOr(t, `board.${data.board}`, data.board)}</dd>
            <dt>{t("dashboard.school.city")}</dt>
            <dd>{data.city ?? "—"}</dd>
            {!canManage ? (
              <>
                <dt>{t("setup.profile.address")}</dt>
                <dd className="whitespace-pre-line">{data.address ?? "—"}</dd>
                <dt>{t("setup.profile.phone")}</dt>
                <dd>{data.phone ?? "—"}</dd>
                <dt>{t("setup.profile.email")}</dt>
                <dd>{data.contactEmail ?? "—"}</dd>
                <dt>{t("setup.profile.udise")}</dt>
                <dd className="mono">{data.udiseCode ?? "—"}</dd>
              </>
            ) : null}
          </dl>
          {canManage ? (
            <ProfileForm
              key={saved?.version ?? 0}
              profile={data}
              onSaved={(next) => setSaved((prev) => ({ version: (prev?.version ?? 0) + 1, profile: next }))}
            />
          ) : null}
        </div>
      )}
    </section>
  );
}
