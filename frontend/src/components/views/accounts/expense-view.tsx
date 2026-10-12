"use client";

import { Check, Pencil, Send, Trash2, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { AttachmentList } from "@/components/views/files/attachments";
import { validatePaid, type PaidValues } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { PAY_MODES, type ExpenseDetail, type PayMode } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { AccountsNav, BudgetPill, ExpenseStatusPill, useBankAccounts, useModeLabel } from "./accounts-ui";
import { ExpenseFormDialog } from "./expense-form";

/**
 * /app/accounts/expenses/[id]: an expense with its bills, approval history, budget position and what the signed-in
 * person may do next: edit, send, approve or pass on, reject with a reason, record the payment, or delete a draft.
 */
export function ExpenseView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  const canManage = hasPermission(me, PERMISSIONS.accountsManage);
  const canApprove = hasPermission(me, PERMISSIONS.accountsApprove);
  const detail = useApiData(`accounts:expense:${id}`, () => accountsApi.expense(id));
  const [editing, setEditing] = useState(false);
  const [paying, setPaying] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fresh, setFresh] = useState<ExpenseDetail | null>(null);
  const e = fresh ?? detail.data;

  const run = async (action: () => Promise<ExpenseDetail>, message: string) => {
    setBusy(true);
    setError(null);
    try {
      setFresh(await action());
      toast(message);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const title = e ? e.description : t("accounts.expense.title");
  return (
    <>
      <PageHead
        eyebrow={e?.number ?? t("accounts.expense.draft")}
        title={title}
        actions={
          e ? (
            <>
              {canManage && e.canEdit ? (
                <button type="button" className="btn" onClick={() => setEditing(true)} disabled={busy}>
                  <Pencil size={18} aria-hidden="true" />
                  {t("common.edit")}
                </button>
              ) : null}
              {canManage && e.canEdit ? (
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={busy}
                  data-testid="expense-submit"
                  onClick={() => void run(() => accountsApi.submitExpense(e.id), t("accounts.expense.sentShort"))}
                >
                  <Send size={18} aria-hidden="true" />
                  {t("accounts.expense.sendForApproval")}
                </button>
              ) : null}
              {canApprove && e.status === "SUBMITTED" && e.myDecision !== "NOT_YOURS" ? (
                <>
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={busy}
                    data-testid="expense-approve"
                    onClick={() =>
                      void run(
                        () => accountsApi.approveExpense(e.id, null),
                        e.myDecision === "ESCALATE" ? t("accounts.expense.passedOn") : t("accounts.expense.approved"),
                      )
                    }
                  >
                    <Check size={18} aria-hidden="true" />
                    {e.myDecision === "ESCALATE" ? t("accounts.expense.passOn") : t("accounts.expense.approve")}
                  </button>
                  <button type="button" className="btn" disabled={busy} onClick={() => setRejecting(true)} data-testid="expense-reject">
                    <X size={18} aria-hidden="true" />
                    {t("accounts.expense.reject")}
                  </button>
                </>
              ) : null}
              {canManage && e.status === "APPROVED" ? (
                <button type="button" className="btn btn-primary" disabled={busy} onClick={() => setPaying(true)} data-testid="expense-pay">
                  {t("accounts.expense.markPaid")}
                </button>
              ) : null}
              {canManage && e.canEdit ? (
                <button type="button" className="btn btn-danger" disabled={busy} onClick={() => setDeleting(true)}>
                  <Trash2 size={18} aria-hidden="true" />
                  {t("common.delete")}
                </button>
              ) : null}
            </>
          ) : null
        }
      />
      <AccountsNav />
      <FormAlert message={error} />

      {detail.error && !e ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !e ? (
        <LoadingRows rows={6} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
          <section className="card" aria-labelledby="expense-facts" data-testid="expense-detail">
            <div className="card-head">
              <h2 id="expense-facts">{t("accounts.expense.details")}</h2>
              <ExpenseStatusPill status={e.status} />
            </div>
            {e.status === "REJECTED" && e.rejectReason ? (
              <div className="alert alert-bad" role="note" data-testid="expense-rejected">
                <span>{t("accounts.expense.rejectedNote", { name: e.rejectedByName ?? "", reason: e.rejectReason })}</span>
              </div>
            ) : null}
            {e.myDecision === "NOT_YOURS" ? (
              <div className="alert alert-info" role="note">
                <span>{t("accounts.expense.notYours")}</span>
              </div>
            ) : null}
            {e.myDecision === "NOBODY_ABOVE" ? (
              <div className="alert alert-warn" role="note">
                <span>{t("accounts.expense.nobodyAbove")}</span>
              </div>
            ) : null}
            {e.escalatedAbovePaise !== null && e.status === "SUBMITTED" ? (
              <div className="alert alert-info" role="note" data-testid="expense-escalated">
                <span>{t("accounts.expense.escalatedNote", { limit: formatPaise(e.escalatedAbovePaise) })}</span>
              </div>
            ) : null}
            <dl className="kv">
              <dt>{t("accounts.col.date")}</dt>
              <dd>{formatPlainDate(e.expenseDate, locale)}</dd>
              <dt>{t("accounts.col.head")}</dt>
              <dd>
                {e.headCode} · {e.headName}
              </dd>
              {e.costCentreName ? (
                <>
                  <dt>{t("accounts.col.costCentre")}</dt>
                  <dd>{e.costCentreName}</dd>
                </>
              ) : null}
              <dt>{t("accounts.col.vendor")}</dt>
              <dd>{e.vendorName ?? t("accounts.expense.noVendor")}</dd>
              {e.billNumber ? (
                <>
                  <dt>{t("accounts.expense.billNumber")}</dt>
                  <dd className="mono">{e.billNumber}</dd>
                </>
              ) : null}
              <dt>{t("accounts.expense.amount")}</dt>
              <dd className="num">{formatPaise(e.amountPaise)}</dd>
              <dt>{t("accounts.expense.gst")}</dt>
              <dd className="num">{formatPaise(e.gstPaise)}</dd>
              <dt>{t("accounts.col.total")}</dt>
              <dd className="num font-semibold" data-testid="expense-total">
                {formatPaise(e.totalPaise)}
              </dd>
              {e.tdsSection ? (
                <>
                  <dt>{t("accounts.expense.tdsAt", { section: e.tdsSection, rate: String(e.tdsRatePercent ?? 0) })}</dt>
                  <dd className="num">{formatPaise(e.tdsPaise)}</dd>
                  <dt>{t("accounts.expense.payable")}</dt>
                  <dd className="num font-semibold">{formatPaise(e.paidPaise)}</dd>
                </>
              ) : null}
              <dt>{t("accounts.expense.requestedBy")}</dt>
              <dd>
                {e.requestedByName} · {formatDateTime(e.createdAt, locale)}
              </dd>
              {e.status === "PAID" ? (
                <>
                  <dt>{t("accounts.expense.paidOn")}</dt>
                  <dd>
                    {formatPlainDate(e.paidOn, locale)} · {modeLabel(e.paymentMode)}
                    {e.bankAccountName ? ` · ${e.bankAccountName}` : ""}
                    {e.paymentReference ? ` · ${e.paymentReference}` : ""}
                  </dd>
                  <dt>{t("accounts.expense.paidBy")}</dt>
                  <dd>{e.paidByName}</dd>
                  {e.clearedOn ? (
                    <>
                      <dt>{t("accounts.bank.clearedOn")}</dt>
                      <dd>{formatPlainDate(e.clearedOn, locale)}</dd>
                    </>
                  ) : null}
                </>
              ) : null}
            </dl>
            {e.status === "PAID" ? (
              <p className="mt-3 text-[13px] text-ink-3" data-testid="expense-locked">
                {t("accounts.expense.locked")}
              </p>
            ) : null}
          </section>

          <div className="flex flex-col gap-4">
            <section className="card" aria-labelledby="expense-budget">
              <div className="card-head">
                <h2 id="expense-budget">{t("accounts.budget.title")}</h2>
                <BudgetPill status={e.budget.status} percent={e.budget.status === "NONE" ? undefined : e.budget.percentAfter} />
              </div>
              {e.budget.status === "NONE" ? (
                <p className="text-sm text-ink-2">{t("accounts.budget.noneForHead")}</p>
              ) : (
                <dl className="kv text-[13.5px]">
                  <dt>{t("accounts.budget.year")}</dt>
                  <dd className="num">{formatPaise(e.budget.budgetPaise)}</dd>
                  <dt>{t("accounts.budget.committed")}</dt>
                  <dd className="num">{formatPaise(e.budget.committedPaise)}</dd>
                  <dt>{t("accounts.budget.after")}</dt>
                  <dd className="num">{formatPaise(e.budget.afterPaise)}</dd>
                </dl>
              )}
              {e.budget.status === "OVER" && e.budget.mode === "BLOCK" && e.status === "SUBMITTED" ? (
                <p className="mt-2 text-[13px] text-bad">{t("accounts.budget.blocks")}</p>
              ) : e.budget.status !== "OK" && e.budget.status !== "NONE" ? (
                <p className="mt-2 text-[13px] text-warn">{t("accounts.budget.warns")}</p>
              ) : null}
            </section>

            <section className="card" aria-labelledby="expense-files">
              <div className="card-head">
                <h2 id="expense-files">{t("accounts.expense.bills")}</h2>
              </div>
              {e.files.length === 0 ? (
                <p className="empty">{t("accounts.expense.noBills")}</p>
              ) : (
                <AttachmentList
                  files={e.files}
                  testId="expense-files"
                  onRemove={
                    canManage && e.status !== "PAID"
                      ? (file) => void run(() => accountsApi.removeAttachment(e.id, file.id), t("accounts.expense.billRemoved"))
                      : undefined
                  }
                />
              )}
            </section>

            <section className="card" aria-labelledby="expense-history">
              <div className="card-head">
                <h2 id="expense-history">{t("accounts.expense.history")}</h2>
              </div>
              {e.approvals.length === 0 ? (
                <p className="empty">{t("accounts.expense.noHistory")}</p>
              ) : (
                <ol className="flex flex-col gap-2 text-[13.5px]" data-testid="expense-approvals">
                  {e.approvals.map((a) => (
                    <li key={a.id}>
                      <b>{translateOr(t, `accounts.approval.${a.action}`, a.action)}</b> · {a.approverName}
                      {a.limitPaise !== null ? ` (${t("accounts.expense.limit", { limit: formatPaise(a.limitPaise) })})` : ""} ·{" "}
                      {formatDateTime(a.at, locale)}
                      {a.note ? <span className="block text-ink-2">{a.note}</span> : null}
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </div>
        </div>
      )}

      {editing && e ? (
        <ExpenseFormDialog
          expense={e}
          onClose={() => setEditing(false)}
          onSaved={(saved, submitted) => {
            setEditing(false);
            setFresh(saved);
            toast(submitted ? t("accounts.expense.sentShort") : t("accounts.expense.savedDraft"));
          }}
        />
      ) : null}
      {paying && e ? (
        <PaidDialog
          expense={e}
          onClose={() => setPaying(false)}
          onPaid={(saved) => {
            setPaying(false);
            setFresh(saved);
            toast(t("accounts.expense.paidToast"));
          }}
        />
      ) : null}
      {rejecting && e ? (
        <RejectDialog
          expense={e}
          onClose={() => setRejecting(false)}
          onRejected={(saved) => {
            setRejecting(false);
            setFresh(saved);
            toast(t("accounts.expense.rejectedToast"));
          }}
        />
      ) : null}
      {deleting && e ? (
        <ConfirmDialog
          open
          title={t("accounts.expense.deleteTitle")}
          body={t("accounts.expense.deleteBody")}
          confirmLabel={t("common.delete")}
          onClose={() => setDeleting(false)}
          onConfirm={async () => {
            await accountsApi.deleteExpense(e.id);
            toast(t("accounts.expense.deleted"));
            router.push("/app/accounts/expenses");
          }}
        />
      ) : null}
    </>
  );
}

/** How an approved expense was paid: date, mode, the bank account (unless cash) and a reference. */
export function PaidDialog({
  expense,
  onClose,
  onPaid,
}: {
  expense: Pick<ExpenseDetail, "id" | "expenseDate" | "paidPaise">;
  onClose: () => void;
  onPaid: (expense: ExpenseDetail) => void;
}) {
  const { t } = useI18n();
  const today = todayInIndia();
  const banks = useBankAccounts();
  const modeLabel = useModeLabel();
  const form = useForm<PaidValues>({ paidOn: today, mode: "BANK", bankAccountId: "", reference: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validatePaid(form.values, expense.expenseDate, today), t, event.currentTarget)) return;
    let saved: ExpenseDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await accountsApi.markPaid(expense.id, {
        paidOn: form.values.paidOn,
        mode: form.values.mode,
        bankAccountId: form.values.mode === "CASH" ? null : form.values.bankAccountId,
        reference: form.values.reference.trim() || null,
      });
    });
    if (ok && saved) onPaid(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("accounts.expense.markPaidTitle")} description={t("accounts.expense.markPaidDescription", { amount: formatPaise(expense.paidPaise) })} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="paid-form">
        <FormAlert message={form.formError} />
        <TextField
          label={t("accounts.expense.paidOn")}
          name="paidOn"
          type="date"
          value={form.values.paidOn}
          min={expense.expenseDate}
          max={today}
          onChange={(e) => form.set("paidOn", e.target.value)}
          error={form.errors.paidOn}
          required
          data-autofocus
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
        <TextField
          label={form.values.mode === "CHEQUE" ? t("accounts.expense.chequeNumber") : t("accounts.col.reference")}
          name="reference"
          value={form.values.reference}
          onChange={(e) => form.set("reference", e.target.value)}
          error={form.errors.reference}
          maxLength={100}
          required={form.values.mode === "CHEQUE"}
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("accounts.expense.markPaid")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** Rejection needs a reason, which the requester sees. */
export function RejectDialog({
  expense,
  onClose,
  onRejected,
}: {
  expense: Pick<ExpenseDetail, "id">;
  onClose: () => void;
  onRejected: (expense: ExpenseDetail) => void;
}) {
  const { t } = useI18n();
  const form = useForm({ reason: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(form.values.reason.trim() ? {} : { reason: "accounts.v.reason" }, t, event.currentTarget)) return;
    let saved: ExpenseDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await accountsApi.rejectExpense(expense.id, form.values.reason.trim());
    });
    if (ok && saved) onRejected(saved);
  };
  return (
    <Dialog open onClose={onClose} title={t("accounts.expense.rejectTitle")} description={t("accounts.expense.rejectDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="reject-form">
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("accounts.expense.reason")}
          name="reason"
          value={form.values.reason}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          rows={3}
          maxLength={500}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("accounts.expense.reject")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
