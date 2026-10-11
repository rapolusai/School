"use client";

import { Search, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatPaise } from "@/lib/format";
import { plural, useI18n } from "@/lib/i18n";
import { payrollApi } from "@/lib/payroll-api";
import type { PayStaffRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { PayrollNav, useMonthLabel } from "./payroll-ui";

export type PayStaffFilter = "all" | "structure" | "bank";

/** The people a filter and search keep, in the API's order. Exported for tests. */
export function filterPayStaff(rows: PayStaffRow[], filter: PayStaffFilter, search: string): PayStaffRow[] {
  const q = search.trim().toLowerCase();
  return rows.filter((row) => {
    if (filter === "structure" && (row.structureFrom !== null || !row.active)) return false;
    if (filter === "bank" && (row.hasBank || !row.active)) return false;
    if (!q) return true;
    return [row.name, row.employeeCode, row.designation, row.departmentName]
      .filter(Boolean)
      .some((value) => (value as string).toLowerCase().includes(q));
  });
}

/** /app/payroll/staff: every member of staff with their salary this month and what payroll still needs. */
export function PayStaffView({ initialFilter = "all" }: { initialFilter?: PayStaffFilter }) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const staff = useApiData("payroll:staff", payrollApi.staff);
  const [filter, setFilter] = useState<PayStaffFilter>(initialFilter);
  const [search, setSearch] = useState("");
  const data = staff.data;
  const rows = data ? filterPayStaff(data.staff, filter, search) : [];

  return (
    <>
      <PageHead eyebrow={t("payroll.eyebrow")} title={t("payroll.staff.title")} />
      <PayrollNav />
      {data && (data.needStructure > 0 || data.needBankDetails > 0) ? (
        <div className="alert alert-warn" role="note" data-testid="staff-gaps">
          <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>
            {[
              data.needStructure > 0 ? plural(t, "payroll.runs.needStructure", data.needStructure) : null,
              data.needBankDetails > 0 ? plural(t, "payroll.staff.needBank", data.needBankDetails) : null,
            ]
              .filter(Boolean)
              .join(" ")}
          </span>
        </div>
      ) : null}
      <section className="card" aria-labelledby="pay-staff-title">
        <div className="card-head">
          <div>
            <h2 id="pay-staff-title">{t("payroll.staff.list")}</h2>
            <p className="mt-1 text-sm text-ink-2">
              {data ? t("payroll.staff.sub", { month: monthLabel(data.month) }) : null}
            </p>
          </div>
        </div>
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("payroll.staff.search")}</span>
            <input
              type="search"
              name="q"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t("payroll.staff.search")}
              maxLength={100}
            />
          </label>
          <label className="min-w-[180px] flex-1 sm:flex-none">
            <span className="sr-only">{t("payroll.staff.show")}</span>
            <select
              className="input"
              name="filter"
              value={filter}
              aria-label={t("payroll.staff.show")}
              onChange={(e) => setFilter(e.target.value as PayStaffFilter)}
            >
              <option value="all">{t("payroll.staff.filter.all")}</option>
              <option value="structure">{t("payroll.staff.filter.structure")}</option>
              <option value="bank">{t("payroll.staff.filter.bank")}</option>
            </select>
          </label>
        </div>
        {staff.error && !data ? (
          <ErrorState error={staff.error} onRetry={staff.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="pay-staff-empty">
            {data.staff.length === 0 ? t("payroll.staff.empty") : t("payroll.staff.noMatch")}
          </p>
        ) : (
          <>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="pay-staff-table">
                <thead>
                  <tr>
                    <th scope="col">{t("payroll.col.name")}</th>
                    <th scope="col">{t("payroll.col.department")}</th>
                    <th scope="col">{t("payroll.col.structureFrom")}</th>
                    <th scope="col" className="r">
                      {t("payroll.col.monthlyGross")}
                    </th>
                    <th scope="col" className="r">
                      {t("payroll.col.ctc")}
                    </th>
                    <th scope="col">{t("payroll.col.bank")}</th>
                    <th scope="col">{t("payroll.col.pan")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.userId}>
                      <td>
                        <Link href={`/app/payroll/staff/${row.userId}`} className="link font-semibold">
                          {row.name}
                        </Link>
                        <span className="block text-[12.5px] text-ink-3">
                          {[row.employeeCode, row.designation].filter(Boolean).join(" · ") || "—"}
                        </span>
                      </td>
                      <td>{row.departmentName ?? "—"}</td>
                      <td>
                        <StructureState row={row} monthLabel={monthLabel} />
                      </td>
                      <td className="r num">{row.structureFrom ? formatPaise(row.grossPaise) : "—"}</td>
                      <td className="r num">{row.structureFrom ? formatPaise(row.ctcPaise) : "—"}</td>
                      <td className="mono whitespace-nowrap">
                        {row.hasBank ? `XXXX ${row.bankLast4 ?? ""}` : <Pill tone="warn">{t("payroll.staff.missing")}</Pill>}
                      </td>
                      <td className="mono whitespace-nowrap">{row.panMasked ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" data-testid="pay-staff-cards">
              {rows.map((row) => (
                <li key={row.userId}>
                  <Link href={`/app/payroll/staff/${row.userId}`} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">{row.name}</b>
                      <span className="block truncate text-[12.5px] text-ink-3">
                        {[row.designation, row.departmentName].filter(Boolean).join(" · ") || "—"}
                      </span>
                      <span className="mt-1 flex flex-wrap gap-1">
                        <StructureState row={row} monthLabel={monthLabel} />
                        {row.hasBank ? null : <Pill tone="warn">{t("payroll.staff.noBank")}</Pill>}
                      </span>
                    </span>
                    <span className="num font-semibold">{row.structureFrom ? formatPaise(row.grossPaise) : ""}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </>
  );
}

function StructureState({ row, monthLabel }: { row: PayStaffRow; monthLabel: (month: string) => string }) {
  const { t } = useI18n();
  if (!row.active) return <Pill>{t("payroll.staff.inactive")}</Pill>;
  if (!row.structureFrom) return <Pill tone="bad">{t("payroll.staff.needsStructure")}</Pill>;
  return <span className="text-[13.5px]">{t("payroll.staff.from", { month: monthLabel(row.structureFrom.slice(0, 7)) })}</span>;
}
