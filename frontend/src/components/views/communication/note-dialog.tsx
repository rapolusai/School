"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { useI18n } from "@/lib/i18n";

/** A step that takes a short note: approve (optional), send back or withdraw (required). */
export function NoteDialog({
  open,
  title,
  description,
  label,
  required,
  maxLength,
  confirmLabel,
  danger = false,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  description?: string;
  label: string;
  required: boolean;
  maxLength: number;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: (note: string) => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [note, setNote] = useState("");
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const close = () => {
    setNote("");
    setFieldError(undefined);
    setError(null);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    if (required && !note.trim()) {
      setFieldError(t("validation.required"));
      event.currentTarget.querySelector<HTMLElement>("textarea")?.focus();
      return;
    }
    if (note.trim().length > maxLength) {
      setFieldError(t("validation.tooLong"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onConfirm(note.trim());
      setNote("");
    } catch (caught) {
      const apiError = toApiError(caught);
      const fieldMessage = apiError.errors ? Object.values(apiError.errors)[0] : undefined;
      if (fieldMessage) setFieldError(fieldMessage);
      else setError(errorMessage(apiError, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={close} title={title} description={description} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={error} />
        <TextAreaField
          label={label}
          name="note"
          rows={3}
          value={note}
          onChange={(e) => {
            setNote(e.target.value);
            setFieldError(undefined);
          }}
          error={fieldError}
          maxLength={maxLength}
          required={required}
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className={danger ? "btn btn-danger" : "btn btn-primary"} disabled={busy}>
            {busy ? t("common.working") : confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
