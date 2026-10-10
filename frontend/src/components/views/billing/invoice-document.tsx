"use client";

import { formatDateTime, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import type { Invoice, Party } from "@/lib/types";
import { cycleLabel, InvoiceStatusPill, periodLabel, planLabel, rateLabel, stateLabel } from "./billing-ui";

function PartyBlock({ label, party }: { label: string; party: Party }) {
  const { t } = useI18n();
  return (
    <div className="min-w-0">
      <p className="text-[12px] font-semibold uppercase tracking-wide text-ink-3">{label}</p>
      <p className="font-semibold">{party.name}</p>
      {party.address ? <p className="whitespace-pre-line text-[13px] text-ink-2">{party.address}</p> : null}
      <p className="text-[13px] text-ink-2">{t("billing.invoice.stateLine", { state: stateLabel(t, party.stateCode) })}</p>
      <p className="text-[13px] text-ink-2">
        {party.gstin ? (
          <>
            {t("billing.invoice.gstinLabel")} <span className="mono">{party.gstin}</span>
          </>
        ) : (
          t("billing.invoice.unregistered")
        )}
      </p>
    </div>
  );
}

/**
 * Akshara's GST tax invoice to a school, as printed: seller and buyer with their states and GSTINs, the subscription
 * line with its SAC code, CGST + SGST or IGST, the total in figures and words, and the payments received. Only this
 * block is printed (see globals.css).
 */
export function InvoiceDocument({ invoice }: { invoice: Invoice }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const cancelled = invoice.status === "CANCELLED";
  const description = t("billing.invoice.line", {
    plan: planLabel(t, invoice.plan),
    cycle: cycleLabel(t, invoice.billingCycle).toLowerCase(),
  });

  return (
    <article
      className={`receipt invoice print-area${cancelled ? " receipt-cancelled" : ""}`}
      aria-label={t("billing.invoice.titleNo", { number: invoice.invoiceNo })}
      data-stamp={cancelled ? t("billing.invoiceStatus.CANCELLED") : undefined}
      data-testid="invoice"
    >
      <header className="receipt-head">
        <PartyBlock label={t("billing.invoice.seller")} party={invoice.seller} />
        <div className="receipt-title">
          <h2>{t("billing.invoice.title")}</h2>
          <InvoiceStatusPill status={invoice.status} overdue={invoice.overdue} />
        </div>
      </header>

      {invoice.sample ? (
        <div className="alert alert-info" role="note" data-testid="invoice-sample">
          <span>{t("billing.invoice.sample")}</span>
        </div>
      ) : null}

      <dl className="receipt-meta">
        <div>
          <dt>{t("billing.invoice.number")}</dt>
          <dd className="mono" data-testid="invoice-no">
            {invoice.invoiceNo}
          </dd>
        </div>
        <div>
          <dt>{t("billing.invoice.date")}</dt>
          <dd>{formatPlainDate(invoice.invoiceDate, locale)}</dd>
        </div>
        <div>
          <dt>{t("billing.invoice.dueDate")}</dt>
          <dd>{formatPlainDate(invoice.dueDate, locale)}</dd>
        </div>
        <div>
          <dt>{t("billing.invoice.financialYear")}</dt>
          <dd>{invoice.financialYear}</dd>
        </div>
        <div>
          <dt>{t("billing.invoice.placeOfSupply")}</dt>
          <dd>{stateLabel(t, invoice.buyer.stateCode)}</dd>
        </div>
        <div>
          <dt>{t("billing.invoice.sac")}</dt>
          <dd className="mono">{invoice.sacCode}</dd>
        </div>
      </dl>

      <PartyBlock label={t("billing.invoice.buyer")} party={invoice.buyer} />

      <div className="table-wrap">
        <table className="table receipt-lines">
          <thead>
            <tr>
              <th scope="col">{t("billing.invoice.description")}</th>
              <th scope="col" className="r">
                {t("billing.invoice.students")}
              </th>
              <th scope="col" className="r">
                {t("billing.invoice.rate")}
              </th>
              <th scope="col" className="r">
                {t("billing.invoice.amount")}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>
                <b>{description}</b>
                <span className="block text-[12.5px] text-ink-3">
                  {periodLabel(invoice.periodStart, invoice.periodEnd, locale)} ·{" "}
                  {t("billing.invoice.sacShort", { code: invoice.sacCode })}
                </span>
              </td>
              <td className="r num">{invoice.billedStudents.toLocaleString("en-IN")}</td>
              <td className="r num">{formatPaise(invoice.unitPricePaise)}</td>
              <td className="r num">{formatPaise(invoice.taxablePaise)}</td>
            </tr>
          </tbody>
          <tfoot className="invoice-totals">
            <tr>
              <th scope="row" colSpan={3}>
                {t("billing.invoice.taxable")}
              </th>
              <td className="r num">{formatPaise(invoice.taxablePaise)}</td>
            </tr>
            {invoice.taxSplit === "CGST_SGST" ? (
              <>
                <tr>
                  <th scope="row" colSpan={3}>
                    {t("billing.invoice.cgst", { rate: rateLabel(invoice.cgstRateBp) })}
                  </th>
                  <td className="r num">{formatPaise(invoice.cgstPaise)}</td>
                </tr>
                <tr>
                  <th scope="row" colSpan={3}>
                    {t("billing.invoice.sgst", { rate: rateLabel(invoice.sgstRateBp) })}
                  </th>
                  <td className="r num">{formatPaise(invoice.sgstPaise)}</td>
                </tr>
              </>
            ) : (
              <tr>
                <th scope="row" colSpan={3}>
                  {t("billing.invoice.igst", { rate: rateLabel(invoice.igstRateBp) })}
                </th>
                <td className="r num">{formatPaise(invoice.igstPaise)}</td>
              </tr>
            )}
            <tr className="invoice-grand">
              <th scope="row" colSpan={3}>
                {t("billing.invoice.total")}
              </th>
              <td className="r num font-bold" data-testid="invoice-total">
                {formatPaise(invoice.totalPaise)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>

      <p className="receipt-words">
        <span className="text-ink-3">{t("billing.invoice.inWords")}</span> {invoice.amountInWords}
      </p>

      {invoice.payments.length > 0 ? (
        <section aria-label={t("billing.invoice.payments")}>
          <h3 className="mb-2 text-[15px]">{t("billing.invoice.payments")}</h3>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">{t("billing.payment.paidOn")}</th>
                  <th scope="col">{t("billing.payment.mode")}</th>
                  <th scope="col">{t("billing.payment.reference")}</th>
                  <th scope="col" className="r">
                    {t("billing.invoice.amount")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {invoice.payments.map((payment) => (
                  <tr key={payment.id}>
                    <td className="whitespace-nowrap">{formatPlainDate(payment.paidOn, locale)}</td>
                    <td>{translateOr(t, `fees.mode.${payment.mode}`, payment.mode)}</td>
                    <td className="mono">{payment.reference}</td>
                    <td className="r num">{formatPaise(payment.amountPaise)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : null}

      <dl className="kv text-[13.5px]">
        <dt>{t("billing.invoice.paid")}</dt>
        <dd>{formatPaise(invoice.paidPaise)}</dd>
        <dt>{t("billing.invoice.balance")}</dt>
        <dd data-testid="invoice-balance">{formatPaise(invoice.balancePaise)}</dd>
        {invoice.paidOn ? (
          <>
            <dt>{t("billing.invoice.paidOn")}</dt>
            <dd>{formatPlainDate(invoice.paidOn, locale)}</dd>
          </>
        ) : null}
        <dt>{t("billing.invoice.issuedBy")}</dt>
        <dd>{invoice.issuedByName}</dd>
      </dl>

      {cancelled ? (
        <div className="alert alert-bad" role="note" data-testid="invoice-cancelled">
          <span>
            {t("billing.invoice.cancelledNote", {
              date: formatDateTime(invoice.cancelledAt, locale),
              name: invoice.cancelledByName ?? t("common.system"),
              reason: invoice.cancelReason ?? "",
            })}
          </span>
        </div>
      ) : null}

      <p className="receipt-foot">{t("billing.invoice.footer")}</p>
    </article>
  );
}
