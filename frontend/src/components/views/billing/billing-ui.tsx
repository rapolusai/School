"use client";

import { Check, Minus } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill, type PillTone } from "@/components/ui/pill";
import { FormAlert } from "@/components/ui/states";
import { formatDate, formatPaise, formatPlainDate } from "@/lib/format";
import { plural, translateOr, useI18n, type Translate } from "@/lib/i18n";
import {
  GST_STATE_CODES,
  type BillingCycle,
  type BillingDetails,
  type BillingDetailsRequest,
  type BillingNotice,
  type InvoiceStatus,
  type Plan,
  type PlanView,
} from "@/lib/types";
import { useForm, type Problems } from "@/lib/use-form";

/* ------------------------------------------------------------------ GSTIN and states */

const GSTIN_SHAPE = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;
const GSTIN_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

/** The check character (15th) of a GSTIN from its first 14 characters, as the API computes it. */
export function gstinCheckCharacter(first14: string): string {
  let sum = 0;
  for (let i = 0; i < 14; i++) {
    const value = GSTIN_CHARS.indexOf(first14.charAt(i));
    if (value < 0) return "";
    const product = value * (i % 2 === 0 ? 1 : 2);
    sum += Math.floor(product / 36) + (product % 36);
  }
  return GSTIN_CHARS.charAt((36 - (sum % 36)) % 36);
}

/** Shape and check character, like the API. Expects upper case. */
export function isValidGstin(value: string): boolean {
  return GSTIN_SHAPE.test(value) && gstinCheckCharacter(value.slice(0, 14)) === value.charAt(14);
}

export function isStateCode(code: string): boolean {
  return (GST_STATE_CODES as readonly string[]).includes(code);
}

/** "Telangana (36)"; "" when there is no state. */
export function stateLabel(t: Translate, code: string | null | undefined): string {
  if (!code) return "";
  return `${translateOr(t, `billing.state.${code}`, code)} (${code})`;
}

/** Client-side checks mirroring PUT …/details. */
export function detailsProblems(values: { legalName: string; address: string; stateCode: string; gstin: string }): Problems {
  const problems: Problems = {};
  if (values.legalName.trim().length > 200) problems.legalName = "validation.tooLong";
  if (values.address.trim().length > 500) problems.address = "validation.tooLong";
  if (!isStateCode(values.stateCode)) problems.stateCode = "billing.details.stateRequired";
  const gstin = values.gstin.trim().toUpperCase();
  if (gstin) {
    if (!isValidGstin(gstin)) problems.gstin = "billing.details.gstinInvalid";
    else if (isStateCode(values.stateCode) && !gstin.startsWith(values.stateCode))
      problems.gstin = "billing.details.gstinState";
  }
  return problems;
}

/* ------------------------------------------------------------------ labels */

export function cycleLabel(t: Translate, cycle: BillingCycle): string {
  return translateOr(t, `billing.cycle.${cycle}`, cycle);
}

export function planLabel(t: Translate, plan: Plan): string {
  return translateOr(t, `plan.${plan}`, plan);
}

/** 900 basis points → "9%"; 1800 → "18%"; 950 → "9.5%". */
export function rateLabel(basisPoints: number): string {
  const percent = basisPoints / 100;
  return `${Number.isInteger(percent) ? percent : Number(percent.toFixed(2))}%`;
}

/** "1 Apr 2026 – 31 Mar 2027" */
export function periodLabel(start: string, end: string, locale: string): string {
  return `${formatPlainDate(start, locale)} – ${formatPlainDate(end, locale)}`;
}

export function invoiceTone(status: InvoiceStatus, overdue: boolean): PillTone {
  if (status === "PAID") return "good";
  if (status === "CANCELLED") return "neutral";
  return overdue ? "bad" : "info";
}

export function InvoiceStatusPill({ status, overdue }: { status: InvoiceStatus; overdue: boolean }) {
  const { t } = useI18n();
  const key = status === "ISSUED" && overdue ? "OVERDUE" : status;
  return (
    <Pill tone={invoiceTone(status, overdue)} dot>
      {translateOr(t, `billing.invoiceStatus.${key}`, key)}
    </Pill>
  );
}

/** The sentence for the admins' banner, or null when there is nothing to say. */
export function noticeText(notice: BillingNotice, t: Translate, locale: string): string | null {
  switch (notice.kind) {
    case "PAYMENT_OVERDUE":
      return notice.overdueInvoices > 0
        ? t("billing.notice.overdue", {
            amount: formatPaise(notice.overduePaise),
            date: formatPlainDate(notice.oldestDueDate, locale),
          })
        : t("billing.notice.pastDue");
    case "TRIAL_ENDING":
      return plural(t, "billing.notice.trialEnding", notice.trialDaysLeft ?? 0, {
        date: formatDate(notice.trialEndsAt, locale),
      });
    case "TRIAL_ENDED":
      return t("billing.notice.trialEnded", { date: formatDate(notice.trialEndsAt, locale) });
    default:
      return null;
  }
}

/* ------------------------------------------------------------------ plans */

