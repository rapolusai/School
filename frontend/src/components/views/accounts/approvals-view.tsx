"use client";

import { Check, X } from "lucide-react";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { accountsApi } from "@/lib/accounts-api";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { ExpenseRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AccountsNav } from "./accounts-ui";
import { RejectDialog } from "./expense-view";
import { ExpensesTable } from "./expenses-view";

/**
 * /app/accounts/approvals: the expenses waiting for approval, oldest first. Each one the signed-in approver may act
 * on has Approve (or Pass on, above their limit) and Reject buttons; the others wait for someone with a larger limit.
 */
export function ApprovalsView() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const queue = useApiData("accounts:queue", accountsApi.queue);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<ExpenseRow | null>(null);
  const data = queue.data;

  const approve = async (row: ExpenseRow) => {
    setBusy(row.id);
    setError(null);
    try {
      const saved = await accountsApi.approveExpense(row.id, null);
      toast(saved.status === "APPROVED" ? t("accounts.expense.approved") : t("accounts.expense.passedOn"));
      queue.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };

  const aboveLimit = (row: ExpenseRow) => data?.myLimitPaise !== null && data !== undefined && row.totalPaise > (data.myLimitPaise ?? 0);

  return (
    <>
      <PageHead eyebrow={t("accounts.eyebrow")} title={t("accounts.approvals.title")} />
      <AccountsNav />
      <FormAlert message={error} />

      <section className="card" aria-labelledby="queue-title">
        <div className="card-head">
          <div>
            <h2 id="queue-title">{data ? plural(t, "accounts.approvals.count", data.expenses.length) : t("accounts.approvals.list")}</h2>
            <p className="mt-1 text-sm text-ink-2" data-testid="queue-limit">
              {data
                ? data.limited
                  ? t("accounts.approvals.myLimit", { limit: formatPaise(data.myLimitPaise ?? 0) })
                  : t("accounts.approvals.noLimit")
                : ""}
            </p>
          </div>
        </div>
        {queue.error && !data ? (
          <ErrorState error={queue.error} onRetry={queue.reload} />
        ) : !data ? (
          <LoadingRows rows={4} />
        ) : data.expenses.length === 0 ? (
          <p className="empty" data-testid="queue-empty">
            {t("accounts.approvals.empty")}
          </p>
        ) : (
          <ExpensesTable
            rows={data.expenses}
            locale={locale}
            testId="queue"
            action={(row) =>
              data.actionable.includes(row.id) ? (
                <span className="inline-flex flex-wrap justify-end gap-1.5">
                  <button
                    type="button"
                    className="btn btn-sm btn-primary"
                    disabled={busy !== null}
                    onClick={() => void approve(row)}
                    data-testid="queue-approve"
                  >
                    <Check size={16} aria-hidden="true" />
                    {aboveLimit(row) ? t("accounts.expense.passOn") : t("accounts.expense.approve")}
                  </button>
                  <button type="button" className="btn btn-sm" disabled={busy !== null} onClick={() => setRejecting(row)} data-testid="queue-reject">
                    <X size={16} aria-hidden="true" />
                    {t("accounts.expense.reject")}
                  </button>
                </span>
              ) : (
                <span className="text-[12.5px] text-ink-3">{t("accounts.approvals.waitingForOther")}</span>
              )
            }
          />
        )}
      </section>

      {rejecting ? (
        <RejectDialog
          expense={rejecting}
          onClose={() => setRejecting(null)}
          onRejected={() => {
            setRejecting(null);
            toast(t("accounts.expense.rejectedToast"));
            queue.reload();
          }}
        />
      ) : null}
    </>
  );
}
