"use client";

import { ArrowLeft, BadgeIndianRupee, XCircle } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { platformBillingApi } from "@/lib/billing-api";
import { formatPaise, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import { INVOICE_PAYMENT_MODES, type Invoice, type InvoicePaymentMode } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { PrintButton } from "../fees/receipt-document";
import { InvoiceDocument } from "./invoice-document";
import { InvoicePageState } from "./school-billing-view";

type PaymentValues = { amount: string; mode: InvoicePaymentMode; reference: string; paidOn: string };

/** Client-side checks mirroring POST …/payments: an amount up to the balance, a reference, a date not in the future. */
export function paymentProblems(values: PaymentValues, balancePaise: number, today: string): Problems {
  const problems: Problems = {};
  const amount = parseRupees(values.amount);
  if (amount === null || amount <= 0) problems.amount = "billing.payment.amountInvalid";
  else if (amount > balancePaise) problems.amount = "billing.payment.amountTooHigh";
  if (!values.reference.trim()) problems.reference = "billing.payment.referenceRequired";
  else if (values.reference.trim().length > 100) problems.reference = "validation.tooLong";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(values.paidOn)) problems.paidOn = "validation.date";
  else if (values.paidOn > today) problems.paidOn = "billing.payment.future";
  return problems;
}

function PaymentDialog({
  tenantId,
  invoice,
  onClose,
  onDone,
}: {
  tenantId: string;
  invoice: Invoice;
  onClose: () => void;
  onDone: (next: Invoice) => void;
}) {
  const { t } = useI18n();
  const today = todayInIndia();
  const form = useForm<PaymentValues>({
    amount: rupeesInput(invoice.balancePaise),
    mode: "BANK_TRANSFER",
    reference: "",
    paidOn: today,
  });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const v = form.values;
    if (!form.check(paymentProblems(v, invoice.balancePaise, today), t, event.currentTarget)) return;
    const result: { invoice?: Invoice } = {};
    const ok = await form.submit(t, async () => {
      result.invoice = await platformBillingApi.recordPayment(tenantId, invoice.id, {
        amountPaise: parseRupees(v.amount),
        mode: v.mode,
        reference: v.reference.trim(),
        paidOn: v.paidOn,
      });
    });
    if (ok && result.invoice) onDone(result.invoice);
  };
  // The API names the field amountPaise; the form shows it on the rupee input.
  const amountError = form.errors.amount ?? form.errors.amountPaise;
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("billing.payment.title", { number: invoice.invoiceNo })}
      description={t("billing.payment.description", { amount: formatPaise(invoice.balancePaise) })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("billing.payment.amount")}
          name="amount"
          inputMode="decimal"
          value={form.values.amount}
          onChange={(e) => form.set("amount", e.target.value)}
          error={amountError}
          required
          data-autofocus
        />
        <SelectField
          label={t("billing.payment.mode")}
          name="mode"
          value={form.values.mode}
          options={INVOICE_PAYMENT_MODES.map((mode) => ({
            value: mode,
            label: translateOr(t, `fees.mode.${mode}`, mode),
          }))}
          onChange={(e) => form.set("mode", e.target.value as InvoicePaymentMode)}
          error={form.errors.mode}
        />
        <TextField
          label={t("billing.payment.reference")}
          name="reference"
          value={form.values.reference}
          maxLength={100}
          hint={t("billing.payment.referenceHint")}
          onChange={(e) => form.set("reference", e.target.value)}
          error={form.errors.reference}
          required
        />
        <TextField
          label={t("billing.payment.paidOn")}
          name="paidOn"
          type="date"
          max={today}
          value={form.values.paidOn}
          onChange={(e) => form.set("paidOn", e.target.value)}
          error={form.errors.paidOn}
          required
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("billing.payment.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function CancelInvoiceDialog({
  tenantId,
  invoice,
  onClose,
  onDone,
}: {
  tenantId: string;
  invoice: Invoice;
  onClose: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const form = useForm({ reason: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const reason = form.values.reason.trim();
    const problems: Problems = {};
    if (!reason) problems.reason = "validation.required";
    else if (reason.length > 500) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await platformBillingApi.cancelInvoice(tenantId, invoice.id, reason);
    });
    if (ok) onDone();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("billing.cancel.title", { number: invoice.invoiceNo })}
      description={t("billing.cancel.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("billing.cancel.reason")}
          name="reason"
          value={form.values.reason}
          maxLength={500}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("billing.cancel.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** /app/platform/schools/[id]/invoices/[invoiceId]: the Super Admin records payments, cancels and prints. */
export function PlatformInvoiceView({ tenantId, invoiceId }: { tenantId: string; invoiceId: string }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const invoice = useApiData(`platform:invoice:${tenantId}:${invoiceId}`, () =>
    platformBillingApi.invoice(tenantId, invoiceId),
  );
  const [pending, setPending] = useState<null | "pay" | "cancel">(null);
  const back = (
    <Link
      href={`/app/platform/schools/${encodeURIComponent(tenantId)}`}
      className="link no-print inline-flex items-center gap-1 text-[13.5px]"
    >
      <ArrowLeft size={16} aria-hidden="true" />
      {t("billing.invoice.backToAccount")}
    </Link>
  );
  const data = invoice.data;
  if (!data) {
    return <InvoicePageState error={invoice.error} loading={invoice.loading} back={back} onRetry={invoice.reload} />;
  }
  const open = data.status === "ISSUED";
  return (
    <>
      {back}
      <div className="page-head no-print">
        <div className="min-w-0">
          <p className="eyebrow">{data.buyer.name}</p>
          <h1 className="mt-1">{t("billing.invoice.titleNo", { number: data.invoiceNo })}</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          <PrintButton label={t("billing.invoice.print")} />
          {open ? (
            <button type="button" className="btn btn-primary" onClick={() => setPending("pay")}>
              <BadgeIndianRupee size={18} aria-hidden="true" />
              {t("billing.payment.button")}
            </button>
          ) : null}
          {open && data.paidPaise === 0 ? (
            <button type="button" className="btn" onClick={() => setPending("cancel")}>
              <XCircle size={18} aria-hidden="true" />
              {t("billing.cancel.button")}
            </button>
          ) : null}
        </div>
      </div>
      <section className="card">
        <InvoiceDocument invoice={data} />
      </section>
      {pending === "pay" ? (
        <PaymentDialog
          tenantId={tenantId}
          invoice={data}
          onClose={() => setPending(null)}
          onDone={(next) => {
            setPending(null);
            invoice.reload();
            toast(
              next.status === "PAID"
                ? t("billing.payment.donePaid", { number: next.invoiceNo })
                : t("billing.payment.done", { amount: formatPaise(next.balancePaise) }),
            );
          }}
        />
      ) : null}
      {pending === "cancel" ? (
        <CancelInvoiceDialog
          tenantId={tenantId}
          invoice={data}
          onClose={() => setPending(null)}
          onDone={() => {
            setPending(null);
            invoice.reload();
            toast(t("billing.cancel.done", { number: data.invoiceNo }));
          }}
        />
      ) : null}
    </>
  );
}