/** The plan catalogue as cards. `current` marks the school's plan; `schools` counts show for the Super Admin. */
export function PlanCards({ plans, current }: { plans: PlanView[]; current?: Plan }) {
  const { t } = useI18n();
  return (
    <div className="grid gap-3 md:grid-cols-3" data-testid="plan-cards">
      {plans.map((plan) => {
        const isCurrent = plan.plan === current;
        return (
          <section
            key={plan.plan}
            className="card flex flex-col gap-2.5"
            style={isCurrent ? { borderColor: "var(--accent)", boxShadow: "inset 0 0 0 1px var(--accent)" } : undefined}
            aria-label={planLabel(t, plan.plan)}
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-[17px]">{planLabel(t, plan.plan)}</h3>
              {isCurrent ? (
                <Pill tone="accent">{t("billing.plans.current")}</Pill>
              ) : plan.popular ? (
                <Pill tone="info">{t("billing.plans.popular")}</Pill>
              ) : null}
            </div>
            <p>
              <span className="kpi-value">{formatPaise(plan.pricePerStudentPerYearPaise)}</span>{" "}
              <span className="text-[13px] text-ink-3">{t("billing.plans.perStudentYear")}</span>
            </p>
            <p className="text-[13px] text-ink-3">
              {t("billing.plans.perStudentMonth", { amount: formatPaise(plan.pricePerStudentPerMonthPaise) })}
            </p>
            <p className="text-[13.5px] font-semibold">
              {plan.maxStudents === null
                ? t("billing.plans.unlimitedStudents")
                : t("billing.plans.upToStudents", { count: plan.maxStudents.toLocaleString("en-IN") })}
              {" · "}
              {plan.maxBranches === null
                ? t("billing.plans.unlimitedBranches")
                : plural(t, "billing.plans.branches", plan.maxBranches)}
            </p>
            <ul className="flex flex-col gap-1.5 text-[13.5px]">
              {plan.features.map((feature) => (
                <li key={feature} className="flex items-start gap-2">
                  <Check size={16} className="mt-0.5 flex-none text-good" aria-hidden="true" />
                  {translateOr(t, `billing.feature.${feature}`, feature)}
                </li>
              ))}
              {plan.notIncluded.map((feature) => (
                <li key={feature} className="flex items-start gap-2 text-ink-3">
                  <Minus size={16} className="mt-0.5 flex-none" aria-hidden="true" />
                  <span>
                    <span className="sr-only">{t("billing.plans.notIncluded")} </span>
                    <s>{translateOr(t, `billing.feature.${feature}`, feature)}</s>
                  </span>
                </li>
              ))}
            </ul>
            {plan.schools !== null ? (
              <p className="mt-auto pt-1 text-[13px] text-ink-2">{plural(t, "billing.plans.schools", plan.schools)}</p>
            ) : null}
          </section>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ billing details */

/** The school as the buyer: shown on the Billing page and the Super Admin's account page. */
export function DetailsList({ details, schoolName }: { details: BillingDetails; schoolName: string }) {
  const { t } = useI18n();
  return (
    <dl className="kv" data-testid="billing-details">
      <dt>{t("billing.details.legalName")}</dt>
      <dd>{details.legalName ?? schoolName}</dd>
      <dt>{t("billing.details.address")}</dt>
      <dd className="whitespace-pre-line">{details.address ?? "—"}</dd>
      <dt>{t("billing.details.state")}</dt>
      <dd>{details.stateCode ? stateLabel(t, details.stateCode) : t("billing.details.stateMissing")}</dd>
      <dt>{t("billing.details.gstin")}</dt>
      <dd className="mono">{details.gstin ?? t("billing.details.noGstin")}</dd>
    </dl>
  );
}

/** Edits the school's billing details; `onSave` calls the API for the school admin or the Super Admin. */
export function DetailsDialog({
  details,
  schoolName,
  onClose,
  onSave,
}: {
  details: BillingDetails;
  schoolName: string;
  onClose: () => void;
  onSave: (body: BillingDetailsRequest) => Promise<void>;
}) {
  const { t } = useI18n();
  const form = useForm({
    legalName: details.legalName ?? "",
    address: details.address ?? "",
    stateCode: details.stateCode ?? "",
    gstin: details.gstin ?? "",
  });
  const states = GST_STATE_CODES.map((code) => ({ value: code, label: stateLabel(t, code) })).sort((a, b) =>
    a.label.localeCompare(b.label),
  );
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(detailsProblems(form.values), t, event.currentTarget)) return;
    const v = form.values;
    const ok = await form.submit(t, () =>
      onSave({
        legalName: v.legalName.trim() || null,
        address: v.address.trim() || null,
        stateCode: v.stateCode,
        gstin: v.gstin.trim().toUpperCase() || null,
      }),
    );
    if (ok) onClose();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("billing.details.title")}
      description={t("billing.details.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("billing.details.legalName")}
          name="legalName"
          value={form.values.legalName}
          maxLength={200}
          placeholder={schoolName}
          hint={t("billing.details.legalNameHint")}
          onChange={(e) => form.set("legalName", e.target.value)}
          error={form.errors.legalName}
          data-autofocus
        />
        <TextAreaField
          label={t("billing.details.address")}
          name="address"
          value={form.values.address}
          maxLength={500}
          rows={3}
          onChange={(e) => form.set("address", e.target.value)}
          error={form.errors.address}
        />
        <SelectField
          label={t("billing.details.state")}
          name="stateCode"
          value={form.values.stateCode}
          hint={t("billing.details.stateHint")}
          options={[{ value: "", label: t("common.choose") }, ...states]}
          onChange={(e) => form.set("stateCode", e.target.value)}
          error={form.errors.stateCode}
          required
        />
        <TextField
          label={t("billing.details.gstinOptional")}
          name="gstin"
          value={form.values.gstin}
          maxLength={15}
          autoCapitalize="characters"
          spellCheck={false}
          className="mono"
          hint={t("billing.details.gstinHint")}
          onChange={(e) => form.set("gstin", e.target.value)}
          error={form.errors.gstin}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
