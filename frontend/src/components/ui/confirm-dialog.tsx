"use client";

import { useState } from "react";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { useI18n } from "@/lib/i18n";
import { Dialog } from "./dialog";
import { FormAlert } from "./states";

/**
 * Asks before something that cannot be undone (deleting a class, removing a guardian). The
 * action runs inside the dialog so a refusal from the API (409 "In use") is shown in place.
 */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  onConfirm,
  onClose,
  danger = true,
}: {
  open: boolean;
  title: string;
  body: string;
  confirmLabel: string;
  onConfirm: () => Promise<void>;
  onClose: () => void;
  danger?: boolean;
}) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const close = () => {
    setError(null);
    onClose();
  };

  const confirm = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
      setError(null);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={close} title={title} description={body} closeLabel={t("common.close")}>
      <FormAlert message={error} />
      <div className="flex justify-end gap-2">
        <button type="button" className="btn" onClick={close}>
          {t("common.cancel")}
        </button>
        <button
          type="button"
          className={danger ? "btn btn-danger" : "btn btn-primary"}
          onClick={confirm}
          disabled={busy}
          data-autofocus
        >
          {busy ? t("common.working") : confirmLabel}
        </button>
      </div>
    </Dialog>
  );
}
