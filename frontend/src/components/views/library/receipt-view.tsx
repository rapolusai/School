"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatDateTime, formatPaise } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import type { FineReceipt } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { fineKindLabel, memberName, memberSub, modeLabel } from "./library-shared";

/** /app/library/receipt?no=…: a library fine receipt, to print or save as PDF. */
export function LibraryReceiptView({ receiptNo }: { receiptNo: string }) {
  const { t } = useI18n();
  const receipt = useApiData(receiptNo ? `library:receipt:${receiptNo}` : null, () => libraryApi.receipt(receiptNo));
  return (
    <>
      <PageHead
        eyebrow={t("nav.library")}
        title={t("library.receipt.title")}
        actions={
          <>
            <Link href="/app/library/fines" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("library.receipt.back")}
            </Link>
            <button type="button" className="btn btn-primary" onClick={() => window.print()} disabled={!receipt.data}>
              <Printer size={18} aria-hidden="true" />
              {t("library.receipt.print")}
            </button>
          </>
        }
      />
      {!receiptNo || receipt.error?.status === 404 ? (
        <p className="empty">{t("library.receipt.notFound")}</p>
      ) : receipt.error && !receipt.data ? (
        <ErrorState error={receipt.error} onRetry={receipt.reload} />
      ) : !receipt.data ? (
        <LoadingRows rows={5} />
      ) : (
        <section className="card">
          <ReceiptDocument receipt={receipt.data} />
        </section>
      )}
    </>
  );
}

export function ReceiptDocument({ receipt }: { receipt: FineReceipt }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <article className="receipt print-area" aria-label={t("library.receipt.titleNo", { number: receipt.receiptNo })} data-testid="library-receipt">
      <header className="receipt-head">
        <div className="min-w-0">
          <p className="receipt-school">{receipt.schoolName}</p>
          <p className="text-[13px] text-ink-2">{t("library.receipt.library")}</p>
        </div>
        <div className="receipt-title">
          <h2>{t("library.receipt.title")}</h2>
        </div>
      </header>
      <dl className="receipt-meta">
        <div>
          <dt>{t("library.receipt.number")}</dt>
          <dd className="mono" data-testid="library-receipt-no">
            {receipt.receiptNo}
          </dd>
        </div>
        <div>
          <dt>{t("library.receipt.paidAt")}</dt>
          <dd>{formatDateTime(receipt.paidAt, locale)}</dd>
        </div>
        <div>
          <dt>{t("library.receipt.member")}</dt>
          <dd>
            {memberName(t, receipt.member)}
            <span className="block text-[12.5px] font-normal text-ink-3">{memberSub(receipt.member)}</span>
          </dd>
        </div>
      </dl>
      <table className="table receipt-lines">
        <thead>
          <tr>
            <th scope="col" className="w-10">
              {t("library.receipt.sn")}
            </th>
            <th scope="col">{t("library.receipt.particulars")}</th>
            <th scope="col" className="r">
              {t("library.col.amount")}
            </th>
          </tr>
        </thead>
        <tbody>
          {receipt.fines.map((fine, index) => (
            <tr key={fine.id}>
              <td className="num">{index + 1}</td>
              <td>
                {fineKindLabel(t, fine.kind)} · {fine.title ?? "—"} (<span className="mono">{fine.accessionNo}</span>)
                {fine.daysLate ? ` · ${plural(t, "library.loan.daysOverdue", fine.daysLate)}` : ""}
              </td>
              <td className="r num">{formatPaise(fine.amountPaise)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan={2}>
              {t("library.receipt.total")}
            </th>
            <td className="r num font-bold" data-testid="library-receipt-total">
              {formatPaise(receipt.totalPaise)}
            </td>
          </tr>
        </tfoot>
      </table>
      <dl className="kv text-[13.5px]">
        <dt>{t("library.receipt.paidBy")}</dt>
        <dd>{[modeLabel(t, receipt.mode), receipt.reference].filter(Boolean).join(" · ")}</dd>
        <dt>{t("library.receipt.receivedBy")}</dt>
        <dd>{receipt.collectedByName ?? "—"}</dd>
      </dl>
      <p className="receipt-foot">{t("library.receipt.foot")}</p>
    </article>
  );
}
