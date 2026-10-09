"use client";

import { Printer } from "lucide-react";
import { formatDateTime, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import type { Receipt, ReceiptLine } from "@/lib/types";
import { ReceiptStatusPill, useModeLabel } from "./fee-ui";

/** "Quarter 1 · Tuition fee", "Late fee · Quarter 2", "Advance". */
export function lineLabel(line: ReceiptLine, t: (key: "fees.receipt.lateFee" | "fees.receipt.advance") => string) {
  if (line.kind === "LATE_FEE") return [t("fees.receipt.lateFee"), line.instalmentLabel].filter(Boolean).join(" · ");
  if (line.kind === "ADVANCE") return t("fees.receipt.advance");
  return [line.instalmentLabel, line.headName].filter(Boolean).join(" · ");
}

/** Opens the browser's print dialog, where the receipt can also be saved as a PDF. */
export function PrintButton({ label }: { label?: string }) {
  const { t } = useI18n();
  return (
    <button type="button" className="btn" onClick={() => window.print()}>
      <Printer size={18} aria-hidden="true" />
      {label ?? t("fees.receipt.print")}
    </button>
  );
}

/**
 * A fee receipt as printed: the school's header, the student, every line paid, the total in
 * figures and in words, and how it was paid. Only this block is printed (see globals.css).
 */
export function ReceiptDocument({ receipt }: { receipt: Receipt }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  const school = receipt.school;
  const cancelled = receipt.status === "CANCELLED";
  const payment = [
    modeLabel(receipt.mode),
    receipt.chequeNo ? t("fees.receipt.cheque", { number: receipt.chequeNo, bank: receipt.bankName ?? "" }) : null,
    receipt.reference ? t("fees.receipt.reference", { reference: receipt.reference }) : null,
    receipt.gatewayPaymentId ? t("fees.receipt.paymentId", { id: receipt.gatewayPaymentId }) : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <article
      className={`receipt print-area${cancelled ? " receipt-cancelled" : ""}`}
      aria-label={t("fees.receipt.titleNo", { number: receipt.receiptNo })}
      data-stamp={cancelled ? t("fees.receiptStatus.CANCELLED") : undefined}
      data-testid="receipt"
    >
      <header className="receipt-head">
        <div className="min-w-0">
          <p className="receipt-school">{school.name}</p>
          {school.address ? <p className="text-[13px] text-ink-2">{school.address}</p> : null}
          <p className="text-[12.5px] text-ink-3">
            {[
              school.phone ? t("fees.receipt.phone", { phone: school.phone }) : null,
              school.contactEmail,
              school.udiseCode ? t("fees.receipt.udise", { code: school.udiseCode }) : null,
              translateOr(t, `board.${school.board}`, school.board),
            ]
              .filter(Boolean)
              .join(" · ")}
          </p>
        </div>
        <div className="receipt-title">
          <h2>{t("fees.receipt.title")}</h2>
          <ReceiptStatusPill status={receipt.status} />
        </div>
      </header>

      <dl className="receipt-meta">
        <div>
          <dt>{t("fees.receipt.number")}</dt>
          <dd className="mono" data-testid="receipt-no">
            {receipt.receiptNo}
          </dd>
        </div>
        <div>
          <dt>{t("fees.receipt.date")}</dt>
          <dd>{formatPlainDate(receipt.receivedOn, locale)}</dd>
        </div>
        <div>
          <dt>{t("fees.receipt.student")}</dt>
          <dd>{receipt.studentName}</dd>
        </div>
        <div>
          <dt>{t("fees.receipt.admissionNo")}</dt>
          <dd className="mono">{receipt.admissionNo}</dd>
        </div>
        <div>
          <dt>{t("fees.receipt.class")}</dt>
          <dd>{receipt.classLabel || "—"}</dd>
        </div>
        <div>
          <dt>{t("fees.receipt.financialYear")}</dt>
          <dd>{receipt.financialYear}</dd>
        </div>
      </dl>

      <table className="table receipt-lines">
        <thead>
          <tr>
            <th scope="col" className="w-10">
              {t("fees.receipt.sn")}
            </th>
            <th scope="col">{t("fees.receipt.particulars")}</th>
            <th scope="col" className="r">
              {t("fees.receipt.amount")}
            </th>
          </tr>
        </thead>
        <tbody>
          {receipt.lines.map((line, index) => (
            <tr key={index}>
              <td className="num">{index + 1}</td>
              <td>{lineLabel(line, t)}</td>
              <td className="r num">{formatPaise(line.amountPaise)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan={2}>
              {t("fees.receipt.total")}
            </th>
            <td className="r num font-bold">{formatPaise(receipt.amountPaise)}</td>
          </tr>
        </tfoot>
      </table>

      <p className="receipt-words">
        <span className="text-ink-3">{t("fees.receipt.inWords")}</span> {receipt.amountInWords}
      </p>

      <dl className="kv text-[13.5px]">
        <dt>{t("fees.receipt.paidBy")}</dt>
        <dd>{payment}</dd>
        <dt>{t("fees.receipt.receivedAt")}</dt>
        <dd>{formatDateTime(receipt.receivedAt, locale)}</dd>
        <dt>{t("fees.receipt.receivedBy")}</dt>
        <dd>{receipt.source === "ONLINE" ? t("fees.receipt.online") : (receipt.collectedByName ?? "—")}</dd>
        {receipt.remarks ? (
          <>
            <dt>{t("fees.receipt.remarks")}</dt>
            <dd>{receipt.remarks}</dd>
          </>
        ) : null}
      </dl>

      {cancelled ? (
        <div className="alert alert-bad" role="note" data-testid="receipt-cancelled">
          <span>
            {t("fees.receipt.cancelledNote", {
              date: formatDateTime(receipt.cancelledAt, locale),
              name: receipt.cancelledByName ?? t("common.system"),
              reason: receipt.cancelReason ?? "",
            })}
          </span>
        </div>
      ) : null}

      <p className="receipt-foot">{t("fees.receipt.footer")}</p>
    </article>
  );
}
