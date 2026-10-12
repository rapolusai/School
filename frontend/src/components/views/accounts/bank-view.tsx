"use client";

import { Pencil, Plus, Trash2, Upload } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { financialYearOfDate } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { ACCOUNT_PATTERN, IFSC_PATTERN } from "@/lib/payroll";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { BankAccountView, Book, OpeningBalancesView, PreviewLine, Reconciliation, StatementPreview } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { AccountsNav, PeriodFilter, Stat, useBankAccounts, YearFilter } from "./accounts-ui";

type Tab = "accounts" | "books" | "reconcile";
type AccountValues = { name: string; bankName: string; accountNumber: string; ifsc: string; active: boolean };

/** The same checks POST /api/accounts/bank-accounts makes. Exported for tests. */
export function validateBankAccount(values: AccountValues, editing: boolean): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "accounts.v.name";
  if (!values.bankName.trim()) problems.bankName = "accounts.v.name";
  const account = values.accountNumber.trim();
  if (!editing || account) {
    if (!ACCOUNT_PATTERN.test(account)) problems.accountNumber = "accounts.v.account";
  }
  if (!IFSC_PATTERN.test(values.ifsc.trim())) problems.ifsc = "accounts.v.ifsc";
  return problems;
}

/** /app/accounts/bank: the bank accounts and cash position, the cash and bank books, and reconciliation. */
export function BankView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const [tab, setTab] = useState<Tab>("accounts");
  const tabs: { key: Tab; label: string }[] = [
    { key: "accounts", label: t("accounts.bank.tabAccounts") },
    { key: "books", label: t("accounts.bank.tabBooks") },
    { key: "reconcile", label: t("accounts.bank.tabReconcile") },
  ];
  return (
    <>
      <PageHead eyebrow={t("accounts.eyebrow")} title={t("accounts.bank.title")} />
      <AccountsNav />
      <div className="subnav no-print" role="tablist" aria-label={t("accounts.bank.title")}>
        {tabs.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key} aria-current={tab === item.key ? "page" : undefined} onClick={() => setTab(item.key)} data-testid={`bank-tab-${item.key}`}>
            {item.label}
          </button>
        ))}
      </div>
      {tab === "accounts" ? <AccountsTab canManage={canManage} /> : tab === "books" ? <BooksTab /> : <ReconcileTab canManage={canManage} />}
    </>
  );
}

/* ------------------------------------------------------------------ accounts and opening balances */

