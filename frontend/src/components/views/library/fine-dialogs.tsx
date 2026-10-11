"use client";

import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { formatPaise } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import { LIBRARY_PAYMENT_MODES, type FineReceipt, type FineView, type LibraryPaymentMode } from "@/lib/types";
import { useForm, type Problems } from "@/lib/use-form";
import { fineKindLabel, memberName, modeLabel } from "./library-shared";

/* Collecting and waiving library fines (kept in the library, apart from school fees). */

export type CollectValues = { fineIds: string[]; mode: LibraryPaymentMode; reference: string };

/** The `key` for a CollectDialog: the ids of the fines still due. */
export const collectKey = (fines: readonly FineView[]) =>
  fines
    .filter((f) => f.status === "DUE")
    .map((f) => f.id)
    .join(",");

/** A cheque or bank transfer needs its reference (the API's rule). */
export const needsReference = (mode: LibraryPaymentMode) => mode === "CHEQUE" || mode === "BANK_TRANSFER";

/** The same checks the API makes before collecting. Pure and exported for tests. */
export function validateCollect(values: CollectValues): Problems {
  const problems: Problems = {};
  if (values.fineIds.length === 0) problems.fineIds = "library.v.pickFine";
  if (needsReference(values.mode) && !values.reference.trim()) problems.reference = "library.v.reference";
  else if (values.reference.trim().length > 60) problems.reference = "validation.tooLong";
  return problems;
}

/**
 * Takes payment for one member's fines under one receipt; all of them are ticked to start with. Give it a `key` made
 * from the fines' ids, so another set of fines starts afresh.
 */
export function CollectDialog({
  open,
  fines,
  onClose,
  onCollected,
}: {
  open: boolean;
  fines: FineView[];
  onClose: () => void;
  onCollected: (receipt: FineReceipt) => void;
}) {
  const { t } = useI18n();
  const due = fines.filter((f) => f.status === "DUE");
  const initial: CollectValues = { fineIds: due.map((f) => f.id), mode: "CASH", reference: "" };
  const form = useForm<CollectValues>(initial);
  const { values, set, errors } = form;

  const total = due.filter((f) => values.fineIds.includes(f.id)).reduce((sum, f) => sum + f.amountPaise, 0);
  const member = due[0]?.member ?? null;

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateCollect(values), t, event.currentTarget)) return;
    let receipt: FineReceipt | undefined;
    const ok = await form.submit(t, async () => {
      receipt = await libraryApi.collect({
        fineIds: values.fineIds,
        mode: values.mode,
        reference: values.reference.trim() || null,
      });
    });
    if (ok && receipt) onCollected(receipt);
  };

  const toggle = (id: string, on: boolean) =>
    set("fineIds", on ? [...values.fineIds, id] : values.fineIds.filter((x) => x !== id));

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("library.collect.title", { name: memberName(t, member) })}
      description={t("library.collect.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="collect-form">
        <FormAlert message={form.formError} />
        <fieldset className="flex flex-col gap-2">
          <legend className="field-label">{t("library.collect.fines")}</legend>
          {due.map((fine) => (
            <label key={fine.id} className="check">
              <input
                type="checkbox"
                name="fineIds"
                value={fine.id}
                checked={values.fineIds.includes(fine.id)}
                onChange={(e) => toggle(fine.id, e.target.checked)}
              />
              <span className="min-w-0 flex-1">
                {fine.title ?? "—"} · {fineKindLabel(t, fine.kind)}
                {fine.daysLate ? ` · ${t("library.fine.daysLate", { days: fine.daysLate })}` : ""}
              </span>
              <b className="num">{formatPaise(fine.amountPaise)}</b>
            </label>
          ))}
          {errors.fineIds ? <p className="field-error">{errors.fineIds}</p> : null}
        </fieldset>
        <div className="grid2">
          <SelectField
            label={t("library.collect.mode")}
            name="mode"
            value={values.mode}
            onChange={(e) => set("mode", e.target.value as LibraryPaymentMode)}
            error={errors.mode}
            options={LIBRARY_PAYMENT_MODES.map((mode) => ({ value: mode, label: modeLabel(t, mode) }))}
            data-autofocus
          />
          <TextField
            label={needsReference(values.mode) ? t("library.collect.reference") : t("library.collect.referenceOptional")}
            name="reference"
            value={values.reference}
            onChange={(e) => set("reference", e.target.value)}
            error={errors.reference}
            maxLength={60}
            required={needsReference(values.mode)}
            autoComplete="off"
          />
        </div>
        <p className="flex items-baseline justify-between gap-2 border-t border-line pt-3">
          <span className="text-ink-2">{t("library.collect.total")}</span>
          <b className="num text-lg" data-testid="collect-total">
            {formatPaise(total)}
          </b>
        </p>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("library.collect.submit", { amount: formatPaise(total) })}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** Waives one fine; the reason goes in the audit log. */
export function WaiveDialog({
  fine,
  onClose,
  onWaived,
}: {
  fine: FineView | null;
  onClose: () => void;
  onWaived: (fine: FineView) => void;
}) {
  const { t } = useI18n();
  const form = useForm({ reason: "" });
  const { values, set, errors } = form;

  const close = () => {
    form.reset({ reason: "" });
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!fine) return;
    const reason = values.reason.trim();
    const problems: Problems = {};
    if (!reason) problems.reason = "library.v.waiveReason";
    else if (reason.length > 500) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    let waived: FineView | undefined;
    const ok = await form.submit(t, async () => {
      waived = await libraryApi.waive(fine.id, reason);
    });
    if (ok && waived) {
      form.reset({ reason: "" });
      onWaived(waived);
    }
  };

  return (
    <Dialog
      open={fine !== null}
      onClose={close}
      title={t("library.waive.title", { amount: formatPaise(fine?.amountPaise ?? 0) })}
      description={fine ? t("library.waive.description", { name: memberName(t, fine.member), title: fine.title ?? "—" }) : undefined}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="waive-form">
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("library.waive.reason")}
          name="reason"
          value={values.reason}
          onChange={(e) => set("reason", e.target.value)}
          error={errors.reason}
          maxLength={500}
          rows={3}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("library.waive.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
