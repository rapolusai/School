"use client";

import { ArrowLeft, Ban, FilePlus2, Pencil, Play, RefreshCcw } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill, statusTone } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { platformBillingApi } from "@/lib/billing-api";
import { formatDate, formatDateTime, formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import {
  BILLING_CYCLES,
  PLANS,
  type BillingCycle,
  type Plan,
  type PlanView,
  type SchoolAccount,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { cycleLabel, DetailsDialog, DetailsList, periodLabel, planLabel } from "./billing-ui";
import { InvoiceTable, NoticeAlert } from "./school-billing-view";

const MAX_STUDENTS = 100_000;

/** Billed students as typed → a whole number, or null when it is not 1–100,000. */
export function parseStudents(text: string): number | null {
  const cleaned = text.replace(/[,\s]/g, "");
  if (!/^\d{1,6}$/.test(cleaned)) return null;
  const value = Number(cleaned);
  return value >= 1 && value <= MAX_STUDENTS ? value : null;
}

/** The price of one period before GST, as the API computes it: students × the plan's price for the cycle. */
export function periodPricePaise(plan: PlanView | undefined, cycle: BillingCycle, students: number): number | null {
  if (!plan) return null;
  const unit = cycle === "YEARLY" ? plan.pricePerStudentPerYearPaise : plan.pricePerStudentPerMonthPaise;
  return unit * students;
}

type SubscriptionValues = { plan: Plan; billingCycle: BillingCycle; billedStudents: string; periodStart: string };

/** Client-side checks mirroring POST/PUT …/subscription. `withPeriod`: the start date is checked too. */
export function subscriptionProblems(
  values: SubscriptionValues,
  plans: PlanView[] | undefined,
  withPeriod: boolean,
): Problems {
  const problems: Problems = {};
  const students = parseStudents(values.billedStudents);
  if (students === null) problems.billedStudents = "billing.start.studentsInvalid";
  else {
    const max = plans?.find((p) => p.plan === values.plan)?.maxStudents;
    if (max !== undefined && max !== null && students > max) problems.billedStudents = "billing.start.overPlan";
  }
  if (withPeriod && values.periodStart && !/^\d{4}-\d{2}-\d{2}$/.test(values.periodStart))
    problems.periodStart = "validation.date";
  return problems;
}

function SubscriptionDialog({
  account,
  plans,
  mode,
  onClose,
  onDone,
}: {
  account: SchoolAccount;
  plans: PlanView[] | undefined;
  /** "start" converts a trial; "change" changes the plan (and, for a paying school, cycle and students). */
  mode: "start" | "change";
  onClose: () => void;
  onDone: (next: SchoolAccount) => void;
}) {
  const { t } = useI18n();
  const school = account.school;
  const sub = account.subscription;
  const paying = sub !== null;
  const form = useForm<SubscriptionValues>({
    plan: school.plan as Plan,
    billingCycle: sub?.billingCycle ?? "YEARLY",
    billedStudents: String(sub?.billedStudents ?? Math.max(1, account.activeStudents)),
    periodStart: todayInIndia(),
  });
  const v = form.values;
  const showTerms = mode === "start" || paying;
  const students = parseStudents(v.billedStudents);
  const price =
    students === null ? null : periodPricePaise(plans?.find((p) => p.plan === v.plan), v.billingCycle, students);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (showTerms && !form.check(subscriptionProblems(v, plans, mode === "start"), t, event.currentTarget)) return;
    const result: { account?: SchoolAccount } = {};
    const ok = await form.submit(t, async () => {
      result.account =
        mode === "start"
          ? await platformBillingApi.start(school.id, {
              plan: v.plan,
              billingCycle: v.billingCycle,
              billedStudents: students ?? 0,
              periodStart: v.periodStart || null,
            })
          : await platformBillingApi.change(school.id, {
              plan: v.plan,
              billingCycle: paying ? v.billingCycle : null,
              billedStudents: paying ? students : null,
            });
    });
    if (ok && result.account) onDone(result.account);
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={mode === "start" ? t("billing.start.title") : t("billing.change.title")}
      description={
        mode === "start"
          ? t("billing.start.description")
          : paying
            ? t("billing.change.descriptionPaying")
            : t("billing.change.descriptionTrial")
      }
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <SelectField
          label={t("billing.subscription.plan")}
          name="plan"
          value={v.plan}
          options={PLANS.map((plan) => ({ value: plan, label: planLabel(t, plan) }))}
          onChange={(e) => form.set("plan", e.target.value as Plan)}
          error={form.errors.plan}
          data-autofocus
        />
        {showTerms ? (
          <>
            <SelectField
              label={t("billing.subscription.cycle")}
              name="billingCycle"
              value={v.billingCycle}
              options={BILLING_CYCLES.map((cycle) => ({ value: cycle, label: cycleLabel(t, cycle) }))}
              onChange={(e) => form.set("billingCycle", e.target.value as BillingCycle)}
              error={form.errors.billingCycle}
            />
            <TextField
              label={t("billing.subscription.students")}
              name="billedStudents"
              inputMode="numeric"
              value={v.billedStudents}
              hint={t("billing.start.studentsHint", { count: account.activeStudents.toLocaleString("en-IN") })}
              onChange={(e) => form.set("billedStudents", e.target.value)}
              error={form.errors.billedStudents}
              required
            />
            {mode === "start" ? (
              <TextField
                label={t("billing.start.periodStart")}
                name="periodStart"
                type="date"
                value={v.periodStart}
                hint={t("billing.start.periodStartHint")}
                onChange={(e) => form.set("periodStart", e.target.value)}
                error={form.errors.periodStart}
              />
            ) : null}
            {price !== null ? (
              <p className="alert alert-info" data-testid="subscription-price">
                {t("billing.start.price", {
                  amount: formatPaise(price),
                  cycle: cycleLabel(t, v.billingCycle).toLowerCase(),
                })}
              </p>
            ) : null}
          </>
        ) : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting
              ? t("common.working")
              : mode === "start"
                ? t("billing.start.submit")
                : t("billing.change.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function SuspendDialog({
  account,
  onClose,
  onDone,
}: {
  account: SchoolAccount;
  onClose: () => void;
  onDone: (next: SchoolAccount) => void;
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
    const result: { account?: SchoolAccount } = {};
    const ok = await form.submit(t, async () => {
      result.account = await platformBillingApi.suspend(account.school.id, reason);
    });
    if (ok && result.account) onDone(result.account);
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("billing.suspend.title", { name: account.school.name })}
      description={t("billing.suspend.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("billing.suspend.reason")}
          name="reason"
          value={form.values.reason}
          maxLength={500}
          placeholder={t("billing.suspend.placeholder")}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("billing.suspend.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type Pending = null | "details" | "start" | "change" | "issue" | "suspend" | "reactivate" | "pastDue" | "active";

/** /app/platform/schools/[id]: the Super Admin manages one school's subscription, invoices and status. */
export function SchoolAccountView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const account = useApiData(`platform:account:${id}`, () => platformBillingApi.account(id));
  const plans = useApiData("platform:plans", platformBillingApi.plans);
  const [pending, setPending] = useState<Pending>(null);
  const data = account.data;

  const back = (
    <Link href="/app/platform/schools" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("billing.account.back")}
    </Link>
  );

  if (!data) {
    return (
      <>
        {back}
        <section className="card">
          {account.error ? (
            account.error.status === 404 ? (
              <p className="empty">{t("billing.account.notFound")}</p>
            ) : (
              <ErrorState error={account.error} onRetry={account.reload} />
            )
          ) : (
            <LoadingRows rows={6} />
          )}
        </section>
      </>
    );
  }

  const school = data.school;
  const sub = data.subscription;
  const suspended = school.status === "SUSPENDED";
  const close = () => setPending(null);
  const done = (message: string) => {
    setPending(null);
    account.reload();
    toast(message);
  };

  return (
    <>
      {back}
      <PageHead
        eyebrow={
          <>
            {t("billing.account.eyebrow")} · <span className="mono">{school.code}</span>
          </>
        }
        title={school.name}
        actions={
          <Pill tone={statusTone(school.status)} dot>
            {translateOr(t, `status.${school.status}`, school.status)}
          </Pill>
        }
      />

      {suspended && data.suspension ? (
        <div className="alert alert-bad flex-wrap items-center" role="status" data-testid="account-suspended">
          <Ban size={18} aria-hidden="true" className="flex-none" />
          <span className="min-w-0 flex-1">
            {t("billing.account.suspendedNote", {
              date: formatDateTime(data.suspension.suspendedAt, locale),
              reason: data.suspension.reason,
            })}
          </span>
        </div>
      ) : null}
      <NoticeAlert notice={data.notice} testId="account-notice" />

      <div className="grid gap-4 lg:grid-cols-2">
        <section className="card flex flex-col gap-3" aria-labelledby="account-subscription">
          <div className="card-head mb-0">
            <h2 id="account-subscription">{t("billing.account.subscription")}</h2>
            <span className="text-[13px] font-semibold text-ink-2">{planLabel(t, school.plan as Plan)}</span>
          </div>
          <dl className="kv">
            {school.status === "TRIAL" && school.trialEndsAt ? (
              <>
                <dt>{t("billing.school.trialEnds")}</dt>
                <dd>{formatDate(school.trialEndsAt, locale)}</dd>
              </>
            ) : null}
            {sub ? (
              <>
                <dt>{t("billing.subscription.cycle")}</dt>
                <dd>{cycleLabel(t, sub.billingCycle)}</dd>
                <dt>{t("billing.subscription.students")}</dt>
                <dd>{sub.billedStudents.toLocaleString("en-IN")}</dd>
                <dt>{t("billing.subscription.period")}</dt>
                <dd>{periodLabel(sub.periodStart, sub.periodEnd, locale)}</dd>
                <dt>{t("billing.subscription.renewal")}</dt>
                <dd data-testid="account-renewal">{formatPlainDate(sub.nextRenewalOn, locale)}</dd>
                <dt>{t("billing.subscription.paidSince")}</dt>
                <dd>{formatPlainDate(sub.paidSince, locale)}</dd>
                <dt>{t("billing.subscription.nextInvoice")}</dt>
                <dd>
                  {t("billing.subscription.nextInvoiceValue", {
                    amount: formatPaise(sub.nextInvoiceTaxablePaise),
                    students: sub.billedStudents.toLocaleString("en-IN"),
                    price: formatPaise(sub.unitPricePaise),
                  })}
                </dd>
              </>
            ) : (
              <>
                <dt>{t("billing.subscription.paid")}</dt>
                <dd>{t("billing.subscription.notPaying")}</dd>
              </>
            )}
            <dt>{t("billing.school.activeStudents")}</dt>
            <dd>{data.activeStudents.toLocaleString("en-IN")}</dd>
            <dt>{t("billing.account.users")}</dt>
            <dd>{school.userCount.toLocaleString("en-IN")}</dd>
          </dl>
          {!suspended ? (
            <div className="flex flex-wrap gap-2">
              {sub ? (
                <button type="button" className="btn btn-primary btn-sm" onClick={() => setPending("issue")}>
                  <FilePlus2 size={16} aria-hidden="true" />
                  {t("billing.issue.button")}
                </button>
              ) : (
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={() => setPending("start")}
                  disabled={!data.details.stateCode}
                >
                  <Play size={16} aria-hidden="true" />
                  {t("billing.start.button")}
                </button>
              )}
              <button type="button" className="btn btn-sm" onClick={() => setPending("change")}>
                <RefreshCcw size={16} aria-hidden="true" />
                {t("billing.change.button")}
              </button>
            </div>
          ) : null}
          {!sub && !suspended && !data.details.stateCode ? (
            <p className="text-[13px] text-ink-3">{t("billing.start.needsState")}</p>
          ) : null}
        </section>

        <section className="card flex flex-col gap-3" aria-labelledby="account-details">
          <div className="card-head mb-0">
            <h2 id="account-details">{t("billing.details.heading")}</h2>
            <button type="button" className="btn btn-sm" onClick={() => setPending("details")}>
              <Pencil size={16} aria-hidden="true" />
              {t("common.edit")}
            </button>
          </div>
          <DetailsList details={data.details} schoolName={school.name} />
          <p className="text-[13px] text-ink-3">{t("billing.details.futureOnly")}</p>
        </section>
      </div>

      <section className="card" aria-labelledby="account-invoices">
        <div className="card-head">
          <h2 id="account-invoices">{t("billing.invoices.title")}</h2>
          <span className="text-[13px] text-ink-2" data-testid="account-unpaid">
            {t("billing.account.unpaid", {
              unpaid: formatPaise(data.unpaidPaise),
              overdue: formatPaise(data.overduePaise),
            })}
          </span>
        </div>
        <InvoiceTable
          invoices={data.invoices}
          hrefFor={(invoiceId) =>
            `/app/platform/schools/${encodeURIComponent(school.id)}/invoices/${encodeURIComponent(invoiceId)}`
          }
        />
      </section>

      <section className="card flex flex-col gap-3" aria-labelledby="account-status">
        <h2 id="account-status">{t("billing.account.status")}</h2>
        <p className="text-[14px] text-ink-2">{t("billing.account.statusNote")}</p>
        <div className="flex flex-wrap gap-2">
          {school.status === "ACTIVE" ? (
            <button type="button" className="btn btn-sm" onClick={() => setPending("pastDue")}>
              {t("billing.status.markPastDue")}
            </button>
          ) : null}
          {school.status === "PAST_DUE" ? (
            <button type="button" className="btn btn-sm" onClick={() => setPending("active")}>
              {t("billing.status.markActive")}
            </button>
          ) : null}
          {suspended ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => setPending("reactivate")}>
              <Play size={16} aria-hidden="true" />
              {t("billing.reactivate.button")}
            </button>
          ) : (
            <button type="button" className="btn btn-danger btn-sm" onClick={() => setPending("suspend")}>
              <Ban size={16} aria-hidden="true" />
              {t("billing.suspend.button")}
            </button>
          )}
        </div>
      </section>

      {pending === "details" ? (
        <DetailsDialog
          details={data.details}
          schoolName={school.name}
          onClose={close}
          onSave={async (body) => {
            await platformBillingApi.updateDetails(school.id, body);
            done(t("billing.details.saved"));
          }}
        />
      ) : null}
      {pending === "start" || pending === "change" ? (
        <SubscriptionDialog
          account={data}
          plans={plans.data}
          mode={pending}
          onClose={close}
          onDone={(next) =>
            done(
              pending === "start"
                ? t("billing.start.done", { number: next.invoices[0]?.invoiceNo ?? "" })
                : t("billing.change.done"),
            )
          }
        />
      ) : null}
      {pending === "suspend" ? (
        <SuspendDialog
          account={data}
          onClose={close}
          onDone={() => done(t("billing.suspend.done", { name: school.name }))}
        />
      ) : null}
      <ConfirmDialog
        open={pending === "issue"}
        title={t("billing.issue.title")}
        body={
          sub
            ? t("billing.issue.body", {
                amount: formatPaise(sub.nextInvoiceTaxablePaise),
                date: formatPlainDate(sub.nextRenewalOn, locale),
              })
            : ""
        }
        confirmLabel={t("billing.issue.submit")}
        danger={false}
        onClose={close}
        onConfirm={async () => {
          const invoice = await platformBillingApi.issueNext(school.id);
          done(t("billing.issue.done", { number: invoice.invoiceNo }));
        }}
      />
      <ConfirmDialog
        open={pending === "reactivate"}
        title={t("billing.reactivate.title", { name: school.name })}
        body={t("billing.reactivate.body")}
        confirmLabel={t("billing.reactivate.button")}
        danger={false}
        onClose={close}
        onConfirm={async () => {
          await platformBillingApi.reactivate(school.id);
          done(t("billing.reactivate.done", { name: school.name }));
        }}
      />
      <ConfirmDialog
        open={pending === "pastDue" || pending === "active"}
        title={pending === "active" ? t("billing.status.activeTitle") : t("billing.status.pastDueTitle")}
        body={pending === "active" ? t("billing.status.activeBody") : t("billing.status.pastDueBody")}
        confirmLabel={pending === "active" ? t("billing.status.markActive") : t("billing.status.markPastDue")}
        danger={false}
        onClose={close}
        onConfirm={async () => {
          const status = pending === "active" ? "ACTIVE" : "PAST_DUE";
          await platformBillingApi.setStatus(school.id, status);
          done(t("billing.status.done", { status: translateOr(t, `status.${status}`, status) }));
        }}
      />
    </>
  );
}
