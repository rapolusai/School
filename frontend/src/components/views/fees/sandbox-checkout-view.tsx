"use client";

import { CircleCheck, CircleX, FlaskConical } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AccessDenied } from "@/components/access";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { feesApi } from "@/lib/fees-api";
import { formatPaise } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { SandboxCheckout } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/**
 * The sandbox gateway's checkout page. It stands in for a real gateway's checkout: the payer
 * chooses Success or Failure, and on success the app verifies the signed result with the API
 * (the same step a real gateway's checkout ends with) and opens the receipt. No money moves.
 */
export function SandboxCheckoutView({ gatewayOrderId }: { gatewayOrderId: string }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const staff = hasPermission(me, PERMISSIONS.feesCollect);
  const allowed = staff || hasPermission(me, PERMISSIONS.childView);
  const checkout = useApiData(allowed ? `sandbox:${gatewayOrderId}` : null, () =>
    feesApi.sandboxCheckout(gatewayOrderId),
  );
  if (!allowed) return <AccessDenied />;
  const c = checkout.data;
  return (
    <div className="mx-auto flex w-full max-w-lg flex-col gap-4">
      <div className="flex items-center gap-2 text-ink-2">
        <FlaskConical size={18} aria-hidden="true" />
        <span className="eyebrow">{t("fees.sandbox.eyebrow")}</span>
      </div>
      <h1>{t("fees.sandbox.title")}</h1>
      {checkout.error && !c ? (
        <section className="card">
          {checkout.error.status === 404 ? (
            <p className="empty">{t("fees.sandbox.notFound")}</p>
          ) : (
            <ErrorState error={checkout.error} onRetry={checkout.reload} />
          )}
        </section>
      ) : !c ? (
        <section className="card">
          <LoadingRows rows={4} />
        </section>
      ) : (
        <CheckoutCard checkout={c} staff={staff} onChanged={checkout.reload} />
      )}
    </div>
  );
}

function CheckoutCard({
  checkout: c,
  staff,
  onChanged,
}: {
  checkout: SandboxCheckout;
  staff: boolean;
  onChanged: () => void;
}) {
  const { t } = useI18n();
  const router = useRouter();
  const [busy, setBusy] = useState<"SUCCESS" | "FAILURE" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [failed, setFailed] = useState(c.status === "FAILED");
  const backHref = staff
    ? `/app/fees/collect?student=${encodeURIComponent(c.studentId)}`
    : `/app/children/${encodeURIComponent(c.studentId)}/fees`;

  const complete = async (outcome: "SUCCESS" | "FAILURE") => {
    if (busy) return;
    setBusy(outcome);
    setError(null);
    try {
      const result = await feesApi.sandboxComplete(c.gatewayOrderId, outcome);
      if (result.status !== "SUCCESS" || !result.gatewayPaymentId || !result.signature) {
        setFailed(true);
        onChanged();
        return;
      }
      const proof = { gatewayPaymentId: result.gatewayPaymentId, signature: result.signature };
      const receipt = staff
        ? await feesApi.staffVerify(c.orderId, proof)
        : await feesApi.childVerify(c.studentId, c.orderId, proof);
      router.replace(
        staff
          ? `/app/fees/receipts/${encodeURIComponent(receipt.id)}`
          : `/app/children/${encodeURIComponent(c.studentId)}/fees/receipts/${encodeURIComponent(receipt.id)}?paid=1`,
      );
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="card flex flex-col gap-4 sandbox-card" data-testid="sandbox-checkout">
      <p className="alert alert-info">{t("fees.sandbox.note")}</p>
      <dl className="kv">
        <dt>{t("fees.sandbox.payee")}</dt>
        <dd>{c.schoolName}</dd>
        <dt>{t("fees.receipt.student")}</dt>
        <dd>{c.studentName}</dd>
        <dt>{t("fees.sandbox.for")}</dt>
        <dd>{c.instalments.join(", ")}</dd>
        <dt>{t("fees.sandbox.order")}</dt>
        <dd className="mono">{c.gatewayOrderId}</dd>
      </dl>
      <p className="kpi-value" data-testid="sandbox-amount">
        {formatPaise(c.amountPaise)}
      </p>
      <FormAlert message={error} />
      {c.status === "PAID" ? (
        <div className="flex flex-col items-start gap-3">
          <p className="font-semibold text-good">{t("fees.sandbox.alreadyPaid")}</p>
          <Link href={backHref} className="btn">
            {t("fees.sandbox.back")}
          </Link>
        </div>
      ) : failed ? (
        <div className="flex flex-col items-start gap-3" role="status">
          <p className="flex items-center gap-2 font-semibold text-bad">
            <CircleX size={18} aria-hidden="true" />
            {t("fees.sandbox.failed")}
          </p>
          <Link href={backHref} className="btn">
            {t("fees.sandbox.tryAgain")}
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
            {busy === "SUCCESS" ? t("common.working") : t("fees.sandbox.success")}
          </button>
          <button
            type="button"
            className="btn btn-lg flex-1"
            onClick={() => complete("FAILURE")}
            disabled={busy !== null}
          >
            <CircleX size={18} aria-hidden="true" />
            {busy === "FAILURE" ? t("common.working") : t("fees.sandbox.failure")}
          </button>
        </div>
      )}
    </section>
  );
}
