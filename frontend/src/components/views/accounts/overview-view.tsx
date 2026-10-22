"use client";

import { ArrowRight, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { financialYearOfDate } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { formatMonth } from "@/lib/payroll";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { useApiData } from "@/lib/use-api-data";
import { AccountsNav, ManagementNote, PeriodFilter, Stat, YearFilter } from "./accounts-ui";

/**
 * /app/accounts: this month at a glance (income, expenses, cash and bank), the approval queue, the fee income
 * reconciliation against the fees module, and the payroll cost per month.
 */
export function AccountsOverview() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const canRead = hasPermission(me, PERMISSIONS.accountsRead);
  const canApprove = hasPermission(me, PERMISSIONS.accountsApprove);
  const year = financialYearOfDate(today);
  const month = today.slice(0, 7);
  const position = useApiData(canRead ? "accounts:cash-position" : null, accountsApi.cashPosition);
  const trend = useApiData(canRead ? `accounts:ive:${year}` : null, () => accountsApi.incomeVsExpense({ financialYear: year }));
  const queue = useApiData(canApprove ? "accounts:queue" : null, accountsApi.queue);
  const thisMonth = trend.data?.months.find((m) => m.month === month);
  const loading = (canRead && (!position.data || !trend.data)) || (canApprove && !queue.data);
  const error = position.error ?? trend.error ?? queue.error;

  return (
    <>
      <PageHead eyebrow={t("accounts.eyebrow")} title={t("accounts.overview.title")} />
      <AccountsNav />
      <ManagementNote />
      {error && loading ? (
        <ErrorState
          error={error}
          onRetry={() => {
            position.reload();
            trend.reload();
            queue.reload();
          }}
        />
      ) : loading ? (
        <LoadingRows rows={4} />
      ) : (
        <ul className="rep-kpis" data-testid="overview-figures">
          {thisMonth ? (
            <>
              <Stat label={t("accounts.overview.income", { month: formatMonth(month, locale) })} value={formatPaise(thisMonth.incomePaise)} sub={t("accounts.overview.feesPart", { amount: formatPaise(thisMonth.feeIncomePaise) })} testId="overview-income" />
              <Stat label={t("accounts.overview.expense", { month: formatMonth(month, locale) })} value={formatPaise(thisMonth.totalExpensePaise)} sub={t("accounts.overview.payrollPart", { amount: formatPaise(thisMonth.payrollPaise) })} testId="overview-expense" />
              <Stat label={t("accounts.overview.net")} value={formatPaise(thisMonth.netPaise)} tone={thisMonth.netPaise < 0 ? "bad" : "good"} testId="overview-net" />
            </>
          ) : null}
          {position.data ? (
            <>
              <Stat label={t("accounts.bank.cashInHand")} value={formatPaise(position.data.cashPaise + position.data.pettyCashPaise)} testId="overview-cash" />
              <Stat label={t("accounts.bank.inBank")} value={formatPaise(position.data.bankPaise)} testId="overview-bank" />
            </>
          ) : null}
          {queue.data ? (
            <Stat label={t("accounts.overview.awaiting")} value={String(queue.data.expenses.length)} sub={formatPaise(queue.data.expenses.reduce((a, e) => a + e.totalPaise, 0))} attention={queue.data.expenses.length > 0} testId="overview-awaiting" />
          ) : null}
        </ul>
      )}
      <div className="flex flex-wrap gap-2">
        <Link href="/app/accounts/expenses" className="btn">
          {t("accounts.nav.expenses")}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
        {canApprove ? (
          <Link href="/app/accounts/approvals" className="btn" data-testid="overview-approvals">
            {t("accounts.nav.approvals")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        ) : null}
        <Link href="/app/reports/accounts-income-vs-expense" className="btn">
          {t("accounts.report.incomeVsExpense.title")}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      </div>
      {canRead ? (
        <>
          <FeeReconciliationCard />
          <PayrollCostsCard />
        </>
      ) : null}
    </>
  );
}

/** The fee income the accounts recorded per day against what the fees module reports, with differences flagged. */
function FeeReconciliationCard() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const [from, setFrom] = useState(`${today.slice(0, 7)}-01`);
  const [to, setTo] = useState(today);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recon = useApiData(`accounts:fee-recon:${from}:${to}`, () => accountsApi.feeReconciliation({ from, to }));
  const data = recon.data;
  const rebuild = async () => {
    setBusy(true);
    setError(null);
    try {
      await accountsApi.rebuildFeeIncome({ from, to });
      toast(t("accounts.feeRecon.rebuilt"));
      recon.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card" aria-labelledby="fee-recon-title">
      <div className="card-head">
        <div>
          <h2 id="fee-recon-title">{t("accounts.feeRecon.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("accounts.feeRecon.sub")}</p>
        </div>
        {canManage ? (
          <button type="button" className="btn" onClick={rebuild} disabled={busy} data-testid="fee-recon-rebuild">
            <RefreshCw size={16} aria-hidden="true" />
            {busy ? t("common.working") : t("accounts.feeRecon.rebuild")}
          </button>
        ) : null}
      </div>
      <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
        <PeriodFilter
          from={from}
          to={to}
          onChange={(f, tt) => {
            setFrom(f);
            setTo(tt);
          }}
        />
      </form>
      <FormAlert message={error} />
      {recon.error && !data ? (
        <ErrorState error={recon.error} onRetry={recon.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : (
        <>
          <ul className="rep-kpis mb-4" data-testid="fee-recon-figures">
            <Stat label={t("accounts.feeRecon.accounts")} value={formatPaise(data.accountsPaise)} />
            <Stat label={t("accounts.feeRecon.fees")} value={formatPaise(data.feesPaise)} />
            <Stat label={t("accounts.feeRecon.difference")} value={formatPaise(data.differencePaise)} tone={data.differencePaise !== 0 ? "bad" : "good"} attention={data.differencePaise !== 0} testId="fee-recon-difference" />
            <Stat label={t("accounts.feeRecon.daysDiffer")} value={String(data.daysWithDifferences)} attention={data.daysWithDifferences > 0} />
          </ul>
          {data.daysWithDifferences > 0 ? (
            <div className="alert alert-warn mb-3" role="status" data-testid="fee-recon-warning">
              <span>{plural(t, "accounts.feeRecon.warning", data.daysWithDifferences)}</span>
            </div>
          ) : null}
          {data.days.length === 0 ? (
            <p className="empty">{t("accounts.feeRecon.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table" data-testid="fee-recon-table">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.date")}</th>
                    <th scope="col" className="r">
                      {t("accounts.feeRecon.accounts")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.feeRecon.fees")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.feeRecon.difference")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.days.map((d) => (
                    <tr key={d.day} className={d.differencePaise !== 0 ? "font-semibold" : undefined} data-differs={d.differencePaise !== 0 ? "true" : undefined}>
                      <td className="whitespace-nowrap">{formatPlainDate(d.day, locale)}</td>
                      <td className="r num">
                        {formatPaise(d.accountsPaise)} <span className="text-[12px] text-ink-3">({d.accountsCount})</span>
                      </td>
                      <td className="r num">
                        {formatPaise(d.feesPaise)} <span className="text-[12px] text-ink-3">({d.feesCount})</span>
                      </td>
                      <td className={`r num${d.differencePaise !== 0 ? " text-bad" : ""}`}>{d.differencePaise === 0 ? "—" : formatPaise(d.differencePaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** The payroll cost recorded for each approved run of the year (from PayrollApproved). */
function PayrollCostsCard() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [year, setYear] = useState(financialYearOfDate(todayInIndia()));
  const costs = useApiData(`accounts:payroll:${year}`, () => accountsApi.payrollCosts({ financialYear: year }));
  const data = costs.data;
  const total = (data ?? []).reduce((a, c) => a + c.costPaise, 0);
  return (
    <section className="card" aria-labelledby="payroll-cost-title">
      <div className="card-head">
        <div>
          <h2 id="payroll-cost-title">{t("accounts.payrollCost.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("accounts.payrollCost.sub")}</p>
        </div>
      </div>
      <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
        <YearFilter value={year} onChange={setYear} />
      </form>
      {costs.error && !data ? (
        <ErrorState error={costs.error} onRetry={costs.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : data.length === 0 ? (
        <p className="empty" data-testid="payroll-cost-empty">
          {t("accounts.payrollCost.empty")}
        </p>
      ) : (
        <div className="table-wrap">
          <table className="table" data-testid="payroll-cost-table">
            <thead>
              <tr>
                <th scope="col">{t("accounts.col.month")}</th>
                <th scope="col" className="r">
                  {t("accounts.payrollCost.staff")}
                </th>
                <th scope="col" className="r hidden sm:table-cell">
                  {t("accounts.payrollCost.gross")}
                </th>
                <th scope="col" className="r hidden sm:table-cell">
                  {t("accounts.payrollCost.employer")}
                </th>
                <th scope="col" className="r">
                  {t("accounts.payrollCost.cost")}
                </th>
              </tr>
            </thead>
            <tbody>
              {data.map((c) => (
                <tr key={c.runId}>
                  <td>{formatMonth(c.month, locale)}</td>
                  <td className="r num">{c.staffCount}</td>
                  <td className="r num hidden sm:table-cell">{formatPaise(c.grossPaise)}</td>
                  <td className="r num hidden sm:table-cell">{formatPaise(c.employerPaise)}</td>
                  <td className="r num font-semibold">{formatPaise(c.costPaise)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colSpan={4}>
                  {t("accounts.col.total")}
                </th>
                <td className="r num font-bold">{formatPaise(total)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </section>
  );
}
