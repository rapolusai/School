"use client";

import { Plus } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { accountsApi } from "@/lib/accounts-api";
import { useAuth } from "@/lib/auth";
import { formatPaise, formatPlainDate, parseRupees, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { PettyCashBook, PettyKind } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { AccountsNav, PeriodFilter, Stat, useHeads } from "./accounts-ui";

type PettyValues = { entryDate: string; kind: PettyKind; amount: string; headId: string; description: string };

/** The same checks POST /api/accounts/petty-cash makes. Exported for tests. */
export function validatePetty(values: PettyValues, today: string): Problems {
  const problems: Problems = {};
  if (!values.entryDate) problems.entryDate = "accounts.v.date";
  else if (values.entryDate > today) problems.entryDate = "accounts.v.futureDate";
  const amount = parseRupees(values.amount);
  if (amount === null || amount <= 0) problems.amount = "accounts.v.amount";
  if (values.kind === "SPENT" && !values.headId) problems.headId = "accounts.v.head";
  if (!values.description.trim()) problems.description = "accounts.v.description";
  return problems;
}

/** /app/accounts/petty-cash: the box's float, balance and running book, with top-ups and spends. */
export function PettyCashView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const [from, setFrom] = useState(`${today.slice(0, 7)}-01`);
  const [to, setTo] = useState(today);
  const [adding, setAdding] = useState<PettyKind | null>(null);
  const book = useApiData(`accounts:petty:${from}:${to}`, () => accountsApi.pettyCash({ from, to }));
  const data = book.data;
  return (
    <>
      <PageHead
        eyebrow={t("accounts.eyebrow")}
        title={t("accounts.petty.title")}
        actions={
          canManage ? (
            <>
              <button type="button" className="btn" onClick={() => setAdding("TOP_UP")} data-testid="petty-topup">
                <Plus size={18} aria-hidden="true" />
                {t("accounts.petty.topUp")}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => setAdding("SPENT")} data-testid="petty-spend">
                <Plus size={18} aria-hidden="true" />
                {t("accounts.petty.spend")}
              </button>
            </>
          ) : null
        }
      />
      <AccountsNav />
      {data ? (
        <ul className="rep-kpis mb-4" data-testid="petty-figures">
          <Stat label={t("accounts.petty.float")} value={formatPaise(data.floatPaise)} />
          <Stat label={t("accounts.petty.balance")} value={formatPaise(data.balancePaise)} testId="petty-balance" />
          <Stat label={t("accounts.petty.topUpNeeded")} value={formatPaise(data.topUpNeededPaise)} attention={data.topUpNeededPaise > 0} />
          <Stat label={t("accounts.petty.spentInPeriod")} value={formatPaise(data.spentPaise)} />
        </ul>
      ) : null}
      <section className="card" aria-labelledby="petty-title">
        <div className="card-head">
          <h2 id="petty-title">{t("accounts.petty.book")}</h2>
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
        {book.error && !data ? (
          <ErrorState error={book.error} onRetry={book.reload} />
        ) : !data ? (
          <LoadingRows rows={4} />
        ) : data.entries.length === 0 ? (
          <p className="empty" data-testid="petty-empty">
            {t("accounts.petty.empty")}
          </p>
        ) : (
          <div className="table-wrap">
            <table className="table" data-testid="petty-table">
              <thead>
                <tr>
                  <th scope="col">{t("accounts.col.date")}</th>
                  <th scope="col">{t("accounts.col.description")}</th>
                  <th scope="col" className="hidden sm:table-cell">
                    {t("accounts.col.head")}
                  </th>
                  <th scope="col" className="r">
                    {t("accounts.petty.in")}
                  </th>
                  <th scope="col" className="r">
                    {t("accounts.petty.out")}
                  </th>
                  <th scope="col" className="r">
                    {t("accounts.col.balance")}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>{formatPlainDate(data.from, locale)}</td>
                  <td className="text-ink-2" colSpan={2}>
                    {t("accounts.book.opening")}
                  </td>
                  <td className="r" />
                  <td className="r" />
                  <td className="r num">{formatPaise(data.openingPaise)}</td>
                </tr>
                {data.entries.map((e) => (
                  <tr key={e.id}>
                    <td className="whitespace-nowrap">{formatPlainDate(e.date, locale)}</td>
                    <td>
                      {e.description}
                      <span className="block text-[12px] text-ink-3 sm:hidden">{e.headName ?? ""}</span>
                    </td>
                    <td className="hidden sm:table-cell">{e.headName ?? "—"}</td>
                    <td className="r num">{e.kind === "TOP_UP" ? formatPaise(e.amountPaise) : ""}</td>
                    <td className="r num">{e.kind === "SPENT" ? formatPaise(e.amountPaise) : ""}</td>
                    <td className="r num font-semibold">{formatPaise(e.balanceAfterPaise)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {adding ? (
        <PettyDialog
          kind={adding}
          onClose={() => setAdding(null)}
          onSaved={() => {
            setAdding(null);
            toast(t("accounts.petty.recorded"));
            book.reload();
          }}
        />
      ) : null}
    </>
  );
}

function PettyDialog({ kind, onClose, onSaved }: { kind: PettyKind; onClose: () => void; onSaved: (book: PettyCashBook) => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const heads = useHeads();
  const form = useForm<PettyValues>({ entryDate: today, kind, amount: "", headId: "", description: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validatePetty(form.values, today), t, event.currentTarget)) return;
    let saved: PettyCashBook | undefined;
    const ok = await form.submit(t, async () => {
      saved = await accountsApi.recordPettyCash({
        entryDate: form.values.entryDate,
        kind,
        amountPaise: parseRupees(form.values.amount) ?? 0,
        headId: kind === "SPENT" ? form.values.headId : null,
        description: form.values.description.trim(),
      });
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog open onClose={onClose} title={kind === "TOP_UP" ? t("accounts.petty.topUpTitle") : t("accounts.petty.spendTitle")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="petty-form">
        <FormAlert message={form.formError} />
        <TextField label={t("accounts.col.date")} name="entryDate" type="date" value={form.values.entryDate} max={today} onChange={(e) => form.set("entryDate", e.target.value)} error={form.errors.entryDate} required data-autofocus />
        <TextField label={t("accounts.col.amount")} name="amount" inputMode="decimal" value={form.values.amount} onChange={(e) => form.set("amount", e.target.value)} error={form.errors.amount} hint={t("accounts.expense.amountHint")} required />
        {kind === "SPENT" ? (
          <SelectField
            label={t("accounts.col.head")}
            name="headId"
            value={form.values.headId}
            onChange={(e) => form.set("headId", e.target.value)}
            options={[{ value: "", label: t("accounts.expense.pickHead") }, ...(heads.data ?? []).filter((h) => h.kind === "EXPENSE" && h.active).map((h) => ({ value: h.id, label: `${h.code} · ${h.name}` }))]}
            error={form.errors.headId}
            required
          />
        ) : null}
        <TextField label={t("accounts.col.description")} name="description" value={form.values.description} onChange={(e) => form.set("description", e.target.value)} error={form.errors.description} maxLength={300} required />
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
