"use client";

import { ArrowLeft, CalendarClock, CircleAlert, Pencil } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill, statusTone } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import type { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { billingApi } from "@/lib/billing-api";
import { formatDate, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { PLAN_MODULE_LABELS } from "@/lib/plan-access";
import type { BillingNotice, InvoiceSummary, PlanModule, SchoolBilling } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { PrintButton } from "../fees/receipt-document";
import {
  cycleLabel,
  DetailsDialog,
  DetailsList,
  InvoiceStatusPill,
  noticeText,
  periodLabel,
  PlanCards,
  planLabel,
} from "./billing-ui";
import { InvoiceDocument } from "./invoice-document";
import { canPayOnline, invoiceHref, PayOnlineButton, usePayInvoice } from "./pay-online";

/** The trial or overdue notice as an alert, for the Billing page and the shell banner. */
export function NoticeAlert({ notice, testId }: { notice: BillingNotice; testId?: string }) {
  const { t, lang } = useI18n();
  const text = noticeText(notice, t, localeFor(lang));
  if (!text) return null;
  const overdue = notice.kind === "PAYMENT_OVERDUE";
  const Icon = overdue ? CircleAlert : CalendarClock;
  return (
    <div className={`alert ${overdue ? "alert-bad" : "alert-warn"}`} role="status" data-testid={testId}>
      <Icon size={18} aria-hidden="true" className="mt-0.5 flex-none" />
      <span>{text}</span>
    </div>
  );
}

/** Invoices with links to their printable page; `action` adds a last column (the School Admin's "Pay online"). */
export function InvoiceTable({
  invoices,
  hrefFor,
  action,
}: {
  invoices: InvoiceSummary[];
  hrefFor: (id: string) => string;
  action?: (invoice: InvoiceSummary) => React.ReactNode;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  if (invoices.length === 0) {
    return (
      <p className="empty" data-testid="invoices-empty">
        {t("billing.invoices.empty")}
      </p>
    );
  }
  return (
    <div className="table-wrap">
      <table className="table" data-testid="invoices-table">
        <thead>
          <tr>
            <th scope="col">{t("billing.invoices.col.number")}</th>
            <th scope="col" className="hidden md:table-cell">
              {t("billing.invoices.col.period")}
            </th>
            <th scope="col" className="hidden sm:table-cell">
              {t("billing.invoices.col.due")}
            </th>
            <th scope="col" className="r">
              {t("billing.invoices.col.total")}
            </th>
            <th scope="col" className="r hidden lg:table-cell">
              {t("billing.invoices.col.balance")}
            </th>
            <th scope="col">{t("billing.invoices.col.status")}</th>
            {action ? <th scope="col" className="r"><span className="sr-only">{t("billing.pay.button")}</span></th> : null}
          </tr>
        </thead>
        <tbody>
          {invoices.map((invoice) => (
            <tr key={invoice.id}>
              <td>
                <Link href={hrefFor(invoice.id)} className="link mono whitespace-nowrap">
                  {invoice.invoiceNo}
                </Link>
                <span className="sub block text-[12.5px] text-ink-3">
                  {formatPlainDate(invoice.invoiceDate, locale)}
                </span>
              </td>
              <td className="hidden whitespace-nowrap text-ink-2 md:table-cell">
                {periodLabel(invoice.periodStart, invoice.periodEnd, locale)}
              </td>
              <td className="hidden whitespace-nowrap text-ink-2 sm:table-cell">
                {formatPlainDate(invoice.dueDate, locale)}
              </td>
              <td className="r num">{formatPaise(invoice.totalPaise)}</td>
              <td className="r num hidden lg:table-cell">{formatPaise(invoice.balancePaise)}</td>
              <td>
                <InvoiceStatusPill status={invoice.status} overdue={invoice.overdue} />
              </td>
              {action ? <td className="r">{action(invoice)}</td> : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PlanSummary({ billing }: { billing: SchoolBilling }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const sub = billing.subscription;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="billing-plan">
      <div className="card-head mb-0">
        <h2 id="billing-plan">{t("billing.school.plan")}</h2>
        <Pill tone={statusTone(billing.status)} dot>
          {translateOr(t, `status.${billing.status}`, billing.status)}
        </Pill>
      </div>
      <p className="kpi-value" data-testid="billing-plan-name">
        {planLabel(t, billing.plan)}
      </p>
      <dl className="kv">
        {billing.status === "TRIAL" && billing.trialEndsAt ? (
          <>
            <dt>{t("billing.school.trialEnds")}</dt>
            <dd data-testid="billing-trial">
              {formatDate(billing.trialEndsAt, locale)}
              {billing.trialDaysLeft !== null && billing.trialDaysLeft > 0
                ? ` · ${plural(t, "dashboard.trial.daysLeft", billing.trialDaysLeft)}`
                : ""}
            </dd>
          </>
        ) : null}
        {sub ? (
          <>
            <dt>{t("billing.subscription.cycle")}</dt>
            <dd>{cycleLabel(t, sub.billingCycle)}</dd>
            <dt>{t("billing.subscription.students")}</dt>
            <dd>{sub.billedStudents.toLocaleString("en-IN")}</dd>
            <dt>{t("billing.subscription.period")}</dt>
            <dd>{periodLabel(sub.periodStart, sub.periodEnd, locale)}</dd>
            <dt>{t("billing.subscription.renewal")}</dt>
            <dd data-testid="billing-renewal">{formatPlainDate(sub.nextRenewalOn, locale)}</dd>
            <dt>{t("billing.subscription.nextInvoice")}</dt>
            <dd>
              {t("billing.subscription.nextInvoiceValue", {
                amount: formatPaise(sub.nextInvoiceTaxablePaise),
                students: sub.billedStudents.toLocaleString("en-IN"),
                price: formatPaise(sub.unitPricePaise),
              })}
            </dd>
          </>
        ) : null}
        <dt>{t("billing.school.activeStudents")}</dt>
        <dd data-testid="billing-active-students">{studentsOnRoll(t, billing)}</dd>
      </dl>
      <p className="text-[13px] text-ink-3">{t("billing.school.contact")}</p>
    </section>
  );
}

/** Students on roll, against the plan's limit when it has one ("280 of 300"). */
function studentsOnRoll(t: ReturnType<typeof useI18n>["t"], billing: SchoolBilling): string {
  const active = billing.activeStudents.toLocaleString("en-IN");
  const max = billing.plans.find((p) => p.plan === billing.plan)?.maxStudents ?? null;
  return max === null ? active : t("billing.limits.students", { active, max: max.toLocaleString("en-IN") });
}

/**
 * /app/billing: a School Admin's view of the school's plan, trial, renewal, invoices and billing details. `upgrade`
 * names the menu module a locked item led here from.
 */
export function SchoolBillingView({ upgrade = null }: { upgrade?: PlanModule | null } = {}) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canEdit = hasPermission(me, PERMISSIONS.settingsManage);
  const canPay = canPayOnline(me);
  const billing = useApiData("billing:mine", billingApi.mine);
  const payment = usePayInvoice((number) => {
    billing.reload();
    toast(t("billing.pay.done", { number }));
  });
  const [editing, setEditing] = useState(false);
  const data = billing.data;
  const schoolName = me?.tenant?.name ?? "";

  return (
    <>
      <PageHead eyebrow={t("billing.eyebrow")} title={t("billing.school.title")} />
      {billing.error && !data ? (
        <section className="card">
          <ErrorState error={billing.error} onRetry={billing.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      ) : (
        <div className="flex flex-col gap-4">
          <NoticeAlert notice={data.notice} testId="billing-notice" />
          {upgrade ? (
            <div className="alert alert-warn" role="status" data-testid="billing-upgrade-notice">
              <CircleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <span>{t("billing.limits.lockedNotice", { module: t(PLAN_MODULE_LABELS[upgrade]) })}</span>
            </div>
          ) : null}
          <div className="grid gap-4 lg:grid-cols-2">
            <PlanSummary billing={data} />
            <section className="card flex flex-col gap-3" aria-labelledby="billing-details-title">
              <div className="card-head mb-0">
                <h2 id="billing-details-title">{t("billing.details.heading")}</h2>
                {canEdit ? (
                  <button type="button" className="btn btn-sm" onClick={() => setEditing(true)}>
                    <Pencil size={16} aria-hidden="true" />
                    {t("common.edit")}
                  </button>
                ) : null}
              </div>
              <DetailsList details={data.details} schoolName={schoolName} />
              <p className="text-[13px] text-ink-3">{t("billing.details.futureOnly")}</p>
            </section>
          </div>
          <section className="card" aria-labelledby="billing-invoices">
            <div className="card-head">
              <h2 id="billing-invoices">{t("billing.invoices.title")}</h2>
            </div>
            <FormAlert message={payment.error} />
            <InvoiceTable
              invoices={data.invoices}
              hrefFor={(id) => invoiceHref(id)}
              action={
                canPay
                  ? (invoice) =>
                      isPayable(invoice) ? (
                        <PayOnlineButton
                          invoiceId={invoice.id}
                          invoiceNo={invoice.invoiceNo}
                          payingId={payment.payingId}
                          onPay={payment.pay}
                        />
                      ) : null
                  : undefined
              }
            />
            {canPay && data.invoices.some(isPayable) ? (
              <p className="mt-3 text-[13px] text-ink-3">{t("billing.pay.note")}</p>
            ) : null}
          </section>
          <section aria-labelledby="billing-plans" className="flex flex-col gap-3">
            <h2 id="billing-plans">{t("billing.plans.title")}</h2>
            <PlanCards plans={data.plans} current={data.plan} />
            <p className="text-[13px] text-ink-3">{t("billing.plans.gstNote")}</p>
          </section>
        </div>
      )}
      {editing && data ? (
        <DetailsDialog
          details={data.details}
          schoolName={schoolName}
          onClose={() => setEditing(false)}
          onSave={async (body) => {
            await billingApi.updateDetails(body);
            billing.reload();
            toast(t("billing.details.saved"));
          }}
        />
      ) : null}
    </>
  );
}

/** Not found, error and loading states shared by both invoice pages. */
export function InvoicePageState({
  error,
  loading,
  back,
  onRetry,
}: {
  error: ApiError | undefined;
  loading: boolean;
  back: React.ReactNode;
  onRetry: () => void;
}) {
  const { t } = useI18n();
  return (
    <>
      {back}
      <section className="card">
        {error ? (
          error.status === 404 ? (
            <p className="empty">{t("billing.invoice.notFound")}</p>
          ) : (
            <ErrorState error={error} onRetry={onRetry} />
          )
        ) : loading ? (
          <LoadingRows rows={6} />
        ) : null}
      </section>
    </>
  );
}

/** An invoice the school can still pay online: issued, with something left to pay. */
export function isPayable(invoice: Pick<InvoiceSummary, "status" | "balancePaise">): boolean {
  return invoice.status === "ISSUED" && invoice.balancePaise > 0;
}

/** /app/billing/invoices/[id]: one of the school's invoices, ready to print, with "Pay online" while it is open. */
export function SchoolInvoiceView({ id, paid = false }: { id: string; paid?: boolean }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const invoice = useApiData(`billing:invoice:${id}`, () => billingApi.invoice(id));
  const payment = usePayInvoice((number) => {
    invoice.reload();
    toast(t("billing.pay.done", { number }));
  });
  const back = (
    <Link href="/app/billing" className="link no-print inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("billing.invoice.backToBilling")}
    </Link>
  );
  const data = invoice.data;
  if (!data) {
    return <InvoicePageState error={invoice.error} loading={invoice.loading} back={back} onRetry={invoice.reload} />;
  }
  return (
    <>
      {back}
      <div className="page-head no-print">
        <div className="min-w-0">
          <p className="eyebrow">{t("billing.eyebrow")}</p>
          <h1 className="mt-1">{t("billing.invoice.titleNo", { number: data.invoiceNo })}</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          {canPayOnline(me) && isPayable(data) ? (
            <PayOnlineButton
              invoiceId={data.id}
              invoiceNo={data.invoiceNo}
              payingId={payment.payingId}
              onPay={payment.pay}
              size="md"
            />
          ) : null}
          <PrintButton label={t("billing.invoice.print")} />
        </div>
      </div>
      <FormAlert message={payment.error} />
      {paid && data.status === "PAID" ? (
        <p className="alert alert-good no-print" role="status" data-testid="invoice-paid-banner">
          {t("billing.pay.paidBanner")}
        </p>
      ) : null}
      <section className="card">
        <InvoiceDocument invoice={data} />
      </section>
    </>
  );
}
