"use client";

import { Download, Printer, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { downloadPayrollFile, payrollApi, payrollPaths } from "@/lib/payroll-api";
import type { BankAdvice } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { BackLink, PayrollNav, useMonthLabel } from "./payroll-ui";

/**
 * /app/payroll/runs/[id]/bank-advice: the covering letter to the school's bank for the month's salary transfer, with
 * the staff accounts masked (the CSV file carries them in full). Printed on A4 and signed by hand.
 */
export function BankAdviceView({ runId }: { runId: string }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const monthLabel = useMonthLabel();
  const advice = useApiData(`payroll:bank-advice:${runId}`, () => payrollApi.bankAdvice(runId));
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const a = advice.data;

  const download = async () => {
    if (!a) return;
    setDownloading(true);
    setError(null);
    try {
      const name = await downloadPayrollFile(payrollPaths.bankFile(runId), `bank-transfer-${a.month}.csv`);
      toast(t("payroll.files.downloaded", { name }));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <>
      <BackLink
        href={`/app/payroll/runs/${runId}`}
        label={a ? t("payslip.backToRun", { month: monthLabel(a.month) }) : t("payroll.run.back")}
      />
      <PageHead
        eyebrow={t("payroll.eyebrow")}
        title={t("advice.title")}
        actions={
          a ? (
            <>
              <button type="button" className="btn" onClick={download} disabled={downloading}>
                <Download size={18} aria-hidden="true" />
                {downloading ? t("common.working") : t("payroll.files.bank")}
              </button>
              <button type="button" className="btn" onClick={() => window.print()}>
                <Printer size={18} aria-hidden="true" />
                {t("advice.print")}
              </button>
            </>
          ) : null
        }
      />
      <PayrollNav />
      <FormAlert message={error} />
      <section className="card">
        {advice.error && !a ? (
          advice.error.status === 404 ? (
            <p className="empty">{t("payroll.run.notFound")}</p>
          ) : advice.error.status === 409 ? (
            <p className="empty">{t("advice.notApproved")}</p>
          ) : (
            <ErrorState error={advice.error} onRetry={advice.reload} />
          )
        ) : !a ? (
          <LoadingRows rows={6} />
        ) : (
          <AdviceLetter advice={a} />
        )}
      </section>
    </>
  );
}

export function AdviceLetter({ advice }: { advice: BankAdvice }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const monthLabel = useMonthLabel();
  const school = advice.school;
  const bank = [advice.bankName, advice.bankBranch].filter(Boolean).join(", ");
  return (
    <article className="receipt print-area bank-advice" data-testid="bank-advice">
      <header className="receipt-head">
        <div className="min-w-0">
          <p className="receipt-school">{school.name}</p>
          {school.address || school.city ? (
            <p className="text-[13px] text-ink-2">{[school.address, school.city].filter(Boolean).join(", ")}</p>
          ) : null}
          <p className="text-[12.5px] text-ink-3">{[school.phone, school.email].filter(Boolean).join(" · ")}</p>
        </div>
        <div className="receipt-title">
          <p>{t("advice.date", { date: formatPlainDate(todayInIndia(), locale) })}</p>
        </div>
      </header>

      <div className="flex flex-col gap-1 text-[14px]">
        <p>{t("advice.to")}</p>
        <p className="font-semibold">{bank || t("advice.bankNotSet")}</p>
      </div>
      <p className="font-semibold">{t("advice.subject", { month: monthLabel(advice.month) })}</p>
      <p className="text-[14px]">
        {advice.debitAccountNumber
          ? t("advice.body", {
              account: advice.debitAccountNumber,
              ifsc: advice.debitIfsc ?? "—",
              amount: formatPaise(advice.totalPaise),
              count: advice.count,
            })
          : t("advice.bodyNoAccount", { amount: formatPaise(advice.totalPaise), count: advice.count })}
      </p>
      <p className="receipt-words">
        <span className="text-ink-3">{t("payslip.inWords")}</span> {advice.totalInWords}
      </p>

      <div className="table-wrap">
        <table className="table receipt-lines" data-testid="bank-advice-table">
          <thead>
            <tr>
              <th scope="col" className="w-10">
                {t("advice.sn")}
              </th>
              <th scope="col">{t("advice.beneficiary")}</th>
              <th scope="col">{t("advice.account")}</th>
              <th scope="col">{t("advice.ifsc")}</th>
              <th scope="col" className="r">
                {t("payslip.amount")}
              </th>
            </tr>
          </thead>
          <tbody>
            {advice.rows.map((row, index) => (
              <tr key={`${row.employeeCode ?? row.name}-${index}`}>
                <td className="num">{index + 1}</td>
                <td>
                  {row.name}
                  {row.employeeCode ? <span className="block text-[12px] text-ink-3">{row.employeeCode}</span> : null}
                </td>
                <td className="mono">{row.accountMasked}</td>
                <td className="mono">{row.ifsc}</td>
                <td className="r num">{formatPaise(row.amountPaise)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colSpan={4}>
                {t("advice.total", { count: advice.count })}
              </th>
              <td className="r num font-bold">{formatPaise(advice.totalPaise)}</td>
            </tr>
          </tfoot>
        </table>
      </div>

      {advice.withoutBank.length > 0 ? (
        <div className="alert alert-warn" role="note" data-testid="bank-advice-missing">
          <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>
            {t("advice.withoutBank")}{" "}
            {advice.withoutBank.map((p) => (p.employeeCode ? `${p.name} (${p.employeeCode})` : p.name)).join(", ")}
          </span>
        </div>
      ) : null}

      <p className="text-[13px] text-ink-2">{t("advice.attached")}</p>
      <div className="bank-advice-sign">
        <span>{t("advice.signatory")}</span>
        <span>{school.name}</span>
      </div>
    </article>
  );
}
