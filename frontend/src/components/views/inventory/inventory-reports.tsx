"use client";

import { useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { PeriodFilter } from "@/components/views/accounts/accounts-ui";
import { Figure, monthStart, rangeProblem, ReportFrame } from "@/components/views/reports/report-frame";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { inventoryApi, inventoryPaths } from "@/lib/inventory-api";
import { formatMonth } from "@/lib/payroll";
import { useApiData } from "@/lib/use-api-data";
import { AssetsTable } from "./assets-view";
import { ItemsTable } from "./items-view";
import { OrderPill } from "./inventory-ui";
import { OrdersTable } from "./orders-view";

/* The six inventory reports in the reports hub (Inventory group), each with a CSV download (BOM) and print. */

function usePeriod() {
  const today = todayInIndia();
  const [from, setFrom] = useState(monthStart(today));
  const [to, setTo] = useState(today);
  return {
    from,
    to,
    set: (f: string, t: string) => {
      setFrom(f);
      setTo(t);
    },
  };
}

function useRangeSummary(from: string, to: string): string[] {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return [t("reports.filter.rangeIs", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) })];
}

/* ------------------------------------------------------------------ stock on hand */

export function StockOnHandReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData("inventory:report:stock", inventoryApi.stockOnHand);
  const data = report.data;
  return (
    <ReportFrame title={t("inventory.report.stockOnHand.title")} testId="report-stock-on-hand" filterSummary={data ? [t("inventory.report.asOf", { date: formatPlainDate(data.asOf, locale) })] : []} problem={null} excel={{ path: inventoryPaths.stockOnHandCsv(), fileName: "stock-on-hand.csv" }} downloadLabel={t("inventory.report.csv")} ready={Boolean(data)} filters={null}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("inventory.col.items")} value={String(data.items.length)} testId="stock-items" />
            <Figure label={t("inventory.col.value")} value={formatPaise(data.categories.reduce((s, c) => s + c.valuePaise, 0))} testId="stock-value" />
            <Figure label={t("inventory.items.low")} value={String(data.items.filter((i) => i.low).length)} testId="stock-low" />
          </div>
          {data.categories.length === 0 ? (
            <p className="empty" data-testid="stock-empty">
              {t("inventory.report.empty")}
            </p>
          ) : (
            <>
              <div className="table-wrap">
                <table className="table report-table" data-testid="stock-categories">
                  <caption className="sr-only">{t("inventory.report.stockOnHand.byCategory")}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("inventory.col.category")}</th>
                      <th scope="col" className="r">
                        {t("inventory.col.items")}
                      </th>
                      <th scope="col" className="r">
                        {t("inventory.col.stock")}
                      </th>
                      <th scope="col" className="r">
                        {t("inventory.col.value")}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.categories.map((c) => (
                      <tr key={c.categoryId}>
                        <th scope="row">{c.categoryName ?? "—"}</th>
                        <td className="r num">{c.items}</td>
                        <td className="r num">{c.stock}</td>
                        <td className="r num">{formatPaise(c.valuePaise)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <h3 className="mt-3 font-semibold">{t("inventory.report.stockOnHand.byItem")}</h3>
              <ItemsTable rows={data.items} />
            </>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ low stock */

export function LowStockReport() {
  const { t } = useI18n();
  const report = useApiData("inventory:report:low", inventoryApi.lowStock);
  const data = report.data;
  return (
    <ReportFrame title={t("inventory.report.lowStock.title")} testId="report-low-stock" filterSummary={[]} problem={null} excel={{ path: inventoryPaths.lowStockCsv(), fileName: "low-stock.csv" }} downloadLabel={t("inventory.report.csv")} ready={Boolean(data)} filters={null}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : data.items.length === 0 ? (
        <p className="empty" data-testid="low-empty">
          {t("inventory.report.lowStock.none")}
        </p>
      ) : (
        <>
          <p className="text-sm text-ink-2" data-testid="low-count">
            {t("inventory.report.lowStock.count", { count: data.items.length })}
          </p>
          <ItemsTable rows={data.items} />
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ asset register */

export function AssetRegisterReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData("inventory:report:register", inventoryApi.assetRegister);
  const data = report.data;
  return (
    <ReportFrame title={t("inventory.report.assetRegister.title")} testId="report-asset-register" filterSummary={data ? [t("inventory.report.asOf", { date: formatPlainDate(data.asOf, locale) })] : []} problem={null} excel={{ path: inventoryPaths.assetRegisterCsv(), fileName: "asset-register.csv" }} downloadLabel={t("inventory.report.csv")} ready={Boolean(data)} filters={null}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("inventory.col.assets")} value={String(data.assets)} testId="register-count" />
            <Figure label={t("inventory.col.cost")} value={formatPaise(data.costPaise)} testId="register-cost" />
            <Figure label={t("inventory.asset.writtenDown")} value={formatPaise(data.writtenDownPaise)} testId="register-wdv" />
          </div>
          {data.locations.length === 0 ? (
            <p className="empty" data-testid="register-empty">
              {t("inventory.report.empty")}
            </p>
          ) : (
            data.locations.map((loc) => (
              <section key={loc.locationId ?? "none"} className="flex flex-col gap-2" data-testid="register-location">
                <h3 className="mt-3 font-semibold">
                  {loc.locationName ?? t("inventory.report.noLocation")} · {t("inventory.report.assetRegister.sub", { count: loc.assets, cost: formatPaise(loc.costPaise), wdv: formatPaise(loc.writtenDownPaise) })}
                </h3>
                <AssetsTable rows={loc.items} locale={locale} />
              </section>
            ))
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ assets by custodian */

export function AssetsByCustodianReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData("inventory:report:custodians", inventoryApi.assetsByCustodian);
  const data = report.data;
  return (
    <ReportFrame title={t("inventory.report.assetsByCustodian.title")} testId="report-assets-by-custodian" filterSummary={[]} problem={null} excel={{ path: inventoryPaths.assetsByCustodianCsv(), fileName: "assets-by-custodian.csv" }} downloadLabel={t("inventory.report.csv")} ready={Boolean(data)} filters={null}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : data.custodians.length === 0 ? (
        <p className="empty" data-testid="custodians-empty">
          {t("inventory.report.empty")}
        </p>
      ) : (
        <>
          <div className="table-wrap">
            <table className="table report-table" data-testid="custodians-table">
              <thead>
                <tr>
                  <th scope="col">{t("inventory.col.custodian")}</th>
                  <th scope="col" className="r">
                    {t("inventory.col.assets")}
                  </th>
                  <th scope="col" className="r">
                    {t("inventory.col.cost")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.custodians.map((c) => (
                  <tr key={c.custodianId ?? "none"}>
                    <th scope="row">{c.custodianName ?? t("inventory.report.noCustodian")}</th>
                    <td className="r num">{c.assets}</td>
                    <td className="r num">{formatPaise(c.costPaise)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("inventory.col.total")}</th>
                  <td className="r num font-bold">{data.assets}</td>
                  <td className="r num font-bold">{formatPaise(data.custodians.reduce((s, c) => s + c.costPaise, 0))}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          {data.custodians.map((c) => (
            <section key={c.custodianId ?? "none"} className="flex flex-col gap-2" data-testid="custodian-section">
              <h3 className="mt-3 font-semibold">{c.custodianName ?? t("inventory.report.noCustodian")}</h3>
              <AssetsTable rows={c.items} locale={locale} />
            </section>
          ))}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ purchase orders */

export function PurchaseOrdersReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const period = usePeriod();
  const problemKey = rangeProblem(period.from, period.to);
  const problem = problemKey ? t(problemKey) : null;
  const report = useApiData(problem ? null : `inventory:report:orders:${period.from}:${period.to}`, () => inventoryApi.ordersReport({ from: period.from, to: period.to }));
  const data = report.data;
  const summary = useRangeSummary(period.from, period.to);
  return (
    <ReportFrame title={t("inventory.report.orders.title")} testId="report-purchase-orders" filterSummary={summary} problem={problem} excel={problem ? null : { path: inventoryPaths.purchaseOrdersCsv({ from: period.from, to: period.to }), fileName: `purchase-orders-${period.from}-${period.to}.csv` }} downloadLabel={t("inventory.report.csv")} ready={Boolean(data)} filters={<PeriodFilter from={period.from} to={period.to} onChange={period.set} />}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={6} />
      ) : data.orders.length === 0 ? (
        <p className="empty" data-testid="orders-report-empty">
          {t("inventory.report.empty")}
        </p>
      ) : (
        <>
          <div className="grid gap-3.5 md:grid-cols-2">
            <div className="table-wrap">
              <table className="table report-table" data-testid="orders-by-status">
                <caption className="text-left text-[14px] font-semibold">{t("inventory.report.orders.byStatus")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("inventory.col.status")}</th>
                    <th scope="col" className="r">
                      {t("inventory.col.orders")}
                    </th>
                    <th scope="col" className="r">
                      {t("inventory.col.total")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.byStatus.map((s) => (
                    <tr key={s.status}>
                      <td>
                        <OrderPill status={s.status} />
                      </td>
                      <td className="r num">{s.orders}</td>
                      <td className="r num">{formatPaise(s.totalPaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="table-wrap">
              <table className="table report-table" data-testid="orders-by-vendor">
                <caption className="text-left text-[14px] font-semibold">{t("inventory.report.orders.byVendor")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("inventory.col.vendor")}</th>
                    <th scope="col" className="r">
                      {t("inventory.col.orders")}
                    </th>
                    <th scope="col" className="r">
                      {t("inventory.col.total")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.byVendor.map((v) => (
                    <tr key={v.vendorId}>
                      <td>{v.vendorName}</td>
                      <td className="r num">{v.orders}</td>
                      <td className="r num">{formatPaise(v.totalPaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          <h3 className="mt-3 font-semibold">{t("inventory.report.orders.list")}</h3>
          <OrdersTable rows={data.orders} locale={locale} testId="orders-report" />
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ consumption */

export function ConsumptionReport() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const [from, setFrom] = useState(`${today.slice(0, 4)}-${String(Math.max(1, Number(today.slice(5, 7)) - 5)).padStart(2, "0")}-01`);
  const [to, setTo] = useState(today);
  const problemKey = rangeProblem(from, to);
  const problem = problemKey ? t(problemKey) : null;
  const report = useApiData(problem ? null : `inventory:report:consumption:${from}:${to}`, () => inventoryApi.consumption({ from, to }));
  const data = report.data;
  const summary = useRangeSummary(from, to);
  const cell = (department: string, month: string) => data?.cells.find((c) => c.department === department && c.month === month);
  return (
    <ReportFrame
      title={t("inventory.report.consumption.title")}
      testId="report-consumption"
      filterSummary={summary}
      problem={problem}
      excel={problem ? null : { path: inventoryPaths.consumptionCsv({ from, to }), fileName: `consumption-${from}-${to}.csv` }}
      downloadLabel={t("inventory.report.csv")}
      ready={Boolean(data)}
      filters={
        <PeriodFilter
          from={from}
          to={to}
          onChange={(f, tt) => {
            setFrom(f);
            setTo(tt);
          }}
        />
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : problem ? null : !data ? (
        <LoadingRows rows={6} />
      ) : data.departments.length === 0 ? (
        <p className="empty" data-testid="consumption-empty">
          {t("inventory.report.empty")}
        </p>
      ) : (
        <div className="table-wrap">
          <table className="table report-table" data-testid="consumption-table">
            <thead>
              <tr>
                <th scope="col">{t("inventory.col.department")}</th>
                {data.months.map((m) => (
                  <th key={m} scope="col" className="r">
                    {formatMonth(m, locale)}
                  </th>
                ))}
                <th scope="col" className="r">
                  {t("inventory.col.total")}
                </th>
              </tr>
            </thead>
            <tbody>
              {data.departments.map((d) => {
                const total = data.cells.filter((c) => c.department === d).reduce((s, c) => s + c.valuePaise, 0);
                return (
                  <tr key={d} data-testid="consumption-row">
                    <th scope="row">{d}</th>
                    {data.months.map((m) => {
                      const c = cell(d, m);
                      return (
                        <td key={m} className="r num">
                          {c ? (
                            <>
                              {formatPaise(c.valuePaise)}
                              <span className="block text-[11.5px] text-ink-3">{t("inventory.report.consumption.units", { count: c.quantity })}</span>
                            </>
                          ) : (
                            "—"
                          )}
                        </td>
                      );
                    })}
                    <td className="r num font-semibold">{formatPaise(total)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </ReportFrame>
  );
}
