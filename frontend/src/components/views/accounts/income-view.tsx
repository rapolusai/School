"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { blank, validateIncome, type IncomeValues } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { useAuth } from "@/lib/auth";
import { formatPaise, formatPlainDate, parseRupees, todayInIndia } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { INCOME_KINDS, PAY_MODES, type IncomeKind, type IncomeReceiptView, type PayMode } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { AccountsNav, PeriodFilter, useBankAccounts, useHeads, useModeLabel } from "./accounts-ui";

const DAYS_BACK = 90;

function daysAgo(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

/** /app/accounts/income: donations, grants, rentals and other receipts of a period, and a new receipt. */
export function IncomeView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  const today = todayInIndia();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const [from, setFrom] = useState(daysAgo(today, DAYS_BACK));
  const [to, setTo] = useState(today);
  const [recording, setRecording] = useState(false);
  const list = useApiData(`accounts:income:${from}:${to}`, () => accountsApi.income({ from, to }));
  const data = list.data;

  return (
    <>
      <PageHead
        eyebrow={t("accounts.eyebrow")}
        title={t("accounts.income.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setRecording(true)} data-testid="income-new">
              <Plus size={18} aria-hidden="true" />
              {t("accounts.income.new")}
            </button>
          ) : null
        }
      />
      <AccountsNav />
      <section className="card" aria-labelledby="income-title">
        <div className="card-head">
          <div>
            <h2 id="income-title">{t("accounts.income.list")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("accounts.income.sub")}</p>
          </div>
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
        {list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={4} />
        ) : data.length === 0 ? (
          <p className="empty" data-testid="income-empty">
            {t("accounts.income.empty")}
          </p>
        ) : (
          <>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="income-table">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.date")}</th>
                    <th scope="col">{t("accounts.col.receiptNo")}</th>
                    <th scope="col">{t("accounts.col.payer")}</th>
                    <th scope="col">{t("accounts.col.kind")}</th>
                    <th scope="col">{t("accounts.col.head")}</th>
                    <th scope="col">{t("accounts.col.mode")}</th>
                    <th scope="col" className="r">
                      {t("accounts.col.amount")}
                    </th>
                    <th scope="col">{t("accounts.col.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.map((r) => (
                    <tr key={r.id}>
                      <td className="whitespace-nowrap">{formatPlainDate(r.receivedOn, locale)}</td>
                      <td className="mono whitespace-nowrap">
                        <Link href={`/app/accounts/income/${r.id}`} className="link font-semibold">
                          {r.receiptNo}
                        </Link>
                      </td>
                      <td>{r.payerName}</td>
                      <td>{translateOr(t, `accounts.incomeKind.${r.kind}`, r.kind)}</td>
                      <td>{r.headName}</td>
                      <td>{modeLabel(r.mode)}</td>
                      <td className="r num font-semibold">{formatPaise(r.amountPaise)}</td>
                      <td>
                        <Pill tone={r.status === "ISSUED" ? "good" : "bad"} dot>
                          {translateOr(t, `accounts.receiptStatus.${r.status}`, r.status)}
                        </Pill>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" data-testid="income-cards">
              {data.map((r) => (
                <li key={r.id}>
                  <Link href={`/app/accounts/income/${r.id}`} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">{r.payerName}</b>
                      <span className="block truncate text-[12.5px] text-ink-3">
                        {r.receiptNo} · {formatPlainDate(r.receivedOn, locale)} · {r.headName}
                      </span>
                    </span>
                    <span className="flex flex-col items-end gap-1">
                      <span className="num font-semibold">{formatPaise(r.amountPaise)}</span>
                      <Pill tone={r.status === "ISSUED" ? "good" : "bad"} dot>
                        {translateOr(t, `accounts.receiptStatus.${r.status}`, r.status)}
                      </Pill>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
      {recording ? (
        <IncomeDialog
          onClose={() => setRecording(false)}
          onRecorded={(receipt) => {
            setRecording(false);
            toast(t("accounts.income.recorded", { number: receipt.receiptNo }));
            router.push(`/app/accounts/income/${receipt.id}`);
          }}
        />
      ) : null}
    </>
  );
}

/** Records a receipt: kind, income head, date, amount, who paid (PAN for an 80G donation), how, and a reference. */
export function IncomeDialog({ onClose, onRecorded }: { onClose: () => void; onRecorded: (receipt: IncomeReceiptView) => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const heads = useHeads();
  const banks = useBankAccounts();
  const settings = useApiData("accounts:settings", accountsApi.settings);
  const modeLabel = useModeLabel();
  const form = useForm<IncomeValues>({
    kind: "DONATION",
    headId: "",
    receivedOn: today,
    amount: "",
    payerName: "",
    payerPan: "",
    payerAddress: "",
    mode: "CASH",
    bankAccountId: "",
    reference: "",
    description: "",
  });
  const incomeHeads = (heads.data ?? []).filter((h) => h.kind === "INCOME" && h.active && !h.feeHeadId && h.systemCode !== "FEES");
  const eightyG = Boolean(settings.data?.donation80gRegistered);
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateIncome(form.values, today, eightyG), t, event.currentTarget)) return;
    let saved: IncomeReceiptView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await accountsApi.recordIncome({
        kind: form.values.kind as IncomeKind,
        headId: form.values.headId,
        receivedOn: form.values.receivedOn,
        amountPaise: parseRupees(form.values.amount) ?? 0,
        payerName: form.values.payerName.trim(),
        payerPan: blank(form.values.payerPan, true),
        payerAddress: blank(form.values.payerAddress),
        mode: form.values.mode,
        bankAccountId: form.values.mode === "CASH" ? null : form.values.bankAccountId || null,
        reference: blank(form.values.reference),
        description: blank(form.values.description),
      });
    });
    if (ok && saved) onRecorded(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("accounts.income.newTitle")} description={t("accounts.income.newDescription")} closeLabel={t("common.close")} variant="sheet">
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="income-form">
        <FormAlert message={form.formError} />
        <div className="grid gap-3.5 sm:grid-cols-2">
          <SelectField
            label={t("accounts.col.kind")}
            name="kind"
            value={form.values.kind}
            onChange={(e) => form.set("kind", e.target.value)}
            options={INCOME_KINDS.map((k) => ({ value: k, label: translateOr(t, `accounts.incomeKind.${k}`, k) }))}
            data-autofocus
          />
          <SelectField
            label={t("accounts.col.head")}
            name="headId"
            value={form.values.headId}
            onChange={(e) => form.set("headId", e.target.value)}
            options={[{ value: "", label: t("accounts.expense.pickHead") }, ...incomeHeads.map((h) => ({ value: h.id, label: `${h.code} · ${h.name}` }))]}
            error={form.errors.headId}
            required
          />
          <TextField label={t("accounts.col.date")} name="receivedOn" type="date" value={form.values.receivedOn} max={today} onChange={(e) => form.set("receivedOn", e.target.value)} error={form.errors.receivedOn} required />
          <TextField label={t("accounts.col.amount")} name="amount" inputMode="decimal" value={form.values.amount} onChange={(e) => form.set("amount", e.target.value)} error={form.errors.amount} hint={t("accounts.expense.amountHint")} required />
          <TextField label={t("accounts.col.payer")} name="payerName" value={form.values.payerName} onChange={(e) => form.set("payerName", e.target.value)} error={form.errors.payerName} maxLength={200} required />
          <TextField
            label={t("accounts.income.payerPan")}
            name="payerPan"
            value={form.values.payerPan}
            onChange={(e) => form.set("payerPan", e.target.value.toUpperCase())}
            error={form.errors.payerPan}
            maxLength={10}
            hint={eightyG && form.values.kind === "DONATION" ? t("accounts.income.panFor80g") : t("accounts.income.panHint")}
            required={eightyG && form.values.kind === "DONATION"}
          />
          <SelectField
            label={t("accounts.col.mode")}
            name="mode"
            value={form.values.mode}
            onChange={(e) => form.set("mode", e.target.value as PayMode)}
            options={PAY_MODES.map((m) => ({ value: m, label: modeLabel(m) }))}
          />
          {form.values.mode !== "CASH" ? (
            <SelectField
              label={t("accounts.bank.account")}
              name="bankAccountId"
              value={form.values.bankAccountId}
              onChange={(e) => form.set("bankAccountId", e.target.value)}
              options={[{ value: "", label: t("accounts.bank.pickAccount") }, ...(banks.data ?? []).filter((b) => b.active).map((b) => ({ value: b.id, label: `${b.name} · ${b.accountNumberMasked}` }))]}
              error={form.errors.bankAccountId}
              required
            />
          ) : null}
          <TextField label={t("accounts.col.reference")} name="reference" value={form.values.reference} onChange={(e) => form.set("reference", e.target.value)} error={form.errors.reference} maxLength={100} />
        </div>
        <TextField label={t("accounts.income.payerAddress")} name="payerAddress" value={form.values.payerAddress} onChange={(e) => form.set("payerAddress", e.target.value)} maxLength={300} />
        <TextAreaField label={t("accounts.col.description")} name="description" value={form.values.description} onChange={(e) => form.set("description", e.target.value)} rows={2} maxLength={500} />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("accounts.income.record")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
