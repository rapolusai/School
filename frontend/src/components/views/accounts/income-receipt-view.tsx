"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { PrintButton } from "@/components/views/fees/receipt-document";
import { accountsApi } from "@/lib/accounts-api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { IncomeReceiptView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { AccountsNav, useModeLabel } from "./accounts-ui";

/** /app/accounts/income/[id]: a receipt as printed, and its cancellation (with a reason) by an accountant. */
export function IncomeReceiptPage({ id }: { id: string }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const receipt = useApiData(`accounts:receipt:${id}`, () => accountsApi.receipt(id));
  const [fresh, setFresh] = useState<IncomeReceiptView | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const r = fresh ?? receipt.data;
  return (
    <>
      <PageHead
        eyebrow={t("accounts.income.title")}
        title={r ? t("accounts.receipt.titleNo", { number: r.receiptNo }) : t("accounts.receipt.title")}
        actions={
          r ? (
            <>
              <PrintButton label={t("accounts.receipt.print")} />
              {canManage && r.status === "ISSUED" ? (
                <button type="button" className="btn btn-danger" onClick={() => setCancelling(true)} data-testid="receipt-cancel">
                  {t("accounts.receipt.cancel")}
                </button>
              ) : null}
            </>
          ) : null
        }
      />
      <AccountsNav />
      {receipt.error && !r ? (
        <ErrorState error={receipt.error} onRetry={receipt.reload} />
      ) : !r ? (
        <LoadingRows rows={6} />
      ) : (
        <IncomeReceiptDocument receipt={r} />
      )}
      {cancelling && r ? (
        <CancelDialog
          receipt={r}
          onClose={() => setCancelling(false)}
          onCancelled={(saved) => {
            setCancelling(false);
            setFresh(saved);
            toast(t("accounts.receipt.cancelled"));
          }}
        />
      ) : null}
    </>
  );
}

/**
 * An income receipt as printed, in the fee receipt's style: the school's header, the payer, the amount in figures
 * and words, how it was paid, and the 80G number when the school is registered. Only this block is printed.
 */
