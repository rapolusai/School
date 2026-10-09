"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { noticesApi } from "@/lib/communication-api";
import { errorMessage } from "@/lib/error-message";
import { useI18n } from "@/lib/i18n";
import { ACK_CHANNELS, type AckChannel, type CommunicationSettings } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/** The school's communication settings (settings.manage): approval for teachers, enquiry thank-you messages. */
export function CommunicationSettingsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const settings = useApiData(open ? "notices:settings" : null, noticesApi.getSettings);
  const [draft, setDraft] = useState<CommunicationSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const values = draft ?? settings.data;

  const close = () => {
    setDraft(null);
    setError(null);
    onClose();
  };

  const set = <K extends keyof CommunicationSettings>(key: K, value: CommunicationSettings[K]) => {
    if (!values) return;
    setDraft({ ...values, [key]: value });
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!values || saving) return;
    setSaving(true);
    setError(null);
    try {
      await noticesApi.updateSettings(values);
      toast(t("notices.settings.saved"));
      settings.reload();
      close();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onClose={close} title={t("notices.settings.title")} closeLabel={t("common.close")}>
      {settings.error && !values ? (
        <ErrorState error={settings.error} onRetry={settings.reload} />
      ) : !values ? (
        <LoadingRows rows={3} />
      ) : (
        <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
          <FormAlert message={error} />
          <fieldset className="fieldset">
            <legend>{t("notices.settings.approval")}</legend>
            <label className="check">
              <input
                type="checkbox"
                name="teacherCircularsNeedApproval"
                checked={values.teacherCircularsNeedApproval}
                onChange={(e) => set("teacherCircularsNeedApproval", e.target.checked)}
              />
              <span>{t("notices.settings.teacherApproval")}</span>
            </label>
            <p className="field-hint">{t("notices.settings.teacherApproval.hint")}</p>
          </fieldset>
          <fieldset className="fieldset">
            <legend>{t("notices.settings.enquiry")}</legend>
            <label className="check">
              <input
                type="checkbox"
                name="enquiryAckEnabled"
                checked={values.enquiryAckEnabled}
                onChange={(e) => set("enquiryAckEnabled", e.target.checked)}
              />
              <span>{t("notices.settings.enquiryAck")}</span>
            </label>
            <SelectField
              label={t("notices.settings.enquiryChannel")}
              name="enquiryAckChannel"
              value={values.enquiryAckChannel}
              disabled={!values.enquiryAckEnabled}
              onChange={(e) => set("enquiryAckChannel", e.target.value as AckChannel)}
              options={ACK_CHANNELS.map((c) => ({ value: c, label: t(`notices.settings.ack.${c}`) }))}
              hint={t("notices.settings.enquiryAck.hint")}
            />
          </fieldset>
          <div className="flex justify-end gap-2 pt-1">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? t("common.saving") : t("common.save")}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
