"use client";

import { ArrowLeft, ArrowRight, CheckCircle2, CreditCard } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { feesApi } from "@/lib/fees-api";
import { classLabel, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import type { InstalmentDue, StudentFees } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ReceiptList } from "./collect-view";
import { DuesTable } from "./dues-table";
import { Amount, openInstalments, payableOf } from "./fee-ui";
import { PrintButton, ReceiptDocument } from "./receipt-document";

/** Instalments a parent is asked to pay now: overdue, due soon or part paid. */
export function payNowInstalments(fees: StudentFees): InstalmentDue[] {
  return openInstalments(fees.instalments).filter((i) => i.status !== "UPCOMING");
}

/** The next instalment still to pay that is not overdue yet. */
export function nextDue(fees: StudentFees): InstalmentDue | undefined {
  return openInstalments(fees.instalments).find((i) => i.status !== "OVERDUE");
}

/** Parent dashboard: a child's fees at a glance, inside the child's card. */
export function ChildFeesSummary({ childId }: { childId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const fees = useApiData(`me:child-fees:${childId}`, () => feesApi.childFees(childId));
  const data = fees.data;
  return (
    <div className="child-fees" data-testid="child-fees">
      <p className="text-[12px] font-semibold uppercase tracking-wide text-ink-3">{t("fees.child.title")}</p>
      {fees.error && !data ? (
        <p className="text-[13px] text-ink-3">{t("fees.child.unavailable")}</p>
      ) : !data ? (
        <div className="skeleton h-5 w-40" aria-hidden="true" />
      ) : data.instalments.length === 0 ? (
        <p className="text-[13px] text-ink-3">{t("fees.child.none")}</p>
      ) : (
        <>
          {data.totals.overduePaise > 0 ? (
            <p className="text-[13.5px] font-semibold text-bad">
              {t("fees.child.overdue", { amount: formatPaise(data.totals.overduePaise + data.totals.lateFeePaise) })}
            </p>
          ) : null}
          {(() => {
            const next = nextDue(data);
            return next ? (
              <p className="text-[13.5px]">
                {t("fees.child.next", {
                  label: next.label,
                  amount: formatPaise(next.balancePaise),
                  date: formatPlainDate(next.dueDate, locale),
                })}
              </p>
            ) : data.totals.balancePaise === 0 ? (
              <p className="text-[13.5px] text-good">{t("fees.child.allPaid")}</p>
            ) : null;
          })()}
          <Link href={`/app/children/${childId}/fees`} className="link inline-flex items-center gap-1 text-[13.5px]">
            {data.totals.payableNowPaise > 0 ? t("fees.child.payLink") : t("fees.child.viewLink")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </>
      )}
    </div>
  );
}

/** /app/children/[id]/fees: a parent's view of one child's dues and receipts, with "Pay now". */
export function ChildFeesView({ id }: { id: string }) {
  const { t } = useI18n();
  const router = useRouter();
  const fees = useApiData(`me:child-fees:${id}`, () => feesApi.childFees(id));
  const [picked, setPicked] = useState<ReadonlySet<string> | null>(null);
  const [paying, setPaying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const data = fees.data;

  const back = (
    <Link href="/app/dashboard" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("fees.child.back")}
    </Link>
  );

  if (fees.error && !data) {
    return (
      <>
        {back}
        <section className="card">
          {fees.error.status === 404 ? (
            <p className="empty">{t("fees.student.notFound")}</p>
          ) : (
            <ErrorState error={fees.error} onRetry={fees.reload} />
          )}
        </section>
      </>
    );
  }
  if (!data) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      </>
    );
  }

  const open = openInstalments(data.instalments);
  // Until the parent changes it, the instalments due now are ticked.
  const selected = picked ?? new Set(payNowInstalments(data).map((i) => i.instalmentId));
  const chosen = open.filter((i) => selected.has(i.instalmentId));
  const amount = chosen.reduce((sum, i) => sum + payableOf(i), 0);
  const s = data.student;

  const toggle = (instalmentId: string) => {
    const next = new Set(selected);
    if (next.has(instalmentId)) next.delete(instalmentId);
    else next.add(instalmentId);
    setPicked(next);
    setError(null);
  };

  const pay = async () => {
    if (!chosen.length || paying) return;
    setPaying(true);
    setError(null);
    try {
      const order = await feesApi.childOrder(
        id,
        chosen.map((i) => i.instalmentId),
      );
      router.push(`/app/pay/sandbox/${encodeURIComponent(order.gatewayOrderId)}`);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
      setPaying(false);
    }
  };

  return (
    <>
      {back}
      <PageHead
        eyebrow={[t("fees.child.title"), classLabel(s.className, s.sectionName)].filter(Boolean).join(" · ")}
        title={s.fullName}
      />
      <section className="card">
        <div className="amounts">
          <Amount label={t("fees.totals.net")} value={formatPaise(data.totals.netPaise)} />
          <Amount label={t("fees.totals.paid")} value={formatPaise(data.totals.paidPaise)} tone="good" />
          <Amount label={t("fees.totals.balance")} value={formatPaise(data.totals.balancePaise)} />
          <Amount
            label={t("fees.totals.overdue")}
            value={formatPaise(data.totals.overduePaise + data.totals.lateFeePaise)}
            tone={data.totals.overduePaise > 0 ? "bad" : undefined}
          />
        </div>
        {data.totals.concessionPaise > 0 ? (
          <p className="mt-3 text-[13px] text-ink-2">
            {t("fees.child.concession", { amount: formatPaise(data.totals.concessionPaise) })}
          </p>
        ) : null}
      </section>

      {data.instalments.length === 0 ? (
        <section className="card">
          <p className="empty">{t("fees.child.none")}</p>
        </section>
      ) : (
        <section className="card" aria-labelledby="child-dues">
          <div className="card-head">
            <div>
              <h2 id="child-dues">{t("fees.child.dues")}</h2>
              <p className="mt-1 text-sm text-ink-2">
                {open.length ? t("fees.child.pickHint") : t("fees.child.allPaid")}
              </p>
            </div>
          </div>
          <DuesTable
            caption={t("fees.child.dues")}
            instalments={data.instalments}
            selection={
              open.length
                ? { selected, onToggle: toggle, canSelect: (i) => i.balancePaise > 0 || i.lateFeePaise > 0 }
                : undefined
            }
          />
          {open.length ? (
            <div className="pay-bar">
              <FormAlert message={error} />
              <div className="flex flex-wrap items-center justify-between gap-3">
                <p className="text-sm text-ink-2">
                  {chosen.length ? t("fees.child.toPay", { amount: formatPaise(amount) }) : t("fees.child.pickOne")}
                </p>
                <button
                  type="button"
                  className="btn btn-primary btn-lg"
                  onClick={pay}
                  disabled={!chosen.length || paying}
                >
                  <CreditCard size={18} aria-hidden="true" />
                  {paying ? t("common.working") : t("fees.child.payNow", { amount: formatPaise(amount) })}
                </button>
              </div>
              <p className="text-[12.5px] text-ink-3">{t("fees.child.payNote")}</p>
            </div>
          ) : null}
        </section>
      )}

      <section className="card" aria-labelledby="child-receipts">
        <div className="card-head">
          <h2 id="child-receipts">{t("fees.child.receipts")}</h2>
        </div>
        {data.receipts.length === 0 ? (
          <p className="empty">{t("fees.child.noReceipts")}</p>
        ) : (
          <ReceiptList receipts={data.receipts} hrefFor={(r) => `/app/children/${id}/fees/receipts/${r.id}`} />
        )}
      </section>
    </>
  );
}