export function IncomeReceiptDocument({ receipt }: { receipt: IncomeReceiptView }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  const school = receipt.school;
  const cancelled = receipt.status === "CANCELLED";
  const payment = [
    modeLabel(receipt.mode),
    receipt.bankAccountName,
    receipt.reference ? t("accounts.receipt.reference", { reference: receipt.reference }) : null,
  ]
    .filter(Boolean)
    .join(" · ");
  return (
    <article
      className={`receipt print-area${cancelled ? " receipt-cancelled" : ""}`}
      aria-label={t("accounts.receipt.titleNo", { number: receipt.receiptNo })}
      data-stamp={cancelled ? t("accounts.receiptStatus.CANCELLED") : undefined}
      data-testid="income-receipt"
    >
      <header className="receipt-head">
        <div className="min-w-0">
          <p className="receipt-school">{school.name}</p>
          {school.address ? <p className="text-[13px] text-ink-2">{school.address}</p> : null}
          <p className="text-[12.5px] text-ink-3">
            {[school.phone, school.contactEmail, school.board ? translateOr(t, `board.${school.board}`, school.board) : null]
              .filter(Boolean)
              .join(" · ")}
          </p>
        </div>
        <div className="receipt-title">
          <h2>{receipt.kind === "DONATION" ? t("accounts.receipt.donation") : t("accounts.receipt.title")}</h2>
          <Pill tone={cancelled ? "bad" : "good"} dot>
            {translateOr(t, `accounts.receiptStatus.${receipt.status}`, receipt.status)}
          </Pill>
        </div>
      </header>

      <dl className="receipt-meta">
        <div>
          <dt>{t("accounts.col.receiptNo")}</dt>
          <dd className="mono" data-testid="income-receipt-no">
            {receipt.receiptNo}
          </dd>
        </div>
        <div>
          <dt>{t("accounts.col.date")}</dt>
          <dd>{formatPlainDate(receipt.receivedOn, locale)}</dd>
        </div>
        <div>
          <dt>{t("accounts.col.payer")}</dt>
          <dd>{receipt.payerName}</dd>
        </div>
        {receipt.payerPanMasked ? (
          <div>
            <dt>{t("accounts.income.payerPan")}</dt>
            <dd className="mono">{receipt.payerPanMasked}</dd>
          </div>
        ) : null}
        <div>
          <dt>{t("accounts.col.kind")}</dt>
          <dd>{translateOr(t, `accounts.incomeKind.${receipt.kind}`, receipt.kind)}</dd>
        </div>
        <div>
          <dt>{t("accounts.financialYear")}</dt>
          <dd>{receipt.financialYear}</dd>
        </div>
        {receipt.eightyGNumber ? (
          <div>
            <dt>{t("accounts.receipt.eightyG")}</dt>
            <dd className="mono" data-testid="income-receipt-80g">
              {receipt.eightyGNumber}
            </dd>
          </div>
        ) : null}
      </dl>

      <table className="table receipt-lines">
        <thead>
          <tr>
            <th scope="col">{t("accounts.receipt.particulars")}</th>
            <th scope="col" className="r">
              {t("accounts.col.amount")}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              {receipt.headName}
              {receipt.description ? ` · ${receipt.description}` : ""}
            </td>
            <td className="r num">{formatPaise(receipt.amountPaise)}</td>
          </tr>
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">{t("accounts.col.total")}</th>
            <td className="r num font-bold">{formatPaise(receipt.amountPaise)}</td>
          </tr>
        </tfoot>
      </table>

      <p className="receipt-words">
        <span className="text-ink-3">{t("accounts.receipt.inWords")}</span> {receipt.amountInWords}
      </p>

      <dl className="kv text-[13.5px]">
        <dt>{t("accounts.receipt.paidBy")}</dt>
        <dd>{payment}</dd>
        {receipt.payerAddress ? (
          <>
            <dt>{t("accounts.income.payerAddress")}</dt>
            <dd>{receipt.payerAddress}</dd>
          </>
        ) : null}
        <dt>{t("accounts.receipt.receivedBy")}</dt>
        <dd>
          {receipt.receivedByName} · {formatDateTime(receipt.createdAt, locale)}
        </dd>
        {receipt.eightyGRegistration ? (
          <>
            <dt>{t("accounts.receipt.eightyGRegistration")}</dt>
            <dd className="mono">{receipt.eightyGRegistration}</dd>
          </>
        ) : null}
      </dl>

      {cancelled ? (
        <div className="alert alert-bad" role="note" data-testid="income-receipt-cancelled">
          <span>
            {t("accounts.receipt.cancelledNote", {
              date: formatDateTime(receipt.cancelledAt, locale),
              name: receipt.cancelledByName ?? t("common.system"),
              reason: receipt.cancelReason ?? "",
            })}
          </span>
        </div>
      ) : null}

      <p className="receipt-foot">{receipt.eightyGNumber ? t("accounts.receipt.footer80g") : t("accounts.receipt.footer")}</p>
    </article>
  );
}

function CancelDialog({ receipt, onClose, onCancelled }: { receipt: IncomeReceiptView; onClose: () => void; onCancelled: (r: IncomeReceiptView) => void }) {
  const { t } = useI18n();
  const form = useForm({ reason: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(form.values.reason.trim() ? {} : { reason: "accounts.v.reason" }, t, event.currentTarget)) return;
    let saved: IncomeReceiptView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await accountsApi.cancelIncome(receipt.id, form.values.reason.trim());
    });
    if (ok && saved) onCancelled(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("accounts.receipt.cancelTitle")} description={t("accounts.receipt.cancelDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField label={t("accounts.expense.reason")} name="reason" value={form.values.reason} onChange={(e) => form.set("reason", e.target.value)} error={form.errors.reason} rows={3} maxLength={500} required data-autofocus />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("accounts.receipt.cancel")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
