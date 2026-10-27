"use client";

import { CircleCheck, CircleX, CreditCard, FlaskConical } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AccessDenied } from "@/components/access";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { billingApi } from "@/lib/billing-api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS, type NavSubject } from "@/lib/permissions";
import { openRazorpayCheckout } from "@/lib/razorpay-checkout";
import type { SandboxInvoiceCheckout } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/** Paying an invoice online needs billing.read and settings.manage (School Admins). */
export function canPayOnline(me: NavSubject): boolean {
  return hasPermission(me, PERMISSIONS.billingRead) && hasPermission(me, PERMISSIONS.settingsManage);
}

export const invoiceHref = (invoiceId: string, paid = false) =>
  `/app/billing/invoices/${encodeURIComponent(invoiceId)}${paid ? "?paid=1" : ""}`;

/**
 * Starts an online payment of an invoice's whole balance: the API makes (or reuses) the order; with Razorpay its
 * checkout opens over the page and the API verifies the result; with the sandbox the app's own checkout page opens.
 * One payment at a time: repeated clicks while one is starting do nothing.
 */
export function usePayInvoice(onPaid: (invoiceNo: string) => void) {
  const { t } = useI18n();
  const router = useRouter();
  const [payingId, setPayingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const pay = async (invoiceId: string) => {
    if (payingId) return;
    setPayingId(invoiceId);
    setError(null);
    try {
      const order = await billingApi.payOnline(invoiceId);
      if (order.gateway !== "razorpay" || !order.checkoutKey) {
        router.push(`/app/billing/pay/sandbox/${encodeURIComponent(order.gatewayOrderId)}`);
        return;
      }
      let proof;
      try {
        proof = await openRazorpayCheckout({
          key: order.checkoutKey,
          gatewayOrderId: order.gatewayOrderId,
          amountPaise: order.amountPaise,
          currency: order.currency,
          name: order.payeeName,
          description: order.invoiceNo,
        });
      } catch {
        setError(t("billing.pay.unavailable"));
        setPayingId(null);
        return;
      }
      if (!proof) {
        setError(t("billing.pay.cancelled"));
        setPayingId(null);
        return;
      }
      const invoice = await billingApi.verify(order.id, proof);
      setPayingId(null);
      onPaid(invoice.invoiceNo);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
      setPayingId(null);
    }
  };

  return { pay, payingId, error };
}

/** The "Pay online" button of one open invoice. */
export function PayOnlineButton({
  invoiceId,
  invoiceNo,
  payingId,
  onPay,
  size = "sm",
}: {
  invoiceId: string;
  invoiceNo: string;
  payingId: string | null;
  onPay: (invoiceId: string) => void;
  size?: "sm" | "md";
}) {
  const { t } = useI18n();
  return (
    <button
      type="button"
      className={`btn btn-primary ${size === "sm" ? "btn-sm" : ""}`}
      onClick={() => onPay(invoiceId)}
      disabled={payingId !== null}
      aria-label={t("billing.pay.buttonFor", { number: invoiceNo })}
      data-testid={`pay-${invoiceId}`}
    >
      <CreditCard size={16} aria-hidden="true" />
      {payingId === invoiceId ? t("common.working") : t("billing.pay.button")}
    </button>
  );
}

/**
 * /app/billing/pay/sandbox/[orderId]: the sandbox gateway's checkout for a subscription invoice. It stands in for
 * Razorpay's window: the School Admin chooses Success or Failure; on success the app verifies the signed result with
 * the API and opens the paid invoice. No money moves.
 */
export function BillingSandboxCheckoutView({ gatewayOrderId }: { gatewayOrderId: string }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const allowed = canPayOnline(me);
  const checkout = useApiData(allowed ? `billing:sandbox:${gatewayOrderId}` : null, () =>
    billingApi.sandboxCheckout(gatewayOrderId),
  );
  if (!allowed) return <AccessDenied />;
  const c = checkout.data;
  return (
    <div className="mx-auto flex w-full max-w-lg flex-col gap-4">
      <div className="flex items-center gap-2 text-ink-2">
        <FlaskConical size={18} aria-hidden="true" />
        <span className="eyebrow">{t("billing.sandbox.eyebrow")}</span>
      </div>
      <h1>{t("billing.sandbox.title")}</h1>
      {checkout.error && !c ? (
        <section className="card">
          {checkout.error.status === 404 ? (
            <p className="empty">{t("billing.sandbox.notFound")}</p>
          ) : (
            <ErrorState error={checkout.error} onRetry={checkout.reload} />
          )}
        </section>
      ) : !c ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : (
        <CheckoutCard checkout={c} onChanged={checkout.reload} />
      )}
    </div>
  );
}

function CheckoutCard({ checkout: c, onChanged }: { checkout: SandboxInvoiceCheckout; onChanged: () => void }) {
  const { t } = useI18n();
  const router = useRouter();
  const [busy, setBusy] = useState<"SUCCESS" | "FAILURE" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [failed, setFailed] = useState(c.status === "FAILED");

  const complete = async (outcome: "SUCCESS" | "FAILURE") => {
    if (busy) return;
    setBusy(outcome);
    setError(null);
    try {
      const result = await billingApi.sandboxComplete(c.gatewayOrderId, outcome);
      if (result.status !== "SUCCESS" || !result.gatewayPaymentId || !result.signature) {
        setFailed(true);
        onChanged();
        return;
      }
      await billingApi.verify(c.orderId, { gatewayPaymentId: result.gatewayPaymentId, signature: result.signature });
      router.replace(invoiceHref(c.invoiceId, true));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="card flex flex-col gap-4 sandbox-card" data-testid="billing-sandbox-checkout">
      <p className="alert alert-info">{t("billing.sandbox.note")}</p>
      <dl className="kv">
        <dt>{t("billing.sandbox.payee")}</dt>
        <dd>{c.payeeName}</dd>
        <dt>{t("billing.sandbox.school")}</dt>
        <dd>{c.schoolName}</dd>
        <dt>{t("billing.sandbox.invoice")}</dt>
        <dd className="mono">{c.invoiceNo}</dd>
        <dt>{t("billing.sandbox.order")}</dt>
        <dd className="mono">{c.gatewayOrderId}</dd>
      </dl>
      <p className="kpi-value" data-testid="billing-sandbox-amount">
        {formatPaise(c.amountPaise)}
      </p>
      <FormAlert message={error} />
      {c.status === "PAID" ? (
        <div className="flex flex-col items-start gap-3">
          <p className="font-semibold text-good">{t("billing.sandbox.alreadyPaid")}</p>
          <Link href={invoiceHref(c.invoiceId)} className="btn">
            {t("billing.sandbox.back")}
          </Link>
        </div>
      ) : failed ? (
        <div className="flex flex-col items-start gap-3" role="status">
          <p className="flex items-center gap-2 font-semibold text-bad">
            <CircleX size={18} aria-hidden="true" />
            {t("billing.sandbox.failed")}
          </p>
          <Link href="/app/billing" className="btn">
            {t("billing.sandbox.back")}
          </Link>
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className="btn btn-primary btn-lg flex-1"
            onClick={() => complete("SUCCESS")}
            disabled={busy !== null}
          >
            <CircleCheck size={18} aria-hidden="true" />
            {busy === "SUCCESS" ? t("common.working") : t("billing.sandbox.success")}
          </button>
          <button
            type="button"
            className="btn btn-lg flex-1"
            onClick={() => complete("FAILURE")}
            disabled={busy !== null}
          >
            <CircleX size={18} aria-hidden="true" />
            {busy === "FAILURE" ? t("common.working") : t("billing.sandbox.failure")}
          </button>
        </div>
      )}
    </section>
  );
}
