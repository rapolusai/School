"use client";

import Link from "next/link";
import { useState } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { platformBillingApi } from "@/lib/billing-api";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n, type Translate } from "@/lib/i18n";
import type { RenewalKind, RenewalRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { PlanCards, planLabel } from "./billing-ui";

const KIND_TONES: Record<RenewalKind, PillTone> = {
  OVERDUE: "bad",
  DUE_FOR_RENEWAL: "warn",
  TRIAL_ENDING: "info",
};

const FILTERS: ("ALL" | RenewalKind)[] = ["ALL", "OVERDUE", "DUE_FOR_RENEWAL", "TRIAL_ENDING"];

/** "in 5 days", "today", "12 days ago". */
export function daysLabel(t: Translate, days: number): string {
  if (days === 0) return t("billing.renewals.today");
  return days > 0 ? plural(t, "billing.renewals.inDays", days) : plural(t, "billing.renewals.daysAgo", -days);
}

/** /app/platform/billing: who is overdue, due for renewal or ending a trial, and the plan catalogue. */
export function RenewalsView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const renewals = useApiData("platform:renewals", platformBillingApi.renewals);
  const plans = useApiData("platform:plans", platformBillingApi.plans);
  const [filter, setFilter] = useState<"ALL" | RenewalKind>("ALL");
  const rows: RenewalRow[] = (renewals.data ?? []).filter((row) => filter === "ALL" || row.kind === filter);
  const count = (kind: RenewalKind) => (renewals.data ?? []).filter((row) => row.kind === kind).length;

  return (
    <>
      <PageHead eyebrow={t("billing.platform.eyebrow")} title={t("billing.platform.title")} />
      <section className="card" aria-labelledby="renewals-title">
        <div className="card-head">
          <div className="min-w-0">
            <h2 id="renewals-title">{t("billing.renewals.title")}</h2>
            <p className="mt-1 text-[13px] text-ink-3">{t("billing.renewals.note")}</p>
          </div>
          <div className="seg" role="radiogroup" aria-label={t("billing.renewals.filter")}>
            {FILTERS.map((value) => (
              <label key={value}>
                <input
                  type="radio"
                  name="renewal-filter"
                  value={value}
                  checked={filter === value}
                  onChange={() => setFilter(value)}
                />
                {translateOr(t, `billing.renewals.kind.${value}`, value)}
                {value !== "ALL" && renewals.data ? ` (${count(value)})` : ""}
              </label>
            ))}
          </div>
        </div>
        {renewals.error && !renewals.data ? (
          <ErrorState error={renewals.error} onRetry={renewals.reload} />
        ) : !renewals.data ? (
          <LoadingRows rows={4} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="renewals-empty">
            {t("billing.renewals.empty")}
          </p>
        ) : (
          <div className="table-wrap">
            <table className="table" data-testid="renewals-table">
              <thead>
                <tr>
                  <th scope="col">{t("schools.col.school")}</th>
                  <th scope="col">{t("billing.renewals.col.what")}</th>
                  <th scope="col" className="hidden sm:table-cell">
                    {t("billing.renewals.col.date")}
                  </th>
                  <th scope="col" className="r">
                    {t("billing.renewals.col.amount")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={`${row.tenantId}:${row.kind}`}>
                    <td className="max-w-0 w-full sm:w-auto sm:max-w-none">
                      <Link
                        href={`/app/platform/schools/${encodeURIComponent(row.tenantId)}`}
                        className="link block truncate font-semibold"
                      >
                        {row.name}
                      </Link>
                      <span className="sub block text-[12.5px] text-ink-3">
                        <span className="mono">{row.code}</span> · {planLabel(t, row.plan)} ·{" "}
                        {translateOr(t, `status.${row.status}`, row.status)}
                      </span>
                    </td>
                    <td>
                      <Pill tone={KIND_TONES[row.kind]} dot>
                        {translateOr(t, `billing.renewals.kind.${row.kind}`, row.kind)}
                      </Pill>
                      <span className="block pt-1 text-[12.5px] text-ink-3 sm:hidden">
                        {formatPlainDate(row.date, locale)} · {daysLabel(t, row.days)}
                      </span>
                    </td>
                    <td className="hidden whitespace-nowrap sm:table-cell">
                      {formatPlainDate(row.date, locale)}
                      <span className="block text-[12.5px] text-ink-3">{daysLabel(t, row.days)}</span>
                    </td>
                    <td className="r num whitespace-nowrap">
                      {row.amountPaise === null ? "—" : formatPaise(row.amountPaise)}
                      {row.invoices ? (
                        <span className="block text-[12.5px] text-ink-3">
                          {plural(t, "billing.renewals.invoices", row.invoices)}
                        </span>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <section aria-labelledby="catalogue-title" className="flex flex-col gap-3">
        <h2 id="catalogue-title">{t("billing.plans.catalogue")}</h2>
        {plans.error && !plans.data ? (
          <section className="card">
            <ErrorState error={plans.error} onRetry={plans.reload} />
          </section>
        ) : !plans.data ? (
          <section className="card">
            <LoadingRows rows={3} />
          </section>
        ) : (
          <>
            <PlanCards plans={plans.data} />
            <p className="text-[13px] text-ink-3">{t("billing.plans.priceNote")}</p>
          </>
        )}
      </section>
    </>
  );
}
