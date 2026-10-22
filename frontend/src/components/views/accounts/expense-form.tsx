"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { FilePicker } from "@/components/views/files/attachments";
import { blank, GST_RATES, gstAt, MAX_ATTACHMENTS, tdsOn, validateExpense, type ExpenseValues } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { formatPaise, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import type { ExpenseDetail, ExpenseRequest, HeadView, VendorView } from "@/lib/types";
import { useForm } from "@/lib/use-form";
import { useCostCentres, useHeads, useVendors } from "./accounts-ui";

function initialValues(expense: ExpenseDetail | null, today: string): ExpenseValues {
  if (!expense) {
    return {
      headId: "",
      costCentreId: "",
      vendorId: "",
      vendorName: "",
      expenseDate: today,
      amount: "",
      gstRate: "",
      gst: "",
      deductTds: false,
      description: "",
      billNumber: "",
    };
  }
  return {
    headId: expense.headId,
    costCentreId: expense.costCentreId ?? "",
    vendorId: expense.vendorId ?? "",
    vendorName: expense.vendorId ? "" : (expense.vendorName ?? ""),
    expenseDate: expense.expenseDate,
    amount: rupeesInput(expense.amountPaise),
    gstRate: "",
    gst: expense.gstPaise ? rupeesInput(expense.gstPaise) : "",
    deductTds: Boolean(expense.tdsSection),
    description: expense.description,
    billNumber: expense.billNumber ?? "",
  };
}

/** The request the form sends, from checked values. Exported for tests. */
export function toExpenseRequest(values: ExpenseValues): ExpenseRequest {
  return {
    headId: values.headId,
    costCentreId: values.costCentreId || null,
    vendorId: values.vendorId || null,
    vendorName: values.vendorId ? null : blank(values.vendorName),
    expenseDate: values.expenseDate,
    amountPaise: parseRupees(values.amount) ?? 0,
    gstPaise: values.gst.trim() ? (parseRupees(values.gst) ?? 0) : 0,
    deductTds: values.deductTds,
    description: values.description.trim(),
    billNumber: blank(values.billNumber),
  };
}

/**
 * Records or edits an expense: head, cost centre, vendor (or a name), date, amount, GST (a rate fills the amount,
 * the bill's figure can override it), TDS from the vendor's section, a description, the bill number and up to
 * five bill photos or PDFs. Saved as a draft, or sent for approval straight away.
 */
export function ExpenseFormDialog({
  expense,
  onClose,
  onSaved,
}: {
  expense: ExpenseDetail | null;
  onClose: () => void;
  onSaved: (expense: ExpenseDetail, submitted: boolean) => void;
}) {
  const { t } = useI18n();
  const today = todayInIndia();
  const heads = useHeads();
  const vendors = useVendors();
  const centres = useCostCentres();
  const form = useForm<ExpenseValues>(initialValues(expense, today));
  const [files, setFiles] = useState<File[]>([]);
  const [fileError, setFileError] = useState<string | null>(null);

  const expenseHeads = (heads.data ?? []).filter((h) => h.kind === "EXPENSE" && (h.active || h.id === form.values.headId));
  const head: HeadView | undefined = expenseHeads.find((h) => h.id === form.values.headId);
  const vendor: VendorView | undefined = (vendors.data ?? []).find((v) => v.id === form.values.vendorId);
  const amount = parseRupees(form.values.amount) ?? 0;
  const gst = form.values.gst.trim() ? (parseRupees(form.values.gst) ?? 0) : 0;
  const tds = form.values.deductTds && vendor?.tdsSection ? tdsOn(amount, vendor.tdsRatePercent) : 0;
  const total = amount + gst;
  const attachmentsLeft = MAX_ATTACHMENTS - (expense?.files.length ?? 0);

  const save = async (submit: boolean, formEl: HTMLFormElement) => {
    if (!form.check(validateExpense(form.values, today, head?.gstApplicable ?? false), t, formEl)) return;
    let saved: ExpenseDetail | undefined;
    const ok = await form.submit(t, async () => {
      const body = toExpenseRequest(form.values);
      saved = expense ? await accountsApi.updateExpense(expense.id, body) : await accountsApi.createExpense(body, false);
      for (const file of files) {
        saved = await accountsApi.addAttachment(saved.id, file);
      }
      if (submit) saved = await accountsApi.submitExpense(saved.id);
    });
    if (ok && saved) onSaved(saved, submit);
  };

  const setRate = (rate: string) => {
    form.set("gstRate", rate);
    if (rate !== "") form.set("gst", rate === "0" ? "" : rupeesInput(gstAt(amount, Number(rate))));
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={expense ? t("accounts.expense.editTitle") : t("accounts.expense.newTitle")}
      description={t("accounts.expense.newDescription")}
      closeLabel={t("common.close")}
      variant="sheet"
    >
      <form
        method="post"
        className="flex flex-col gap-3.5"
        onSubmit={(e) => {
          e.preventDefault();
          void save(false, e.currentTarget);
        }}
        noValidate
        data-testid="expense-form"
      >
        <FormAlert message={form.formError} />
        {heads.error ? <FormAlert message={t("accounts.expense.headsFailed")} /> : null}
        <div className="grid gap-3.5 sm:grid-cols-2">
          <SelectField
            label={t("accounts.col.head")}
            name="headId"
            value={form.values.headId}
            onChange={(e) => {
              form.set("headId", e.target.value);
              const next = expenseHeads.find((h) => h.id === e.target.value);
              if (next && !next.gstApplicable) {
                form.set("gst", "");
                form.set("gstRate", "");
              }
            }}
            options={[{ value: "", label: t("accounts.expense.pickHead") }, ...expenseHeads.map((h) => ({ value: h.id, label: `${h.code} · ${h.name}` }))]}
            error={form.errors.headId}
            required
            data-autofocus
          />
          <SelectField
            label={t("accounts.col.costCentre")}
            name="costCentreId"
            value={form.values.costCentreId}
            onChange={(e) => form.set("costCentreId", e.target.value)}
            options={[
              { value: "", label: t("accounts.expense.noCostCentre") },
              ...(centres.data ?? []).filter((c) => c.active || c.id === form.values.costCentreId).map((c) => ({ value: c.id, label: c.name })),
            ]}
            error={form.errors.costCentreId}
          />
          <SelectField
            label={t("accounts.col.vendor")}
            name="vendorId"
            value={form.values.vendorId}
            onChange={(e) => {
              form.set("vendorId", e.target.value);
              if (!e.target.value) form.set("deductTds", false);
            }}
            options={[
              { value: "", label: t("accounts.expense.noVendor") },
              ...(vendors.data ?? []).filter((v) => v.active || v.id === form.values.vendorId).map((v) => ({ value: v.id, label: v.name })),
            ]}
            error={form.errors.vendorId}
            hint={vendor?.tdsSection ? t("accounts.expense.vendorTds", { section: vendor.tdsSection, rate: String(vendor.tdsRatePercent ?? 0) }) : undefined}
          />
          {!form.values.vendorId ? (
            <TextField
              label={t("accounts.expense.vendorName")}
              name="vendorName"
              value={form.values.vendorName}
              onChange={(e) => form.set("vendorName", e.target.value)}
              error={form.errors.vendorName}
              maxLength={120}
              hint={t("accounts.expense.vendorNameHint")}
            />
          ) : (
            <TextField
              label={t("accounts.col.date")}
              name="expenseDate"
              type="date"
              value={form.values.expenseDate}
              max={today}
              onChange={(e) => form.set("expenseDate", e.target.value)}
              error={form.errors.expenseDate}
              required
            />
          )}
          {!form.values.vendorId ? (
            <TextField
              label={t("accounts.col.date")}
              name="expenseDate"
              type="date"
              value={form.values.expenseDate}
              max={today}
              onChange={(e) => form.set("expenseDate", e.target.value)}
              error={form.errors.expenseDate}
              required
            />
          ) : null}
          <TextField
            label={t("accounts.expense.amount")}
            name="amount"
            inputMode="decimal"
            value={form.values.amount}
            onChange={(e) => {
              form.set("amount", e.target.value);
              if (form.values.gstRate && form.values.gstRate !== "0") {
                form.set("gst", rupeesInput(gstAt(parseRupees(e.target.value) ?? 0, Number(form.values.gstRate))));
              }
            }}
            error={form.errors.amount}
            hint={t("accounts.expense.amountHint")}
            required
          />
          {head?.gstApplicable ? (
            <>
              <SelectField
                label={t("accounts.expense.gstRate")}
                name="gstRate"
                value={form.values.gstRate}
                onChange={(e) => setRate(e.target.value)}
                options={[{ value: "", label: t("accounts.expense.gstRateBill") }, ...GST_RATES.map((r) => ({ value: String(r), label: `${r}%` }))]}
              />
              <TextField
                label={t("accounts.expense.gst")}
                name="gst"
                inputMode="decimal"
                value={form.values.gst}
                onChange={(e) => {
                  form.set("gst", e.target.value);
                  form.set("gstRate", "");
                }}
                error={form.errors.gst}
                hint={t("accounts.expense.gstHint")}
              />
            </>
          ) : null}
        </div>
        {vendor?.tdsSection ? (
          <label className="check">
            <input
              type="checkbox"
              name="deductTds"
              checked={form.values.deductTds}
              onChange={(e) => form.set("deductTds", e.target.checked)}
            />
            <span>
              {t("accounts.expense.deductTds", { section: vendor.tdsSection, rate: String(vendor.tdsRatePercent ?? 0) })}
              {tds ? ` · ${formatPaise(tds)}` : ""}
            </span>
          </label>
        ) : null}
        {form.errors.deductTds ? <p className="field-error">{form.errors.deductTds}</p> : null}
        <TextAreaField
          label={t("accounts.col.description")}
          name="description"
          value={form.values.description}
          onChange={(e) => form.set("description", e.target.value)}
          error={form.errors.description}
          rows={2}
          maxLength={500}
          required
        />
        <TextField
          label={t("accounts.expense.billNumber")}
          name="billNumber"
          value={form.values.billNumber}
          onChange={(e) => form.set("billNumber", e.target.value)}
          error={form.errors.billNumber}
          maxLength={60}
          className="sm:max-w-xs"
        />
        <FilePicker
          files={files}
          onChange={(next) => {
            setFiles(next);
            setFileError(null);
          }}
          max={attachmentsLeft}
          label={t("accounts.expense.bills")}
          error={fileError ?? form.errors.file}
          name="file"
        />
        <dl className="kv text-[13.5px]" data-testid="expense-totals">
          <dt>{t("accounts.col.total")}</dt>
          <dd className="num font-semibold">{formatPaise(total)}</dd>
          {tds ? (
            <>
              <dt>{t("accounts.expense.tdsKeptBack")}</dt>
              <dd className="num">{formatPaise(tds)}</dd>
              <dt>{t("accounts.expense.payable")}</dt>
              <dd className="num font-semibold">{formatPaise(total - tds)}</dd>
            </>
          ) : null}
        </dl>
        <div className="flex flex-wrap justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("accounts.expense.saveDraft")}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={form.submitting}
            onClick={(e) => void save(true, e.currentTarget.form as HTMLFormElement)}
          >
            {form.submitting ? t("common.working") : t("accounts.expense.sendForApproval")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