function AccountsTab({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const accounts = useBankAccounts();
  const position = useApiData("accounts:cash-position", accountsApi.cashPosition);
  const [editing, setEditing] = useState<BankAccountView | null | "new">(null);
  const [deleting, setDeleting] = useState<BankAccountView | null>(null);
  const [year, setYear] = useState(financialYearOfDate(todayInIndia()));
  const data = accounts.data;
  const pos = position.data;
  return (
    <>
      {pos ? (
        <ul className="rep-kpis mb-4" data-testid="cash-position">
          <Stat label={t("accounts.bank.cashInHand")} value={formatPaise(pos.cashPaise)} testId="position-cash" />
          <Stat label={t("accounts.petty.title")} value={formatPaise(pos.pettyCashPaise)} />
          <Stat label={t("accounts.bank.inBank")} value={formatPaise(pos.bankPaise)} testId="position-bank" />
          <Stat label={t("accounts.bank.total")} value={formatPaise(pos.totalPaise)} sub={t("accounts.bank.asOf", { date: pos.asOf })} />
        </ul>
      ) : null}
      <section className="card" aria-labelledby="bank-accounts-title">
        <div className="card-head">
          <div>
            <h2 id="bank-accounts-title">{t("accounts.bank.accounts")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("accounts.bank.accountsSub")}</p>
          </div>
          {canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="bank-new">
              <Plus size={18} aria-hidden="true" />
              {t("accounts.bank.add")}
            </button>
          ) : null}
        </div>
        {accounts.error && !data ? (
          <ErrorState error={accounts.error} onRetry={accounts.reload} />
        ) : !data ? (
          <LoadingRows rows={3} />
        ) : data.length === 0 ? (
          <p className="empty" data-testid="bank-empty">
            {t("accounts.bank.empty")}
          </p>
        ) : (
          <div className="table-wrap">
            <table className="table" data-testid="bank-table">
              <thead>
                <tr>
                  <th scope="col">{t("accounts.col.name")}</th>
                  <th scope="col" className="hidden sm:table-cell">
                    {t("accounts.bank.bankName")}
                  </th>
                  <th scope="col">{t("accounts.bank.accountNumber")}</th>
                  <th scope="col" className="hidden md:table-cell">
                    {t("accounts.bank.ifsc")}
                  </th>
                  <th scope="col" className="r">
                    {t("accounts.col.balance")}
                  </th>
                  <th scope="col">{t("accounts.col.status")}</th>
                  {canManage ? <th scope="col" className="r">{t("accounts.col.actions")}</th> : null}
                </tr>
              </thead>
              <tbody>
                {data.map((a) => (
                  <tr key={a.id}>
                    <td className="font-semibold">{a.name}</td>
                    <td className="hidden sm:table-cell">{a.bankName}</td>
                    <td className="mono">{a.accountNumberMasked}</td>
                    <td className="mono hidden md:table-cell">{a.ifsc}</td>
                    <td className="r num">
                      {formatPaise(a.balancePaise)}
                      {a.unclearedCount > 0 ? <span className="block text-[12px] text-ink-3">{plural(t, "accounts.bank.uncleared", a.unclearedCount)}</span> : null}
                    </td>
                    <td>
                      <Pill tone={a.active ? "good" : "neutral"} dot>
                        {a.active ? t("accounts.active") : t("accounts.inactive")}
                      </Pill>
                    </td>
                    {canManage ? (
                      <td className="r whitespace-nowrap">
                        <button type="button" className="iconbtn" aria-label={t("accounts.bank.editFor", { name: a.name })} onClick={() => setEditing(a)}>
                          <Pencil size={16} aria-hidden="true" />
                        </button>
                        {!a.inUse ? (
                          <button type="button" className="iconbtn" aria-label={t("accounts.bank.deleteFor", { name: a.name })} onClick={() => setDeleting(a)}>
                            <Trash2 size={16} aria-hidden="true" />
                          </button>
                        ) : null}
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <OpeningBalances year={year} onYear={setYear} canManage={canManage} accountsVersion={data?.length ?? 0} />
      {editing ? (
        <AccountDialog
          account={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("accounts.bank.saved"));
            accounts.reload();
            position.reload();
          }}
        />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          open
          title={t("accounts.bank.deleteTitle")}
          body={t("accounts.bank.deleteBody", { name: deleting.name })}
          confirmLabel={t("common.delete")}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await accountsApi.deleteBankAccount(deleting.id);
            setDeleting(null);
            toast(t("common.deleted"));
            accounts.reload();
            position.reload();
          }}
        />
      ) : null}
    </>
  );
}

function OpeningBalances({ year, onYear, canManage, accountsVersion }: { year: string; onYear: (y: string) => void; canManage: boolean; accountsVersion: number }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const balances = useApiData(`accounts:opening:${year}:${accountsVersion}`, () => accountsApi.openingBalances(year));
  const data = balances.data;
  return (
    <section className="card" aria-labelledby="opening-title">
      <div className="card-head">
        <div>
          <h2 id="opening-title">{t("accounts.bank.opening")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("accounts.bank.openingSub")}</p>
        </div>
      </div>
      <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
        <YearFilter value={year} onChange={onYear} />
      </form>
      {balances.error && !data ? (
        <ErrorState error={balances.error} onRetry={balances.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : (
        <OpeningForm key={`${year}:${data.accounts.length}`} data={data} canManage={canManage} onSaved={() => {
          toast(t("accounts.bank.openingSaved"));
          balances.reload();
        }} />
      )}
    </section>
  );
}

function OpeningForm({ data, canManage, onSaved }: { data: OpeningBalancesView; canManage: boolean; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ cash: string; accounts: string[] }>({
    cash: rupeesInput(data.cashPaise),
    accounts: data.accounts.map((a) => rupeesInput(a.amountPaise)),
  });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (parseRupees(form.values.cash) === null) problems.cash = "accounts.v.amountOrZero";
    form.values.accounts.forEach((v, i) => {
      if (parseRupees(v) === null) problems[`accounts.${i}`] = "accounts.v.amountOrZero";
    });
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await accountsApi.saveOpeningBalances(data.financialYear, {
        balances: [
          { bankAccountId: null, amountPaise: parseRupees(form.values.cash) ?? 0 },
          ...data.accounts.map((a, i) => ({ bankAccountId: a.bankAccountId, amountPaise: parseRupees(form.values.accounts[i] ?? "") ?? 0 })),
        ],
      });
    });
    if (ok) onSaved();
  };
  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="opening-form">
      <FormAlert message={form.formError} />
      <div className="grid gap-3.5 sm:grid-cols-2 lg:grid-cols-3">
        <TextField label={t("accounts.bank.cashInHand")} name="cash" inputMode="decimal" value={form.values.cash} onChange={(e) => form.set("cash", e.target.value)} error={form.errors.cash} disabled={!canManage} />
        {data.accounts.map((a, i) => (
          <TextField
            key={a.bankAccountId}
            label={a.name}
            name={`accounts.${i}`}
            inputMode="decimal"
            value={form.values.accounts[i] ?? ""}
            onChange={(e) => {
              const next = [...form.values.accounts];
              next[i] = e.target.value;
              form.set("accounts", next);
            }}
            error={form.errors[`accounts.${i}`]}
            disabled={!canManage}
          />
        ))}
      </div>
      {canManage ? (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="opening-save">
            {form.submitting ? t("common.working") : t("common.save")}
          </button>
        </div>
      ) : null}
    </form>
  );
}

function AccountDialog({ account, onClose, onSaved }: { account: BankAccountView | null; onClose: () => void; onSaved: (a: BankAccountView) => void }) {
  const { t } = useI18n();
  const form = useForm<AccountValues>({
    name: account?.name ?? "",
    bankName: account?.bankName ?? "",
    accountNumber: "",
    ifsc: account?.ifsc ?? "",
    active: account?.active ?? true,
  });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateBankAccount(form.values, account !== null), t, event.currentTarget)) return;
    let saved: BankAccountView | undefined;
    const ok = await form.submit(t, async () => {
      const body = {
        name: form.values.name.trim(),
        bankName: form.values.bankName.trim(),
        accountNumber: form.values.accountNumber.trim(),
        ifsc: form.values.ifsc.trim().toUpperCase(),
        active: form.values.active,
      };
      saved = account ? await accountsApi.updateBankAccount(account.id, body) : await accountsApi.createBankAccount(body);
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog open onClose={onClose} title={account ? t("accounts.bank.editTitle") : t("accounts.bank.addTitle")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="bank-form">
        <FormAlert message={form.formError} />
        <TextField label={t("accounts.col.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={100} required data-autofocus hint={t("accounts.bank.nameHint")} />
        <TextField label={t("accounts.bank.bankName")} name="bankName" value={form.values.bankName} onChange={(e) => form.set("bankName", e.target.value)} error={form.errors.bankName} maxLength={100} required />
        <TextField
          label={t("accounts.bank.accountNumber")}
          name="accountNumber"
          inputMode="numeric"
          value={form.values.accountNumber}
          onChange={(e) => form.set("accountNumber", e.target.value)}
          error={form.errors.accountNumber}
          maxLength={18}
          hint={account ? t("accounts.bank.keepNumber", { masked: account.accountNumberMasked }) : undefined}
          required={!account}
        />
        <TextField label={t("accounts.bank.ifsc")} name="ifsc" value={form.values.ifsc} onChange={(e) => form.set("ifsc", e.target.value.toUpperCase())} error={form.errors.ifsc} maxLength={11} required />
        {account ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            <span>{t("accounts.active")}</span>
          </label>
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

/* ------------------------------------------------------------------ books */

function BooksTab() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const accounts = useBankAccounts();
  const [bankAccountId, setBankAccountId] = useState("");
  const [from, setFrom] = useState(`${today.slice(0, 7)}-01`);
  const [to, setTo] = useState(today);
  const book = useApiData(`accounts:book:${bankAccountId}:${from}:${to}`, () => accountsApi.book({ from, to, bankAccountId: bankAccountId || undefined }));
  const data = book.data;
  return (
    <section className="card" aria-labelledby="book-title">
      <div className="card-head">
        <h2 id="book-title">{data ? data.name : t("accounts.bank.tabBooks")}</h2>
      </div>
      <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
        <label className="field w-full sm:w-auto sm:min-w-44">
          <span className="field-label">{t("accounts.bank.book")}</span>
          <select className="input" name="bankAccountId" value={bankAccountId} onChange={(e) => setBankAccountId(e.target.value)} data-testid="book-select">
            <option value="">{t("accounts.bank.cashBook")}</option>
            {(accounts.data ?? []).map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
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
        <LoadingRows rows={5} />
      ) : (
        <BookTable book={data} locale={locale} />
      )}
    </section>
  );
}

/** The rows of a cash or bank book with opening and closing lines. Shared with the books report. */
export function BookTable({ book, locale }: { book: Book; locale: string }) {
  const { t } = useI18n();
  return (
    <div className="table-wrap">
      <table className="table report-table" data-testid="book-table">
        <thead>
          <tr>
            <th scope="col">{t("accounts.col.date")}</th>
            <th scope="col">{t("accounts.col.particulars")}</th>
            <th scope="col" className="hidden sm:table-cell">
              {t("accounts.col.reference")}
            </th>
            <th scope="col" className="r">
              {t("accounts.col.receipt")}
            </th>
            <th scope="col" className="r">
              {t("accounts.col.payment")}
            </th>
            <th scope="col" className="r">
              {t("accounts.col.balance")}
            </th>
            {book.bankAccountId ? <th scope="col">{t("accounts.bank.cleared")}</th> : null}
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{formatPlainDate(book.from, locale)}</td>
            <td className="text-ink-2">{t("accounts.book.opening")}</td>
            <td className="hidden sm:table-cell" />
            <td className="r" />
            <td className="r" />
            <td className="r num">{formatPaise(book.openingPaise)}</td>
            {book.bankAccountId ? <td /> : null}
          </tr>
          {book.rows.map((r, i) => (
            <tr key={`${r.refId}-${i}`}>
              <td className="whitespace-nowrap">{formatPlainDate(r.date, locale)}</td>
              <td>
                {r.particulars}
                {r.reference ? <span className="block text-[12px] text-ink-3 sm:hidden">{r.reference}</span> : null}
              </td>
              <td className="mono hidden sm:table-cell">{r.reference ?? ""}</td>
              <td className="r num">{r.receiptPaise ? formatPaise(r.receiptPaise) : ""}</td>
              <td className="r num">{r.paymentPaise ? formatPaise(r.paymentPaise) : ""}</td>
              <td className="r num">{formatPaise(r.balancePaise)}</td>
              {book.bankAccountId ? <td>{r.cleared ? t("accounts.bank.clearedYes") : t("accounts.bank.clearedNo")}</td> : null}
            </tr>
          ))}
          {book.rows.length === 0 ? (
            <tr>
              <td colSpan={book.bankAccountId ? 7 : 6} className="empty">
                {t("accounts.book.empty")}
              </td>
            </tr>
          ) : null}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan={3}>
              {t("accounts.book.closing")}
            </th>
            <td className="r num">{formatPaise(book.receiptsPaise)}</td>
            <td className="r num">{formatPaise(book.paymentsPaise)}</td>
            <td className="r num font-bold" data-testid="book-closing">
              {formatPaise(book.closingPaise)}
            </td>
            {book.bankAccountId ? <td /> : null}
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

/* ------------------------------------------------------------------ reconciliation */

function ReconcileTab({ canManage }: { canManage: boolean }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const today = todayInIndia();
  const accounts = useBankAccounts();
  const list = (accounts.data ?? []).filter((a) => a.active);
  const [chosen, setChosen] = useState("");
  const bankAccountId = chosen || list[0]?.id || "";
  const [statementDate, setStatementDate] = useState(today);
  const [ticked, setTicked] = useState<Set<string>>(() => new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const recon = useApiData(bankAccountId ? `accounts:recon:${bankAccountId}:${statementDate}` : null, () => accountsApi.reconciliation(bankAccountId, statementDate));
  const data = recon.data;

  const toggle = (id: string) =>
    setTicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const clear = async () => {
    if (!data) return;
    setBusy(true);
    setError(null);
    try {
      const result = await accountsApi.clear(bankAccountId, {
        statementDate,
        expenseIds: data.unclearedPayments.filter((p) => ticked.has(p.id)).map((p) => p.id),
        incomeIds: data.unclearedReceipts.filter((r) => ticked.has(r.id)).map((r) => r.id),
      });
      toast(plural(t, "accounts.recon.clearedCount", result.cleared));
      setTicked(new Set());
      recon.reload();
      accounts.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const tickedTotal = data
    ? data.unclearedReceipts.filter((r) => ticked.has(r.id)).reduce((a, r) => a + r.amountPaise, 0) - data.unclearedPayments.filter((p) => ticked.has(p.id)).reduce((a, p) => a + p.amountPaise, 0)
    : 0;

  if (accounts.data && list.length === 0) {
    return (
      <section className="card">
        <p className="empty" data-testid="recon-no-accounts">
          {t("accounts.recon.noAccounts")}
        </p>
      </section>
    );
  }

  return (
    <>
      <section className="card" aria-labelledby="recon-title">
        <div className="card-head">
          <div>
            <h2 id="recon-title">{t("accounts.recon.title")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("accounts.recon.sub")}</p>
          </div>
          {canManage && bankAccountId ? (
            <button type="button" className="btn" onClick={() => setImporting(true)} data-testid="recon-import">
              <Upload size={18} aria-hidden="true" />
              {t("accounts.recon.import")}
            </button>
          ) : null}
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("accounts.bank.account")}</span>
            <select className="input" name="bankAccountId" value={bankAccountId} onChange={(e) => { setChosen(e.target.value); setTicked(new Set()); }} data-testid="recon-account">
              {list.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto">
            <span className="field-label">{t("accounts.recon.statementDate")}</span>
            <input type="date" className="input" name="statementDate" value={statementDate} max={today} onChange={(e) => setStatementDate(e.target.value)} required />
          </label>
        </form>
        <FormAlert message={error} />
        {recon.error && !data ? (
          <ErrorState error={recon.error} onRetry={recon.reload} />
        ) : !data ? (
          <LoadingRows rows={4} />
        ) : (
          <>
            <ul className="rep-kpis mb-4" data-testid="recon-figures">
              <Stat label={t("accounts.recon.bookBalance")} value={formatPaise(data.bookBalancePaise)} />
              <Stat label={t("accounts.recon.unclearedPayments")} value={formatPaise(data.unclearedPaymentsPaise)} sub={plural(t, "accounts.recon.items", data.unclearedPayments.length)} />
              <Stat label={t("accounts.recon.unclearedReceipts")} value={formatPaise(data.unclearedReceiptsPaise)} sub={plural(t, "accounts.recon.items", data.unclearedReceipts.length)} />
              <Stat label={t("accounts.recon.expected")} value={formatPaise(data.expectedStatementBalancePaise)} testId="recon-expected" />
            </ul>
            <p className="mb-3 text-[13px] text-ink-2">{t("accounts.recon.howTo")}</p>
            <UnclearedTable title={t("accounts.recon.payments")} items={data.unclearedPayments} ticked={ticked} onToggle={toggle} canManage={canManage} locale={locale} testId="recon-payments" />
            <UnclearedTable title={t("accounts.recon.receipts")} items={data.unclearedReceipts} ticked={ticked} onToggle={toggle} canManage={canManage} locale={locale} testId="recon-receipts" />
            {canManage ? (
              <div className="mt-3 flex flex-wrap items-center justify-end gap-3">
                <span className="text-[13px] text-ink-2">{t("accounts.recon.tickedNet", { amount: formatPaise(tickedTotal) })}</span>
                <button type="button" className="btn btn-primary" disabled={busy || ticked.size === 0} onClick={clear} data-testid="recon-clear">
                  {busy ? t("common.working") : plural(t, "accounts.recon.clearTicked", ticked.size)}
                </button>
              </div>
            ) : null}
            {data.recentLines.length > 0 ? <RecentLines lines={data.recentLines} locale={locale} /> : null}
          </>
        )}
      </section>
      {importing && bankAccountId ? (
        <ImportDialog
          bankAccountId={bankAccountId}
          statementDate={statementDate}
          onClose={() => setImporting(false)}
          onImported={(result) => {
            setImporting(false);
            toast(t("accounts.recon.imported", { imported: result.imported, cleared: result.cleared, skipped: result.skipped }));
            recon.reload();
            accounts.reload();
          }}
        />
      ) : null}
    </>
  );
}

function UnclearedTable({ title, items, ticked, onToggle, canManage, locale, testId }: { title: string; items: Reconciliation["unclearedPayments"]; ticked: Set<string>; onToggle: (id: string) => void; canManage: boolean; locale: string; testId: string }) {
  const { t } = useI18n();
  return (
    <div className="mt-3">
      <h3 className="mb-2 text-[14px] font-semibold">{title}</h3>
      {items.length === 0 ? (
        <p className="empty">{t("accounts.recon.none")}</p>
      ) : (
        <div className="table-wrap">
          <table className="table" data-testid={testId}>
            <thead>
              <tr>
                {canManage ? <th scope="col">{t("accounts.recon.tick")}</th> : null}
                <th scope="col">{t("accounts.col.date")}</th>
                <th scope="col">{t("accounts.col.particulars")}</th>
                <th scope="col" className="hidden sm:table-cell">
                  {t("accounts.col.reference")}
                </th>
                <th scope="col" className="r">
                  {t("accounts.col.amount")}
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  {canManage ? (
                    <td>
                      <input type="checkbox" name={`clear-${item.id}`} aria-label={t("accounts.recon.tickFor", { label: item.label })} checked={ticked.has(item.id)} onChange={() => onToggle(item.id)} />
                    </td>
                  ) : null}
                  <td className="whitespace-nowrap">{formatPlainDate(item.date, locale)}</td>
                  <td>{item.label}</td>
                  <td className="mono hidden sm:table-cell">{item.reference ?? ""}</td>
                  <td className="r num">{formatPaise(item.amountPaise)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function RecentLines({ lines, locale }: { lines: Reconciliation["recentLines"]; locale: string }) {
  const { t } = useI18n();
  return (
    <details className="mt-4">
      <summary className="cursor-pointer text-[13.5px] font-semibold">{plural(t, "accounts.recon.recentLines", lines.length)}</summary>
      <div className="table-wrap mt-2">
        <table className="table" data-testid="recon-lines">
          <thead>
            <tr>
              <th scope="col">{t("accounts.col.date")}</th>
              <th scope="col">{t("accounts.col.description")}</th>
              <th scope="col" className="r">
                {t("accounts.recon.debit")}
              </th>
              <th scope="col" className="r">
                {t("accounts.recon.credit")}
              </th>
              <th scope="col">{t("accounts.recon.matchedTo")}</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.id}>
                <td className="whitespace-nowrap">{formatPlainDate(l.date, locale)}</td>
                <td>
                  {l.description}
                  {l.reference ? <span className="mono block text-[12px] text-ink-3">{l.reference}</span> : null}
                </td>
                <td className="r num">{l.debitPaise ? formatPaise(l.debitPaise) : ""}</td>
                <td className="r num">{l.creditPaise ? formatPaise(l.creditPaise) : ""}</td>
                <td>{l.matchedLabel ?? <span className="text-ink-3">{t("accounts.recon.unmatched")}</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

/** Picks a statement CSV, shows the preview with its matches, lets the accountant change them, then imports. */
function ImportDialog({ bankAccountId, statementDate, onClose, onImported }: { bankAccountId: string; statementDate: string; onClose: () => void; onImported: (r: { imported: number; cleared: number; skipped: number }) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<StatementPreview | null>(null);
  const [choices, setChoices] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    if (!file) {
      setError(t("accounts.recon.chooseFile"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await accountsApi.previewStatement(bankAccountId, file);
      setPreview(result);
      const initial: Record<number, string> = {};
      for (const line of result.lines) if (line.match && line.match.quality !== "NONE") initial[line.index] = matchKey(line.match.expenseId, line.match.incomeId);
      setChoices(initial);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const doImport = async () => {
    if (!preview) return;
    setBusy(true);
    setError(null);
    try {
      const result = await accountsApi.importStatement(bankAccountId, {
        statementDate,
        lines: preview.lines
          .filter((l) => !l.problem && !l.duplicate && l.date)
          .map((l) => {
            const choice = choices[l.index] ?? "";
            return {
              date: l.date as string,
              description: l.description,
              reference: l.reference,
              debitPaise: l.debitPaise,
              creditPaise: l.creditPaise,
              expenseId: choice.startsWith("E:") ? choice.slice(2) : null,
              incomeId: choice.startsWith("I:") ? choice.slice(2) : null,
            };
          }),
      });
      onImported(result);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const options = (line: PreviewLine) => {
    const candidates = line.debitPaise > 0 ? (preview?.payments ?? []) : (preview?.receipts ?? []);
    const prefix = line.debitPaise > 0 ? "E:" : "I:";
    return [{ value: "", label: t("accounts.recon.noMatch") }, ...candidates.map((c) => ({ value: `${prefix}${c.id}`, label: `${formatPlainDate(c.date, locale)} · ${c.label} · ${formatPaise(c.amountPaise)}` }))];
  };

  return (
    <Dialog open onClose={onClose} title={t("accounts.recon.importTitle")} description={t("accounts.recon.importDescription")} closeLabel={t("common.close")} variant="sheet">
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => { e.preventDefault(); if (preview) doImport(); else load(); }} noValidate data-testid="statement-form">
        <FormAlert message={error} />
        {!preview ? (
          <label className="field">
            <span className="field-label">{t("accounts.recon.file")}</span>
            <input type="file" className="input" name="file" accept=".csv,text/csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} data-testid="statement-file" />
            <span className="field-hint">{t("accounts.recon.fileHint")}</span>
          </label>
        ) : (
          <>
            <ul className="rep-kpis" data-testid="statement-figures">
              <Stat label={t("accounts.recon.matched")} value={String(preview.matched)} tone="good" />
              <Stat label={t("accounts.recon.probable")} value={String(preview.probable)} attention={preview.probable > 0} />
              <Stat label={t("accounts.recon.unmatchedCount")} value={String(preview.unmatched)} />
            </ul>
            <div className="table-wrap">
              <table className="table" data-testid="statement-preview">
                <thead>
                  <tr>
                    <th scope="col">{t("accounts.col.date")}</th>
                    <th scope="col">{t("accounts.col.description")}</th>
                    <th scope="col" className="r">
                      {t("accounts.recon.debit")}
                    </th>
                    <th scope="col" className="r">
                      {t("accounts.recon.credit")}
                    </th>
                    <th scope="col">{t("accounts.recon.matchedTo")}</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.lines.map((l) => (
                    <tr key={l.index} className={l.problem || l.duplicate ? "opacity-70" : undefined}>
                      <td className="whitespace-nowrap">{l.date ? formatPlainDate(l.date, locale) : "—"}</td>
                      <td>
                        {l.description}
                        {l.reference ? <span className="mono block text-[12px] text-ink-3">{l.reference}</span> : null}
                        {l.problem ? <span className="block text-[12px] text-bad">{l.problem}</span> : null}
                        {l.duplicate ? <span className="block text-[12px] text-warn">{t("accounts.recon.duplicate")}</span> : null}
                      </td>
                      <td className="r num">{l.debitPaise ? formatPaise(l.debitPaise) : ""}</td>
                      <td className="r num">{l.creditPaise ? formatPaise(l.creditPaise) : ""}</td>
                      <td>
                        {l.problem || l.duplicate ? (
                          ""
                        ) : (
                          <select className="input" name={`match.${l.index}`} aria-label={t("accounts.recon.matchFor", { description: l.description })} value={choices[l.index] ?? ""} onChange={(e) => setChoices((prev) => ({ ...prev, [l.index]: e.target.value }))}>
                            {options(l).map((o) => (
                              <option key={o.value} value={o.value}>
                                {o.label}
                              </option>
                            ))}
                          </select>
                        )}
                        {l.match?.quality === "PROBABLE" ? <span className="block text-[12px] text-warn">{t("accounts.recon.probableNote")}</span> : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          {preview ? (
            <button type="button" className="btn" onClick={() => { setPreview(null); setChoices({}); }}>
              {t("accounts.recon.anotherFile")}
            </button>
          ) : null}
          <button type="submit" className="btn btn-primary" disabled={busy} data-testid="statement-submit">
            {busy ? t("common.working") : preview ? t("accounts.recon.importNow") : t("accounts.recon.preview")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function matchKey(expenseId: string | null, incomeId: string | null): string {
  return expenseId ? `E:${expenseId}` : incomeId ? `I:${incomeId}` : "";
}
