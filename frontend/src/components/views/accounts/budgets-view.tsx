"use client";

import { Pencil } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { financialYearMonths, financialYearOfDate } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { useAuth } from "@/lib/auth";
import { formatPaise, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { formatMonth } from "@/lib/payroll";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { BudgetRow, BudgetSpread, BudgetsView as Budgets } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { AccountsNav, BudgetPill, Stat, YearFilter } from "./accounts-ui";

type BudgetValues = { annual: string; spread: BudgetSpread; months: string[] };

/** The same checks PUT /api/accounts/budgets/{fy} makes: entered months must add up to the year. Exported for tests. */
export function validateBudget(values: BudgetValues): Problems {
  const problems: Problems = {};
  const annual = parseRupees(values.annual);
  if (annual === null) problems.annual = "accounts.v.amountOrZero";
  if (values.spread === "ENTERED") {
    const parts = values.months.map((m) => (m.trim() === "" ? 0 : parseRupees(m)));
    if (parts.some((p) => p === null)) problems.months = "accounts.v.months";
    else if (annual !== null && parts.reduce<number>((a, b) => a + (b ?? 0), 0) !== annual) problems.months = "accounts.v.monthsSum";
  }
  return problems;
}

/** /app/accounts/budgets: this year's budget per head against the actuals, with the variance and warnings. */
export function BudgetsView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const [year, setYear] = useState(financialYearOfDate(todayInIndia()));
  const [editing, setEditing] = useState<BudgetRow | null>(null);
  const budgets = useApiData(`accounts:budgets:${year}`, () => accountsApi.budgets(year));
  const data = budgets.data;
  const kinds: ("EXPENSE" | "INCOME")[] = ["EXPENSE", "INCOME"];
  return (
    <>
      <PageHead eyebrow={t("accounts.eyebrow")} title={t("accounts.budgets.title")} />
      <AccountsNav />
      <section className="card" aria-labelledby="budgets-title">
        <div className="card-head">
          <div>
            <h2 id="budgets-title">{t("accounts.budgets.list", { year })}</h2>
            <p className="mt-1 text-sm text-ink-2">
              {data ? (data.mode === "BLOCK" ? t("accounts.budgets.modeBlock") : t("accounts.budgets.modeWarn")) : ""}
            </p>
          </div>
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <YearFilter value={year} onChange={setYear} />
        </form>
        {data ? (
          <ul className="rep-kpis mb-4" data-testid="budget-figures">
            <Stat label={t("accounts.budgets.yearBudget")} value={formatPaise(data.budgetPaise)} />
            <Stat label={t("accounts.budgets.yearActual")} value={formatPaise(data.actualPaise)} sub={`${data.percentUsed}%`} />
            <Stat label={t("accounts.budgets.near")} value={String(data.nearCount)} attention={data.nearCount > 0} />
            <Stat label={t("accounts.budgets.over")} value={String(data.overCount)} attention={data.overCount > 0} tone={data.overCount > 0 ? "bad" : undefined} />
          </ul>
        ) : null}
        {budgets.error && !data ? (
          <ErrorState error={budgets.error} onRetry={budgets.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : data.rows.length === 0 ? (
          <p className="empty" data-testid="budgets-empty">
            {t("accounts.budgets.empty")}
          </p>
        ) : (
          kinds.map((kind) => (
            <div key={kind} className="mt-3">
              <h3 className="mb-2 text-[14px] font-semibold">{translateOr(t, `accounts.headKind.${kind}`, kind)}</h3>
              <div className="table-wrap">
                <table className="table" data-testid={`budgets-${kind.toLowerCase()}`}>
                  <thead>
                    <tr>
                      <th scope="col">{t("accounts.col.head")}</th>
                      <th scope="col" className="r">
                        {t("accounts.col.budget")}
                      </th>
                      <th scope="col" className="r">
                        {t("accounts.col.actual")}
                      </th>
                      <th scope="col" className="r hidden sm:table-cell">
                        {t("accounts.col.variance")}
                      </th>
                      <th scope="col">{t("accounts.col.status")}</th>
                      {canManage ? <th scope="col" className="r">{t("accounts.col.actions")}</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows
                      .filter((r) => r.kind === kind)
                      .map((r) => (
                        <tr key={r.headId}>
                          <td>
                            <span className="mono text-[12px] text-ink-3">{r.headCode}</span> {r.headName}
                          </td>
                          <td className="r num">{formatPaise(r.annualPaise)}</td>
                          <td className="r num">{formatPaise(r.actualPaise)}</td>
                          <td className={`r num hidden sm:table-cell${r.annualPaise - r.actualPaise < 0 && kind === "EXPENSE" ? " text-bad" : ""}`}>
                            {r.budgetId ? formatPaise(r.annualPaise - r.actualPaise) : "—"}
                          </td>
                          <td>
                            <BudgetPill status={r.status} percent={r.status === "NONE" ? undefined : r.percentUsed} />
                          </td>
                          {canManage ? (
                            <td className="r">
                              <button type="button" className="btn btn-sm" onClick={() => setEditing(r)} aria-label={t("accounts.budgets.editFor", { head: r.headName })}>
                                <Pencil size={16} aria-hidden="true" />
                                {r.budgetId ? t("common.edit") : t("accounts.budgets.set")}
                              </button>
                            </td>
                          ) : null}
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))
        )}
      </section>
      {editing && data ? (
        <BudgetDialog
          year={year}
          row={editing}
          locale={locale}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("accounts.budgets.saved"));
            budgets.reload();
          }}
        />
      ) : null}
    </>
  );
}

function BudgetDialog({ year, row, locale, onClose, onSaved }: { year: string; row: BudgetRow; locale: string; onClose: () => void; onSaved: (b: Budgets) => void }) {
  const { t } = useI18n();
  const months = financialYearMonths(year);
  const form = useForm<BudgetValues>({
    annual: row.budgetId ? rupeesInput(row.annualPaise) : "",
    spread: row.spread,
    months: row.months.map((m) => (m ? rupeesInput(m) : "")),
  });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateBudget(form.values), t, event.currentTarget)) return;
    let saved: Budgets | undefined;
    const ok = await form.submit(t, async () => {
      saved = await accountsApi.saveBudget(year, {
        headId: row.headId,
        annualPaise: parseRupees(form.values.annual) ?? 0,
        spread: form.values.spread,
        months: form.values.spread === "ENTERED" ? form.values.months.map((m) => (m.trim() ? (parseRupees(m) ?? 0) : 0)) : null,
      });
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("accounts.budgets.dialogTitle", { head: row.headName, year })} closeLabel={t("common.close")} variant="sheet">
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="budget-form">
        <FormAlert message={form.formError} />
        <TextField label={t("accounts.budgets.annual")} name="annual" inputMode="decimal" value={form.values.annual} onChange={(e) => form.set("annual", e.target.value)} error={form.errors.annual} hint={t("accounts.expense.amountHint")} required data-autofocus />
        <SelectField
          label={t("accounts.budgets.spread")}
          name="spread"
          value={form.values.spread}
          onChange={(e) => form.set("spread", e.target.value as BudgetSpread)}
          options={[
            { value: "EVEN", label: t("accounts.budgets.spreadEven") },
            { value: "ENTERED", label: t("accounts.budgets.spreadEntered") },
          ]}
        />
        {form.values.spread === "ENTERED" ? (
          <fieldset className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            <legend className="field-label mb-1">{t("accounts.budgets.perMonth")}</legend>
            {months.map((m, i) => (
              <TextField
                key={m}
                label={formatMonth(m, locale)}
                name={`months.${i}`}
                inputMode="decimal"
                value={form.values.months[i] ?? ""}
                onChange={(e) => {
                  const next = [...form.values.months];
                  next[i] = e.target.value;
                  form.set("months", next);
                }}
              />
            ))}
            {form.errors.months ? <p className="field-error col-span-full">{form.errors.months}</p> : null}
          </fieldset>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