/** /app/children/[id]/fees/receipts/[receiptId]: a receipt the parent can print or save as PDF. */
export function ChildReceiptView({
  studentId,
  receiptId,
  justPaid,
}: {
  studentId: string;
  receiptId: string;
  justPaid: boolean;
}) {
  const { t } = useI18n();
  const receipt = useApiData(`me:child-receipt:${studentId}:${receiptId}`, () =>
    feesApi.childReceipt(studentId, receiptId),
  );
  const r = receipt.data;
  const back = (
    <Link
      href={`/app/children/${studentId}/fees`}
      className="link no-print inline-flex items-center gap-1 text-[13.5px]"
    >
      <ArrowLeft size={16} aria-hidden="true" />
      {t("fees.child.backToFees")}
    </Link>
  );
  if (receipt.error && !r) {
    return (
      <>
        {back}
        <section className="card">
          {receipt.error.status === 404 ? (
            <p className="empty">{t("fees.receipt.notFound")}</p>
          ) : (
            <ErrorState error={receipt.error} onRetry={receipt.reload} />
          )}
        </section>
      </>
    );
  }
  if (!r) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }
  return (
    <>
      {back}
      {justPaid ? (
        <section className="alert alert-good no-print" role="status" data-testid="payment-success">
          <CheckCircle2 size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>{t("fees.child.paid", { amount: formatPaise(r.amountPaise), number: r.receiptNo })}</span>
        </section>
      ) : null}
      <div className="page-head no-print">
        <div className="min-w-0">
          <p className="eyebrow">{r.studentName}</p>
          <h1 className="mt-1">{t("fees.receipt.titleNo", { number: r.receiptNo })}</h1>
        </div>
        <PrintButton label={t("fees.receipt.download")} />
      </div>
      <section className="card">
        <ReceiptDocument receipt={r} />
      </section>
    </>
  );
}
