"use client";

import { Paperclip, Plus, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useToast } from "@/components/ui/toast";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { accountsApi } from "@/lib/accounts-api";
import { useAuth } from "@/lib/auth";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { EXPENSE_STATUSES, type ExpenseList, type ExpenseRow, type ExpenseStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AccountsNav, ExpenseStatusPill } from "./accounts-ui";
import { ExpenseFormDialog } from "./expense-form";

/** /app/accounts/expenses: every expense newest first, filtered by status, and a new one. */
export function ExpensesView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const [status, setStatus] = useState<ExpenseStatus | "">("");
  const [creating, setCreating] = useState(false);
  const list = useApiData(`accounts:expenses:${status}`, () => accountsApi.expenses({ status: status || undefined }));
  const data = list.data;

  return (
    <>
      <PageHead
        eyebrow={t("accounts.eyebrow")}
        title={t("accounts.expenses.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} data-testid="expense-new">
              <Plus size={18} aria-hidden="true" />
              {t("accounts.expenses.new")}
            </button>
          ) : null
        }
      />
      <AccountsNav />

      <section className="card" aria-labelledby="expenses-title">
        <div className="card-head">
          <div>
            <h2 id="expenses-title">{t("accounts.expenses.list")}</h2>
            {data ? <p className="mt-1 text-sm text-ink-2">{counts(data, t)}</p> : null}
          </div>
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("accounts.col.status")}</span>
            <select className="input" name="status" value={status} onChange={(e) => setStatus(e.target.value as ExpenseStatus | "")}>
              <option value="">{t("accounts.expenses.allStatuses")}</option>
              {EXPENSE_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `accounts.status.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
        </form>
        {list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : data.expenses.length === 0 ? (
          <p className="empty" data-testid="expenses-empty">
            {canManage && !status ? t("accounts.expenses.emptyManage") : t("accounts.expenses.empty")}
          </p>
        ) : (
          <ExpensesTable rows={data.expenses} locale={locale} testId="expenses" />
        )}
      </section>

      {creating ? (
        <ExpenseFormDialog
          expense={null}
          onClose={() => setCreating(false)}
          onSaved={(saved, submitted) => {
            setCreating(false);
            toast(submitted ? t("accounts.expense.sent", { number: saved.number ?? "" }) : t("accounts.expense.savedDraft"));
            router.push(`/app/accounts/expenses/${saved.id}`);
          }}
        />
      ) : null}
    </>
  );
}

function counts(data: ExpenseList, t: (key: "accounts.expenses.counts", vars: Record<string, number>) => string): string {
  return t("accounts.expenses.counts", {
    drafts: data.draftCount,
    waiting: data.submittedCount,
    approved: data.approvedCount,
    paid: data.paidCount,
  });
}

/** The expenses table (a card list on phones), shared by the list and the approval queue. */
export function ExpensesTable({
  rows,
  locale,
  testId,
  action,
}: {
  rows: ExpenseRow[];
  locale: string;
  testId: string;
  /** an extra cell per row (the queue's buttons) */
  action?: (row: ExpenseRow) => React.ReactNode;
}) {
  const { t } = useI18n();
  const who = (row: ExpenseRow) => row.vendorName ?? t("accounts.expense.noVendor");
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid={`${testId}-table`}>
          <thead>
            <tr>
              <th scope="col">{t("accounts.col.date")}</th>
              <th scope="col">{t("accounts.col.number")}</th>
              <th scope="col">{t("accounts.col.description")}</th>
              <th scope="col">{t("accounts.col.head")}</th>
              <th scope="col">{t("accounts.col.vendor")}</th>
              <th scope="col" className="r">
                {t("accounts.col.total")}
              </th>
              <th scope="col">{t("accounts.col.status")}</th>
              {action ? <th scope="col" className="r">{t("accounts.col.actions")}</th> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-testid={`${testId}-row`}>
                <td className="whitespace-nowrap">{formatPlainDate(row.expenseDate, locale)}</td>
                <td className="mono whitespace-nowrap">{row.number ?? "—"}</td>
                <td>
                  <Link href={`/app/accounts/expenses/${row.id}`} className="link font-semibold">
                    {row.description}
                  </Link>
                  {row.attachmentCount > 0 ? (
                    <Paperclip size={14} aria-label={t("accounts.expense.attachments", { count: row.attachmentCount })} className="ml-1 inline text-ink-3" />
                  ) : null}
                  {row.budgetWarning ? (
                    <TriangleAlert size={14} aria-label={t("accounts.budget.warningShort")} className="ml-1 inline text-warn" />
                  ) : null}
                </td>
                <td>{row.headName}</td>
                <td>{who(row)}</td>
                <td className="r num font-semibold">{formatPaise(row.totalPaise)}</td>
                <td>
                  <ExpenseStatusPill status={row.status} />
                </td>
                {action ? <td className="r">{action(row)}</td> : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid={`${testId}-cards`}>
        {rows.map((row) => (
          <li key={row.id} data-testid={`${testId}-card`}>
            <Link href={`/app/accounts/expenses/${row.id}`} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block truncate font-semibold">{row.description}</b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {formatPlainDate(row.expenseDate, locale)} · {row.headName} · {who(row)}
                </span>
              </span>
              <span className="flex flex-col items-end gap-1">
                <span className="num font-semibold">{formatPaise(row.totalPaise)}</span>
                <ExpenseStatusPill status={row.status} />
              </span>
            </Link>
            {action ? <div className="flex justify-end gap-2 px-1 pb-2">{action(row)}</div> : null}
          </li>
        ))}
      </ul>
    </>
  );
}
