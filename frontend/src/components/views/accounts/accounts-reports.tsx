"use client";

import { Fragment, useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { Figure, monthStart, rangeProblem, ReportFrame } from "@/components/views/reports/report-frame";
import { financialYearOfDate } from "@/lib/accounts";
import { accountsApi, accountsPaths } from "@/lib/accounts-api";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { formatMonth } from "@/lib/payroll";
import type { IncomeVsExpense } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ExpenseStatusPill, PeriodFilter, useBankAccounts, useVendors, YearFilter } from "./accounts-ui";
import { BookTable } from "./bank-view";

/* The eight accounts reports in the reports hub (Accounts group), each with a CSV download and print. */

function usePeriod() {
  const today = todayInIndia();
  const [from, setFrom] = useState(monthStart(today));
  const [to, setTo] = useState(today);
  return { from, to, set: (f: string, t: string) => { setFrom(f); setTo(t); } };
}

function useRangeSummary(from: string, to: string): string[] {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return [t("reports.filter.rangeIs", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) })];
}

/* ------------------------------------------------------------------ income vs expense */

export function IncomeVsExpenseReport() {
  const { t } = useI18n();
  const [year, setYear] = useState(financialYearOfDate(todayInIndia()));
  const report = useApiData(`accounts:report:ive:${year}`, () => accountsApi.incomeVsExpense({ financialYear: year }));
  const data = report.data && report.data.financialYear === year ? report.data : undefined;
  return (
    <ReportFrame
      title={t("accounts.report.incomeVsExpense.title")}
      testId="report-income-vs-expense"
      filterSummary={[t("reports.filter.yearIs", { name: year })]}
      problem={null}
      excel={{ path: accountsPaths.incomeVsExpenseCsv({ financialYear: year }), fileName: `income-vs-expense-${year}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={<YearFilter value={year} onChange={setYear} />}
    >
      {report.error && !data ? <ErrorState error={report.error} onRetry={report.reload} /> : !data ? <LoadingRows rows={6} /> : <IncomeVsExpenseBody report={data} />}
    </ReportFrame>
  );
}

export function IncomeVsExpenseBody({ report }: { report: IncomeVsExpense }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const totals = report.totals;
  const any = report.months.some((m) => m.incomePaise !== 0 || m.totalExpensePaise !== 0);
  return (
    <>
      <div className="report-figures">
        <Figure label={t("accounts.report.col.income")} value={formatPaise(totals.incomePaise)} testId="ive-income" />
        <Figure label={t("accounts.report.col.feeIncome")} value={formatPaise(totals.feeIncomePaise)} testId="ive-fee-income" />
        <Figure label={t("accounts.report.col.expense")} value={formatPaise(totals.totalExpensePaise)} testId="ive-expense" />
        <Figure label={t("accounts.report.col.net")} value={<span className={totals.netPaise < 0 ? "text-bad" : "text-good"}>{formatPaise(totals.netPaise)}</span>} testId="ive-net" />
      </div>
      {!any ? (
        <p className="empty" data-testid="ive-empty">
          {t("accounts.report.empty")}
        </p>
      ) : (
        <div className="table-wrap">
          <table className="table report-table" data-testid="ive-table">
            <caption className="sr-only">{t("accounts.report.incomeVsExpense.title")}</caption>
            <thead>
              <tr>
                <th scope="col">{t("accounts.col.month")}</th>
                <th scope="col" className="r hidden md:table-cell">
                  {t("accounts.report.col.feeIncome")}
                </th>
                <th scope="col" className="r hidden md:table-cell">
                  {t("accounts.report.col.otherIncome")}
                </th>
                <th scope="col" className="r">
                  {t("accounts.report.col.income")}
                </th>
                <th scope="col" className="r hidden md:table-cell">
                  {t("accounts.report.col.expenses")}
                </th>
                <th scope="col" className="r hidden md:table-cell">
                  {t("accounts.report.col.payroll")}
                </th>
                <th scope="col" className="r hidden lg:table-cell">
                  {t("accounts.petty.title")}
                </th>
                <th scope="col" className="r">
                  {t("accounts.report.col.expense")}
                </th>
                <th scope="col" className="r">
                  {t("accounts.report.col.net")}
                </th>
              </tr>
            </thead>
            <tbody>
              {report.months.map((m) => (
                <tr key={m.month} data-testid={`ive-month-${m.month}`}>
                  <th scope="row">{formatMonth(m.month, locale)}</th>
                  <td className="r num hidden md:table-cell">{formatPaise(m.feeIncomePaise)}</td>
                  <td className="r num hidden md:table-cell">{formatPaise(m.otherIncomePaise)}</td>
                  <td className="r num">{formatPaise(m.incomePaise)}</td>
                  <td className="r num hidden md:table-cell">{formatPaise(m.expensePaise)}</td>
                  <td className="r num hidden md:table-cell">{formatPaise(m.payrollPaise)}</td>
                  <td className="r num hidden lg:table-cell">{formatPaise(m.pettyCashPaise)}</td>
                  <td className="r num">{formatPaise(m.totalExpensePaise)}</td>
                  <td className={`r num font-semibold${m.netPaise < 0 ? " text-bad" : ""}`}>{formatPaise(m.netPaise)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">{t("accounts.col.total")}</th>
                <td className="r num hidden md:table-cell">{formatPaise(totals.feeIncomePaise)}</td>
                <td className="r num hidden md:table-cell">{formatPaise(totals.otherIncomePaise)}</td>
                <td className="r num font-bold">{formatPaise(totals.incomePaise)}</td>
                <td className="r num hidden md:table-cell">{formatPaise(totals.expensePaise)}</td>
                <td className="r num hidden md:table-cell">{formatPaise(totals.payrollPaise)}</td>
                <td className="r num hidden lg:table-cell">{formatPaise(totals.pettyCashPaise)}</td>
                <td className="r num font-bold">{formatPaise(totals.totalExpensePaise)}</td>
                <td className={`r num font-bold${totals.netPaise < 0 ? " text-bad" : ""}`}>{formatPaise(totals.netPaise)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ receipts and payments */

export function ReceiptsAndPaymentsReport() {
  const { t } = useI18n();
  const period = usePeriod();
  const problemKey = rangeProblem(period.from, period.to);
  const problem = problemKey ? t(problemKey) : null;
  const report = useApiData(problem ? null : `accounts:report:rp:${period.from}:${period.to}`, () => accountsApi.receiptsAndPayments({ from: period.from, to: period.to }));
  const data = report.data && report.data.from === period.from && report.data.to === period.to ? report.data : undefined;
  const summary = useRangeSummary(period.from, period.to);
  return (
    <ReportFrame
      title={t("accounts.report.receiptsAndPayments.title")}
      testId="report-receipts-and-payments"
      filterSummary={summary}
      problem={problem}
      excel={problem ? null : { path: accountsPaths.receiptsAndPaymentsCsv({ from: period.from, to: period.to }), fileName: `receipts-and-payments-${period.from}-${period.to}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={<PeriodFilter from={period.from} to={period.to} onChange={period.set} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("accounts.book.opening")} value={formatPaise(data.openingPaise)} />
            <Figure label={t("accounts.report.col.receipts")} value={formatPaise(data.receiptsPaise)} testId="rp-receipts" />
            <Figure label={t("accounts.report.col.payments")} value={formatPaise(data.paymentsPaise)} testId="rp-payments" />
            <Figure label={t("accounts.book.closing")} value={formatPaise(data.closingPaise)} testId="rp-closing" />
          </div>
          <div className="grid gap-3.5 md:grid-cols-2">
            {(["receipts", "payments"] as const).map((side) => (
              <div key={side} className="table-wrap">
                <table className="table report-table" data-testid={`rp-${side}-table`}>
                  <caption className="text-left text-[14px] font-semibold">{side === "receipts" ? t("accounts.report.col.receipts") : t("accounts.report.col.payments")}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("accounts.col.head")}</th>
                      <th scope="col" className="r">
                        {t("accounts.col.amount")}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data[side].length === 0 ? (
                      <tr>
                        <td colSpan={2} className="empty">
                          {t("accounts.report.empty")}
                        </td>
                      </tr>
                    ) : (
                      data[side].map((h) => (
                        <tr key={h.headId ?? h.name}>
                          <td>
                            {h.code ? <span className="mono text-[12px] text-ink-3">{h.code} </span> : null}
                            {h.name}
                          </td>
                          <td className="r num">{formatPaise(h.amountPaise)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                  <tfoot>
                    <tr>
                      <th scope="row">{t("accounts.col.total")}</th>
                      <td className="r num font-bold">{formatPaise(side === "receipts" ? data.receiptsPaise : data.paymentsPaise)}</td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            ))}
          </div>
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ budget vs actual */

export function BudgetVsActualReport() {
  const { t } = useI18n();
  const [year, setYear] = useState(financialYearOfDate(todayInIndia()));
  const report = useApiData(`accounts:report:bva:${year}`, () => accountsApi.budgetVsActual({ financialYear: year }));
  const data = report.data && report.data.financialYear === year ? report.data : undefined;
  return (
    <ReportFrame
      title={t("accounts.report.budgetVsActual.title")}
      testId="report-budget-vs-actual"
      filterSummary={[t("reports.filter.yearIs", { name: year })]}
      problem={null}
      excel={{ path: accountsPaths.budgetVsActualCsv({ financialYear: year }), fileName: `budget-vs-actual-${year}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={<YearFilter value={year} onChange={setYear} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("accounts.col.budget")} value={formatPaise(data.budgetPaise)} />
            <Figure label={t("accounts.col.actual")} value={formatPaise(data.actualPaise)} />
            <Figure label={t("accounts.report.col.used")} value={`${data.percentUsed}%`} />
            <Figure label={t("accounts.budgets.over")} value={data.overCount} testId="bva-over" />
          </div>
          {data.rows.filter((r) => r.budgetId).length === 0 ? (
            <p className="empty">{t("accounts.budgets.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="bva-table">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.head")}</th>
                    <th scope="col" className="r">
                      {t("accounts.col.budget")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.col.actual")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.col.variance")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.report.col.used")}
                    </th>
                    <th scope="col">{t("accounts.col.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows
                    .filter((r) => r.budgetId)
                    .map((r) => (
                      <tr key={r.headId}>
                        <td>
                          <span className="mono text-[12px] text-ink-3">{r.headCode}</span> {r.headName}
                          <span className="block text-[12px] text-ink-3">{translateOr(t, `accounts.headKind.${r.kind}`, r.kind)}</span>
                        </td>
                        <td className="r num">{formatPaise(r.annualPaise)}</td>
                        <td className="r num">{formatPaise(r.actualPaise)}</td>
                        <td className={`r num${r.kind === "EXPENSE" && r.actualPaise > r.annualPaise ? " text-bad" : ""}`}>{formatPaise(r.annualPaise - r.actualPaise)}</td>
                        <td className="r num">{r.percentUsed}%</td>
                        <td>{translateOr(t, `accounts.budgetStatus.${r.status}`, r.status)}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ head-wise expense */

export function HeadWiseReport() {
  const { t } = useI18n();
  const period = usePeriod();
  const problemKey = rangeProblem(period.from, period.to);
  const problem = problemKey ? t(problemKey) : null;
  const report = useApiData(problem ? null : `accounts:report:hw:${period.from}:${period.to}`, () => accountsApi.headWise({ from: period.from, to: period.to }));
  const data = report.data && report.data.from === period.from && report.data.to === period.to ? report.data : undefined;
  const summary = useRangeSummary(period.from, period.to);
  return (
    <ReportFrame
      title={t("accounts.report.headWise.title")}
      testId="report-head-wise"
      filterSummary={summary}
      problem={problem}
      excel={problem ? null : { path: accountsPaths.headWiseCsv({ from: period.from, to: period.to }), fileName: `head-wise-expense-${period.from}-${period.to}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={<PeriodFilter from={period.from} to={period.to} onChange={period.set} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("accounts.col.amount")} value={formatPaise(data.amountPaise)} testId="hw-amount" />
            <Figure label={t("accounts.expense.gst")} value={formatPaise(data.gstPaise)} />
            <Figure label={t("accounts.report.col.bills")} value={data.count} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("accounts.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="hw-table">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.head")}</th>
                    <th scope="col" className="r">
                      {t("accounts.report.col.bills")}
                    </th>
                    <th scope="col" className="r hidden sm:table-cell">
                      {t("accounts.expense.gst")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.col.amount")}
                    </th>
                    <th scope="col" className="r hidden sm:table-cell">
                      {t("accounts.report.col.share")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => (
                    <Fragment key={r.headId}>
                      <tr className="report-group-row">
                        <th scope="rowgroup">
                          <span className="mono text-[12px] text-ink-3">{r.code}</span> {r.name}
                        </th>
                        <td className="r num">{r.count}</td>
                        <td className="r num hidden sm:table-cell">{formatPaise(r.gstPaise)}</td>
                        <td className="r num font-semibold">{formatPaise(r.amountPaise)}</td>
                        <td className="r num hidden sm:table-cell">{data.amountPaise ? Math.round((r.amountPaise * 100) / data.amountPaise) : 0}%</td>
                      </tr>
                      {r.costCentres.length > 1 || (r.costCentres.length === 1 && r.costCentres[0]?.headId)
                        ? r.costCentres.map((c) => (
                            <tr key={`${r.headId}-${c.headId ?? "none"}`}>
                              <td className="report-indent text-ink-2">{c.headId ? c.name : t("accounts.expense.noCostCentre")}</td>
                              <td className="r num">{c.count}</td>
                              <td className="hidden sm:table-cell" />
                              <td className="r num">{formatPaise(c.amountPaise)}</td>
                              <td className="hidden sm:table-cell" />
                            </tr>
                          ))
                        : null}
                    </Fragment>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row">{t("accounts.col.total")}</th>
                    <td className="r num">{data.count}</td>
                    <td className="r num hidden sm:table-cell">{formatPaise(data.gstPaise)}</td>
                    <td className="r num font-bold">{formatPaise(data.amountPaise)}</td>
                    <td className="hidden sm:table-cell" />
                  </tr>
                </tfoot>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ vendor ledger */

export function VendorLedgerReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const vendors = useVendors();
  const today = todayInIndia();
  const [vendorId, setVendorId] = useState("");
  const [from, setFrom] = useState(`${financialYearOfDate(today).slice(0, 4)}-04-01`);
  const [to, setTo] = useState(today);
  const problemKey = rangeProblem(from, to);
  const problem = problemKey ? t(problemKey) : !vendorId ? t("accounts.report.pickVendor") : null;
  const report = useApiData(problem ? null : `accounts:report:vl:${vendorId}:${from}:${to}`, () => accountsApi.vendorLedger(vendorId, { from, to }));
  const data = report.data && report.data.vendor.id === vendorId && report.data.from === from && report.data.to === to ? report.data : undefined;
  const vendorName = (vendors.data ?? []).find((v) => v.id === vendorId)?.name ?? null;
  return (
    <ReportFrame
      title={t("accounts.report.vendorLedger.title")}
      testId="report-vendor-ledger"
      filterSummary={[vendorName ? t("accounts.report.vendorIs", { name: vendorName }) : "", t("reports.filter.rangeIs", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) })].filter(Boolean)}
      problem={problem}
      excel={problem ? null : { path: accountsPaths.vendorLedgerCsv({ vendorId, from, to }), fileName: `vendor-ledger-${from}-${to}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-full sm:w-auto sm:min-w-52">
            <span className="field-label">{t("accounts.col.vendor")}</span>
            <select className="input" name="vendorId" value={vendorId} onChange={(e) => setVendorId(e.target.value)} data-testid="vl-vendor">
              <option value="">{t("accounts.report.pickVendor")}</option>
              {(vendors.data ?? []).map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </select>
          </label>
          <PeriodFilter from={from} to={to} onChange={(f, tt) => { setFrom(f); setTo(tt); }} />
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("accounts.vendors.billed")} value={formatPaise(data.billedPaise)} />
            <Figure label={t("accounts.expense.gst")} value={formatPaise(data.gstPaise)} />
            <Figure label={t("accounts.expense.tds")} value={formatPaise(data.tdsPaise)} />
            <Figure label={t("accounts.vendors.paid")} value={formatPaise(data.paidPaise)} />
            <Figure label={t("accounts.vendors.outstanding")} value={formatPaise(data.outstandingPaise)} testId="vl-outstanding" />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("accounts.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="vl-table">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.date")}</th>
                    <th scope="col">{t("accounts.col.number")}</th>
                    <th scope="col">{t("accounts.col.description")}</th>
                    <th scope="col">{t("accounts.col.status")}</th>
                    <th scope="col" className="r">
                      {t("accounts.vendors.billed")}
                    </th>
                    <th scope="col" className="r hidden sm:table-cell">
                      {t("accounts.expense.tds")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.vendors.paid")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => (
                    <tr key={r.expenseId}>
                      <td className="whitespace-nowrap">{formatPlainDate(r.date, locale)}</td>
                      <td className="mono">{r.number ?? "—"}</td>
                      <td>{r.description}</td>
                      <td>
                        <ExpenseStatusPill status={r.status} />
                      </td>
                      <td className="r num">{formatPaise(r.billPaise + r.gstPaise)}</td>
                      <td className="r num hidden sm:table-cell">{formatPaise(r.tdsPaise)}</td>
                      <td className="r num">
                        {r.paidPaise ? formatPaise(r.paidPaise) : ""}
                        {r.paidOn ? <span className="block text-[12px] text-ink-3">{formatPlainDate(r.paidOn, locale)}</span> : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ cash and bank books */

export function BooksReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const accounts = useBankAccounts();
  const period = usePeriod();
  const [bankAccountId, setBankAccountId] = useState("");
  const problemKey = rangeProblem(period.from, period.to);
  const problem = problemKey ? t(problemKey) : null;
  const report = useApiData(problem ? null : `accounts:report:book:${bankAccountId}:${period.from}:${period.to}`, () => accountsApi.book({ from: period.from, to: period.to, bankAccountId: bankAccountId || undefined }));
  const data = report.data && (report.data.bankAccountId ?? "") === bankAccountId && report.data.from === period.from ? report.data : undefined;
  const summary = useRangeSummary(period.from, period.to);
  return (
    <ReportFrame
      title={t("accounts.report.books.title")}
      testId="report-books"
      filterSummary={[data?.name ?? "", ...summary].filter(Boolean)}
      problem={problem}
      excel={problem ? null : { path: accountsPaths.booksCsv({ from: period.from, to: period.to, bankAccountId: bankAccountId || undefined }), fileName: `book-${period.from}-${period.to}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("accounts.bank.book")}</span>
            <select className="input" name="bankAccountId" value={bankAccountId} onChange={(e) => setBankAccountId(e.target.value)}>
              <option value="">{t("accounts.bank.cashBook")}</option>
              {(accounts.data ?? []).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </label>
          <PeriodFilter from={period.from} to={period.to} onChange={period.set} />
        </>
      }
    >
      {report.error && !data ? <ErrorState error={report.error} onRetry={report.reload} /> : problem ? null : !data ? <LoadingRows rows={6} /> : <BookTable book={data} locale={locale} />}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ GST summary */

export function GstSummaryReport() {
  const { t } = useI18n();
  const period = usePeriod();
  const problemKey = rangeProblem(period.from, period.to);
  const problem = problemKey ? t(problemKey) : null;
  const report = useApiData(problem ? null : `accounts:report:gst:${period.from}:${period.to}`, () => accountsApi.gstSummary({ from: period.from, to: period.to }));
  const data = report.data && report.data.from === period.from && report.data.to === period.to ? report.data : undefined;
  const summary = useRangeSummary(period.from, period.to);
  return (
    <ReportFrame
      title={t("accounts.report.gstSummary.title")}
      testId="report-gst-summary"
      filterSummary={summary}
      problem={problem}
      excel={problem ? null : { path: accountsPaths.gstSummaryCsv({ from: period.from, to: period.to }), fileName: `gst-summary-${period.from}-${period.to}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={<PeriodFilter from={period.from} to={period.to} onChange={period.set} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("accounts.report.col.taxable")} value={formatPaise(data.taxablePaise)} />
            <Figure label={t("accounts.expense.gst")} value={formatPaise(data.gstPaise)} testId="gst-total" />
            <Figure label={t("accounts.report.col.withoutGstin")} value={formatPaise(data.withoutGstinPaise)} />
          </div>
          <p className="text-[13px] text-ink-2">{t("accounts.report.gstNote")}</p>
          {data.rows.length === 0 ? (
            <p className="empty">{t("accounts.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="gst-table">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.vendor")}</th>
                    <th scope="col">{t("accounts.vendors.gstin")}</th>
                    <th scope="col" className="r">
                      {t("accounts.report.col.bills")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.report.col.taxable")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.expense.gst")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => (
                    <tr key={r.vendorId ?? r.vendorName}>
                      <td>{r.vendorName}</td>
                      <td className="mono">{r.gstin ?? <span className="text-ink-3">{t("accounts.report.noGstin")}</span>}</td>
                      <td className="r num">{r.bills}</td>
                      <td className="r num">{formatPaise(r.taxablePaise)}</td>
                      <td className="r num">{formatPaise(r.gstPaise)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row" colSpan={3}>
                      {t("accounts.col.total")}
                    </th>
                    <td className="r num font-bold">{formatPaise(data.taxablePaise)}</td>
                    <td className="r num font-bold">{formatPaise(data.gstPaise)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ day book */

export function DayBookReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const [date, setDate] = useState(today);
  const problem = !/^\d{4}-\d{2}-\d{2}$/.test(date) ? t("reports.v.dates") : null;
  const report = useApiData(problem ? null : `accounts:report:day:${date}`, () => accountsApi.dayBook(date));
  const data = report.data && report.data.date === date ? report.data : undefined;
  return (
    <ReportFrame
      title={t("accounts.report.dayBook.title")}
      testId="report-day-book"
      filterSummary={[t("reports.filter.dateIs", { date: formatPlainDate(date, locale) })]}
      problem={problem}
      excel={problem ? null : { path: accountsPaths.dayBookCsv({ date }), fileName: `day-book-${date}.csv` }}
      downloadLabel={t("accounts.report.csv")}
      ready={Boolean(data)}
      filters={
        <label className="field w-full sm:w-auto">
          <span className="field-label">{t("reports.filter.date")}</span>
          <input type="date" className="input" name="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} required />
        </label>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("accounts.report.col.receipts")} value={formatPaise(data.receiptsPaise)} testId="day-receipts" />
            <Figure label={t("accounts.report.col.payments")} value={formatPaise(data.paymentsPaise)} testId="day-payments" />
            <Figure label={t("accounts.report.col.entries")} value={plural(t, "accounts.report.entries", data.rows.length)} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("accounts.report.emptyDay")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="day-table">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.kind")}</th>
                    <th scope="col">{t("accounts.col.particulars")}</th>
                    <th scope="col" className="hidden sm:table-cell">
                      {t("accounts.col.mode")}
                    </th>
                    <th scope="col" className="hidden md:table-cell">
                      {t("accounts.bank.account")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.col.receipt")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.col.payment")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r, i) => (
                    <tr key={`${r.refId}-${i}`}>
                      <td>{translateOr(t, `accounts.dayKind.${r.kind}`, r.kind)}</td>
                      <td>
                        {r.number ? <span className="mono text-[12px] text-ink-3">{r.number} </span> : null}
                        {r.particulars}
                      </td>
                      <td className="hidden sm:table-cell">{r.mode ? translateOr(t, `accounts.mode.${r.mode}`, r.mode) : "—"}</td>
                      <td className="hidden md:table-cell">{r.account ?? "—"}</td>
                      <td className="r num">{r.receiptPaise ? formatPaise(r.receiptPaise) : ""}</td>
                      <td className="r num">{r.paymentPaise ? formatPaise(r.paymentPaise) : ""}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row" colSpan={4}>
                      {t("accounts.col.total")}
                    </th>
                    <td className="r num font-bold">{formatPaise(data.receiptsPaise)}</td>
                    <td className="r num font-bold">{formatPaise(data.paymentsPaise)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}
