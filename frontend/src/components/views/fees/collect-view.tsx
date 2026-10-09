"use client";

import { ArrowLeft, Plus, UserRound } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { feesApi } from "@/lib/fees-api";
import { classLabel, formatPaise, formatPlainDate, initials, parseRupees, rupeesInput } from "@/lib/format";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import {
  COUNTER_PAYMENT_MODES,
  MAX_AMOUNT_PAISE,
  type CounterPaymentMode,
  type InstalmentDue,
  type PaymentRequest,
  type Receipt,
  type StudentFees,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { DuesTable } from "./dues-table";
import { Amount, FeesNav, openInstalments, payableOf, useModeLabel } from "./fee-ui";
import { PrintButton, ReceiptDocument } from "./receipt-document";
import { StudentSearch } from "./student-search";

export type PaymentValues = {
  amount: string;
  mode: CounterPaymentMode;
  chequeNo: string;
  bankName: string;
  reference: string;
  includeLateFee: boolean;
  remarks: string;
};

export const EMPTY_PAYMENT: PaymentValues = {
  amount: "",
  mode: "CASH",
  chequeNo: "",
  bankName: "",
  reference: "",
  includeLateFee: true,
  remarks: "",
};

const REFERENCE_LABELS: Partial<Record<CounterPaymentMode, MessageKey>> = {
  UPI: "fees.collect.reference.UPI",
  CARD: "fees.collect.reference.CARD",
  BANK_TRANSFER: "fees.collect.reference.BANK_TRANSFER",
};

/** Modes that need a reference number (cards may give one, the API does not insist). */
const NEEDS_REFERENCE: CounterPaymentMode[] = ["UPI", "BANK_TRANSFER"];

/** The same checks POST /api/fees/students/{id}/payments makes. `maxPaise` is what is due. */
export function validatePayment(values: PaymentValues, maxPaise: number): Problems {
  const problems: Problems = {};
  const amount = parseRupees(values.amount);
  if (!values.amount.trim()) problems.amount = "validation.required";
  else if (amount === null || amount < 1) problems.amount = "fees.v.amount";
  else if (amount > MAX_AMOUNT_PAISE) problems.amount = "fees.v.amountTooLarge";
  else if (amount > maxPaise) problems.amount = "fees.v.moreThanDue";
  if (values.mode === "CHEQUE") {
    if (!values.chequeNo.trim()) problems.chequeNo = "validation.required";
    else if (values.chequeNo.trim().length > 20) problems.chequeNo = "validation.tooLong";
    if (!values.bankName.trim()) problems.bankName = "validation.required";
    else if (values.bankName.trim().length > 100) problems.bankName = "validation.tooLong";
  }
  if (NEEDS_REFERENCE.includes(values.mode) && !values.reference.trim()) problems.reference = "validation.required";
  else if (values.reference.trim().length > 100) problems.reference = "validation.tooLong";
  if (values.remarks.trim().length > 200) problems.remarks = "validation.tooLong";
  return problems;
}

/** The request body for the form, sending only the fields the chosen mode uses. */
export function toPaymentRequest(values: PaymentValues, instalmentIds: string[]): PaymentRequest {
  const text = (value: string) => value.trim() || null;
  return {
    amountPaise: parseRupees(values.amount) ?? 0,
    mode: values.mode,
    chequeNo: values.mode === "CHEQUE" ? text(values.chequeNo) : null,
    bankName: values.mode === "CHEQUE" ? text(values.bankName) : null,
    reference: values.mode === "CHEQUE" || values.mode === "CASH" ? null : text(values.reference),
    instalmentIds: instalmentIds.length ? instalmentIds : null,
    includeLateFee: values.includeLateFee,
    remarks: text(values.remarks),
  };
}

/** What the payment may cover: the chosen instalments, or every open one (oldest first). */
export function payableFor(instalments: InstalmentDue[], selected: ReadonlySet<string>, includeLateFee: boolean) {
  const open = openInstalments(instalments);
  const chosen = selected.size ? open.filter((i) => selected.has(i.instalmentId)) : open;
  return chosen.reduce((sum, i) => sum + payableOf(i, includeLateFee), 0);
}

/** The amount the form suggests: the chosen instalments, or what is payable today. */
function suggestedAmount(fees: StudentFees, selected: ReadonlySet<string>, includeLateFee: boolean): number {
  if (selected.size) return payableFor(fees.instalments, selected, includeLateFee);
  const lateFee = includeLateFee ? 0 : fees.totals.lateFeePaise;
  const now = fees.totals.payableNowPaise - lateFee;
  return now > 0 ? now : payableFor(fees.instalments, selected, includeLateFee);
}

/** Collect a fee at the counter: find the student, pick instalments, take the payment, print the receipt. */
export function CollectView({ initialStudentId }: { initialStudentId?: string }) {
  const { t } = useI18n();
  const [studentId, setStudentId] = useState<string | null>(initialStudentId ?? null);
  const [receipt, setReceipt] = useState<Receipt | null>(null);

  const pick = (id: string | null) => {
    setStudentId(id);
    setReceipt(null);
  };

  return (
    <>
      <PageHead eyebrow={t("fees.eyebrow")} title={t("fees.collect.title")} />
      <FeesNav />
      {receipt ? (
        <ReceiptDone receipt={receipt} onAgain={() => setReceipt(null)} onNewStudent={() => pick(null)} />
      ) : studentId ? (
        <StudentPayment key={studentId} studentId={studentId} onBack={() => pick(null)} onPaid={setReceipt} />
      ) : (
        <section className="card" aria-labelledby="find-student">
          <div className="card-head">
            <h2 id="find-student">{t("fees.collect.find")}</h2>
          </div>
          <StudentSearch onPick={(hit) => pick(hit.id)} autoFocus />
        </section>
      )}
    </>
  );
}

function ReceiptDone({
  receipt,
  onAgain,
  onNewStudent,
}: {
  receipt: Receipt;
  onAgain: () => void;
  onNewStudent: () => void;
}) {
  const { t } = useI18n();
  return (
    <>
      <section className="alert alert-info no-print" role="status">
        {t("fees.collect.done", { number: receipt.receiptNo, amount: formatPaise(receipt.amountPaise) })}
      </section>
      <div className="flex flex-wrap gap-2 no-print">
        <PrintButton />
        <button type="button" className="btn" onClick={onAgain}>
          {t("fees.collect.again")}
        </button>
        <button type="button" className="btn btn-primary" onClick={onNewStudent}>
          <Plus size={18} aria-hidden="true" />
          {t("fees.collect.newStudent")}
        </button>
      </div>
      <section className="card">
        <ReceiptDocument receipt={receipt} />
      </section>
    </>
  );
}

function StudentPayment({
  studentId,
  onBack,
  onPaid,
}: {
  studentId: string;
  onBack: () => void;
  onPaid: (receipt: Receipt) => void;
}) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canCollect = hasPermission(me, PERMISSIONS.feesCollect);
  const canManage = hasPermission(me, PERMISSIONS.feesManage);
  const fees = useApiData(`fees:student:${studentId}`, () => feesApi.studentFees(studentId));
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [touched, setTouched] = useState(false);
  const [waiving, setWaiving] = useState<InstalmentDue | null>(null);
  const form = useForm<PaymentValues>(EMPTY_PAYMENT);
  const { values, set, errors } = form;
  const modeLabel = useModeLabel();

  const back = (
    <button type="button" className="linkbtn inline-flex items-center gap-1 text-[13.5px]" onClick={onBack}>
      <ArrowLeft size={16} aria-hidden="true" />
      {t("fees.collect.otherStudent")}
    </button>
  );

  const data = fees.data;
  if (fees.error && !data) {
    return (
      <>
        {back}
        <section className="card">
          {fees.error.status === 404 ? (
            <p className="empty">{t("fees.student.notFound")}</p>
          ) : (
            <ErrorState error={fees.error} onRetry={fees.reload} />
          )}
        </section>
      </>
    );
  }
  if (!data) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      </>
    );
  }

  const open = openInstalments(data.instalments);
  const hasLateFee = open.some((i) => i.lateFeePaise > 0);
  const maxPaise = payableFor(data.instalments, selected, values.includeLateFee);
  // Until the amount is typed, it follows the instalments picked.
  const amountText = touched ? values.amount : rupeesInput(suggestedAmount(data, selected, values.includeLateFee));
  const current = { ...values, amount: amountText };

  const toggle = (instalmentId: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(instalmentId)) next.delete(instalmentId);
      else next.add(instalmentId);
      return next;
    });
    setTouched(false);
    form.setErrors({});
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validatePayment(current, maxPaise), t, event.currentTarget)) return;
    const ids = open.filter((i) => selected.has(i.instalmentId)).map((i) => i.instalmentId);
    let saved: Receipt | undefined;
    const ok = await form.submit(t, async () => {
      saved = await feesApi.collect(studentId, toPaymentRequest(current, ids));
    });
    if (ok && saved) {
      toast(t("fees.collect.saved", { number: saved.receiptNo }));
      onPaid(saved);
    }
  };

  const s = data.student;
  const totals = data.totals;

  return (
    <>
      {back}
      <section className="card flex flex-col gap-4" aria-labelledby="fee-student">
        <div className="person">
          <span className="avatar h-11 w-11 text-[15px]" aria-hidden="true">
            {initials(s.fullName)}
          </span>
          <div className="min-w-0">
            <h2 id="fee-student" className="leading-tight">
              {s.fullName}
            </h2>
            <span className="sub">
              {[classLabel(s.className, s.sectionName), s.admissionNo].filter(Boolean).join(" · ")}
            </span>
          </div>
          <Link href={`/app/students/${s.id}`} className="link ml-auto hidden text-[13.5px] sm:inline-flex">
            <UserRound size={16} aria-hidden="true" className="mr-1" />
            {t("fees.collect.record")}
          </Link>
        </div>
        <div className="amounts">
          <Amount label={t("fees.totals.net")} value={formatPaise(totals.netPaise)} />
          <Amount label={t("fees.totals.paid")} value={formatPaise(totals.paidPaise)} tone="good" />
          <Amount label={t("fees.totals.balance")} value={formatPaise(totals.balancePaise)} />
          <Amount
            label={t("fees.totals.overdue")}
            value={formatPaise(totals.overduePaise + totals.lateFeePaise)}
            tone={totals.overduePaise > 0 ? "bad" : undefined}
          />
        </div>
        {totals.concessionPaise > 0 ? (
          <p className="text-[13px] text-ink-2">
            {t("fees.collect.concessions", {
              amount: formatPaise(totals.concessionPaise),
              names: data.concessions
                .filter((c) => c.status === "ACTIVE")
                .map((c) => t(`fees.concessionType.${c.type}`))
                .join(", "),
            })}
          </p>
        ) : null}
      </section>

      {data.instalments.length === 0 ? (
        <section className="card">
          <p className="empty">{t("fees.collect.noDues")}</p>
        </section>
      ) : (
        <div className="collect-grid">
          <section className="card" aria-labelledby="dues-title">
            <div className="card-head">
              <div>
                <h2 id="dues-title">{t("fees.collect.dues")}</h2>
                <p className="mt-1 text-sm text-ink-2">
                  {canCollect && open.length ? t("fees.collect.pickHint") : t("fees.collect.duesHint")}
                </p>
              </div>
            </div>
            <DuesTable
              caption={t("fees.collect.dues")}
              instalments={data.instalments}
              selection={
                canCollect
                  ? { selected, onToggle: toggle, canSelect: (i) => i.balancePaise > 0 || i.lateFeePaise > 0 }
                  : undefined
              }
              action={
                canManage
                  ? (i) =>
                      i.lateFeePaise > 0 ? (
                        <button type="button" className="btn btn-sm" onClick={() => setWaiving(i)}>
                          {t("fees.waive.button")}
                        </button>
                      ) : null
                  : undefined
              }
            />
          </section>

          {canCollect ? (
            <section className="card" aria-labelledby="pay-title">
              <h2 id="pay-title" className="mb-3">
                {t("fees.collect.payment")}
              </h2>
              {open.length === 0 ? (
                <p className="empty">{t("fees.collect.allPaid")}</p>
              ) : (
                <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
                  <FormAlert message={form.formError} />
                  <TextField
                    label={t("fees.collect.amount")}
                    name="amount"
                    inputMode="decimal"
                    value={amountText}
                    onChange={(e) => {
                      setTouched(true);
                      set("amount", e.target.value);
                    }}
                    hint={t(selected.size ? "fees.collect.amountHintSelected" : "fees.collect.amountHint", {
                      amount: formatPaise(maxPaise),
                    })}
                    error={errors.amount ?? errors.amountPaise}
                    required
                  />
                  {hasLateFee ? (
                    <label className="check">
                      <input
                        type="checkbox"
                        name="includeLateFee"
                        checked={values.includeLateFee}
                        onChange={(e) => {
                          set("includeLateFee", e.target.checked);
                          setTouched(false);
                        }}
                      />
                      <span>{t("fees.collect.includeLateFee")}</span>
                    </label>
                  ) : null}
                  <fieldset className="field m-0 border-0 p-0">
                    <legend className="field-label mb-1.5">{t("fees.collect.mode")}</legend>
                    <div className="seg" role="radiogroup" aria-label={t("fees.collect.mode")}>
                      {COUNTER_PAYMENT_MODES.map((mode) => (
                        <label key={mode}>
                          <input
                            type="radio"
                            name="mode"
                            value={mode}
                            checked={values.mode === mode}
                            onChange={() => set("mode", mode)}
                          />
                          {modeLabel(mode)}
                        </label>
                      ))}
                    </div>
                    {errors.mode ? <p className="field-error">{errors.mode}</p> : null}
                  </fieldset>
                  {values.mode === "CHEQUE" ? (
                    <div className="grid2">
                      <TextField
                        label={t("fees.collect.chequeNo")}
                        name="chequeNo"
                        value={values.chequeNo}
                        maxLength={20}
                        onChange={(e) => set("chequeNo", e.target.value)}
                        error={errors.chequeNo}
                        required
                      />
                      <TextField
                        label={t("fees.collect.bankName")}
                        name="bankName"
                        value={values.bankName}
                        maxLength={100}
                        onChange={(e) => set("bankName", e.target.value)}
                        error={errors.bankName}
                        required
                      />
                    </div>
                  ) : null}
                  {values.mode !== "CASH" && values.mode !== "CHEQUE" ? (
                    <TextField
                      label={t(REFERENCE_LABELS[values.mode] ?? "fees.collect.reference.UPI")}
                      name="reference"
                      value={values.reference}
                      maxLength={100}
                      onChange={(e) => set("reference", e.target.value)}
                      hint={values.mode === "CARD" ? t("fees.collect.optional") : undefined}
                      error={errors.reference}
                      required={NEEDS_REFERENCE.includes(values.mode)}
                    />
                  ) : null}
                  <TextAreaField
                    label={t("fees.collect.remarks")}
                    name="remarks"
                    value={values.remarks}
                    maxLength={200}
                    rows={2}
                    hint={t("fees.collect.optional")}
                    onChange={(e) => set("remarks", e.target.value)}
                    error={errors.remarks}
                  />
                  {errors.instalmentIds ? <p className="field-error">{errors.instalmentIds}</p> : null}
                  <button type="submit" className="btn btn-primary btn-lg" disabled={form.submitting}>
                    {form.submitting
                      ? t("common.saving")
                      : t("fees.collect.submit", {
                          amount: parseRupees(amountText) !== null ? formatPaise(parseRupees(amountText) ?? 0) : "",
                        })}
                  </button>
                </form>
              )}
            </section>
          ) : null}
        </div>
      )}

      {data.receipts.length ? (
        <section className="card" aria-labelledby="past-receipts">
          <div className="card-head">
            <h2 id="past-receipts">{t("fees.collect.receipts")}</h2>
          </div>
          <ReceiptList receipts={data.receipts} hrefFor={(r) => `/app/fees/receipts/${r.id}`} />
        </section>
      ) : null}

      {waiving ? (
        <WaiveDialog
          studentId={studentId}
          instalment={waiving}
          onClose={() => setWaiving(null)}
          onDone={() => {
            setWaiving(null);
            setTouched(false);
            fees.reload();
            toast(t("fees.waive.done"));
          }}
        />
      ) : null}
    </>
  );
}

function WaiveDialog({
  studentId,
  instalment,
  onClose,
  onDone,
}: {
  studentId: string;
  instalment: InstalmentDue;
  onClose: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const form = useForm({ reason: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const reason = form.values.reason.trim();
    const problems: Problems = {};
    if (!reason) problems.reason = "validation.required";
    else if (reason.length > 500) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await feesApi.waiveLateFee(studentId, instalment.instalmentId, reason);
    });
    if (ok) onDone();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("fees.waive.title", { label: instalment.label })}
      description={t("fees.waive.description", { amount: formatPaise(instalment.lateFeePaise) })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("fees.reason")}
          name="reason"
          value={form.values.reason}
          maxLength={500}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("fees.waive.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** A short list of receipts linking to each one. */
export function ReceiptList({
  receipts,
  hrefFor,
}: {
  receipts: StudentFees["receipts"];
  hrefFor: (receipt: StudentFees["receipts"][number]) => string;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  return (
    <ul className="list" data-testid="receipt-list">
      {receipts.map((r) => (
        <li key={r.id} className="li items-center">
          <div className="min-w-0 flex-1">
            <Link href={hrefFor(r)} className="link mono">
              {r.receiptNo}
            </Link>
            <p className="text-[12.5px] text-ink-3">
              {[formatPlainDate(r.receivedOn, locale), modeLabel(r.mode)].join(" · ")}
              {r.status === "CANCELLED" ? ` · ${t("fees.receiptStatus.CANCELLED")}` : ""}
            </p>
          </div>
          <span className={`num font-semibold${r.status === "CANCELLED" ? " text-ink-3 line-through" : ""}`}>
            {formatPaise(r.amountPaise)}
          </span>
        </li>
      ))}
    </ul>
  );
}
