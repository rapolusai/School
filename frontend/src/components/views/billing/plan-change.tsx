"use client";

import { CalendarClock } from "lucide-react";
import { useEffect, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { billingApi } from "@/lib/billing-api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { notifyPlanChanged } from "@/lib/plan-access";
import type { Plan, PlanChangeQuote, PlanView, SchoolBilling } from "@/lib/types";
import { planLabel } from "./billing-ui";

/*
 * A School Admin changes the school's plan from the Billing page (docs/api/phase-3-subscriptions.md, section 4):
 * upgrade at once with an invoice for the rest of the period, downgrade at the next renewal, cancel at the period's
 * end. Each can be seen first (the API's quote) and confirmed.
 */

/** A dearer plan is an upgrade (as the API decides). */
export function isUpgrade(plans: readonly PlanView[], from: Plan, to: Plan): boolean {
  const price = (plan: Plan) => plans.find((p) => p.plan === plan)?.pricePerStudentPerYearPaise ?? 0;
  return price(to) > price(from);
}

/** The button on a plan card: Upgrade, Downgrade (or Switch during the trial); none on the current plan. */
export function PlanChangeButton({
  billing,
  plan,
  onChanged,
}: {
  billing: SchoolBilling;
  plan: PlanView;
  onChanged: (billing: SchoolBilling) => void;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const pending = billing.subscription?.pendingPlan ?? null;
  if (plan.plan === billing.plan || plan.plan === pending) return null;
  const label = !billing.subscription
    ? t("billing.self.switch")
    : isUpgrade(billing.plans, billing.plan, plan.plan)
      ? t("billing.self.upgrade")
      : t("billing.self.downgrade");
  return (
    <>
      <button
        type="button"
        className="btn btn-sm mt-auto self-start"
        aria-label={t("billing.self.buttonFor", { action: label, plan: planLabel(t, plan.plan) })}
        data-testid={`plan-change-${plan.plan}`}
        onClick={() => setOpen(true)}
      >
        {label}
      </button>
      {open ? (
        <PlanChangeDialog
          plan={plan.plan}
          onClose={() => setOpen(false)}
          onChanged={(updated) => {
            setOpen(false);
            onChanged(updated);
          }}
        />
      ) : null}
    </>
  );
}

/** What the change would do (from the API's quote), confirmed by the School Admin. */
export function PlanChangeDialog({
  plan,
  onClose,
  onChanged,
}: {
  plan: Plan;
  onClose: () => void;
  onChanged: (billing: SchoolBilling) => void;
}) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const [quote, setQuote] = useState<PlanChangeQuote | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const name = planLabel(t, plan);

  useEffect(() => {
    let cancelled = false;
    billingApi
      .planQuote(plan)
      .then((q) => {
        if (!cancelled) setQuote(q);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(errorMessage(toApiError(e), t));
      });
    return () => {
      cancelled = true;
    };
  }, [plan, t]);

  const confirm = async () => {
    setSaving(true);
    setError(null);
    try {
      const updated = await billingApi.changePlan(plan);
      notifyPlanChanged();
      const date = formatPlainDate(quote?.effectiveOn, locale);
      toast(
        quote?.kind === "DOWNGRADE"
          ? t("billing.self.scheduled", { plan: name, date })
          : t("billing.self.upgraded", { plan: name }),
      );
      onChanged(updated);
    } catch (e) {
      setError(errorMessage(toApiError(e), t));
      setSaving(false);
    }
  };

  return (
    <Dialog open onClose={onClose} title={t("billing.self.title", { plan: name })} closeLabel={t("common.close")}>
      <div className="flex flex-col gap-3.5" data-testid="plan-change-dialog">
        <FormAlert message={error} />
        {!quote && !error ? <LoadingRows rows={2} /> : null}
        {quote ? <QuoteText quote={quote} name={name} locale={locale} /> : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!quote || !quote.allowed || saving}
            onClick={() => void confirm()}
          >
            {saving ? t("common.saving") : t("billing.self.confirm")}
          </button>
        </div>
      </div>
    </Dialog>
  );
}

function QuoteText({ quote, name, locale }: { quote: PlanChangeQuote; name: string; locale: string }) {
  const { t } = useI18n();
  if (!quote.allowed) {
    return (
      <p className="alert alert-bad" role="alert" data-testid="plan-change-refused">
        {t("billing.self.downgradeRefused", {
          plan: name,
          max: (quote.maxStudents ?? 0).toLocaleString("en-IN"),
          active: quote.activeStudents.toLocaleString("en-IN"),
        })}
      </p>
    );
  }
  if (quote.kind === "TRIAL") return <p data-testid="plan-change-quote">{t("billing.self.trial")}</p>;
  if (quote.kind === "DOWNGRADE") {
    return (
      <p data-testid="plan-change-quote">
        {t("billing.self.downgradeOn", { plan: name, date: formatPlainDate(quote.effectiveOn, locale) })}
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-2" data-testid="plan-change-quote">
      <p>{t("billing.self.upgradeNow", { plan: name })}</p>
      <p>
        {quote.totalPaise > 0
          ? t("billing.self.upgradeInvoice", {
              days: quote.remainingDays,
              total: quote.periodDays,
              students: quote.billedStudents.toLocaleString("en-IN"),
              unit: formatPaise(quote.unitPricePaise),
              amount: formatPaise(quote.taxablePaise),
              totalAmount: formatPaise(quote.totalPaise),
            })
          : t("billing.self.upgradeNoInvoice", { plan: name })}
      </p>
    </div>
  );
}

/**
 * Scheduled changes on the plan summary: a pending downgrade with "Keep <plan>", a cancellation with "Keep
 * subscription", and "Cancel subscription" for a paying school. `canChange`: the School Admin (settings.manage).
 */
export function SubscriptionChanges({
  billing,
  canChange,
  onChanged,
}: {
  billing: SchoolBilling;
  canChange: boolean;
  onChanged: (billing: SchoolBilling) => void;
}) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const sub = billing.subscription;
  if (!sub) return null;
  const current = planLabel(t, billing.plan);
  const run = async (action: () => Promise<SchoolBilling>, done: string) => {
    setBusy(true);
    setError(null);
    try {
      const updated = await action();
      toast(done);
      onChanged(updated);
    } catch (e) {
      setError(errorMessage(toApiError(e), t));
    } finally {
      setBusy(false);
    }
  };
  const endsOn = formatPlainDate(sub.periodEnd, locale);

  return (
    <div className="flex flex-col gap-2">
      <FormAlert message={error} />
      {sub.pendingPlan ? (
        <div className="alert alert-warn" role="status" data-testid="billing-pending-plan">
          <CalendarClock size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span className="flex flex-1 flex-wrap items-center justify-between gap-2">
            {t("billing.self.pending", {
              plan: planLabel(t, sub.pendingPlan),
              date: formatPlainDate(sub.nextRenewalOn, locale),
            })}
            {canChange ? (
              <button
                type="button"
                className="btn btn-sm"
                disabled={busy}
                onClick={() =>
                  void run(billingApi.withdrawDowngrade, t("billing.self.kept", { plan: current }))
                }
              >
                {t("billing.self.keep", { plan: current })}
              </button>
            ) : null}
          </span>
        </div>
      ) : null}
      {sub.cancelAtPeriodEnd ? (
        <div className="alert alert-bad" role="status" data-testid="billing-cancelled">
          <CalendarClock size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span className="flex flex-1 flex-wrap items-center justify-between gap-2">
            {t("billing.end.scheduled", { date: endsOn })}
            {canChange ? (
              <button
                type="button"
                className="btn btn-sm"
                disabled={busy}
                onClick={() => void run(billingApi.resumeSubscription, t("billing.end.resumed"))}
              >
                {t("billing.end.resume")}
              </button>
            ) : null}
          </span>
        </div>
      ) : canChange ? (
        <button
          type="button"
          className="btn btn-sm self-start"
          data-testid="billing-cancel"
          onClick={() => setCancelling(true)}
        >
          {t("billing.end.button")}
        </button>
      ) : null}
      {cancelling ? (
        <CancelDialog
          endsOn={endsOn}
          onClose={() => setCancelling(false)}
          onCancelled={(updated) => {
            setCancelling(false);
            toast(t("billing.end.done", { date: endsOn }));
            onChanged(updated);
          }}
        />
      ) : null}
    </div>
  );
}

function CancelDialog({
  endsOn,
  onClose,
  onCancelled,
}: {
  endsOn: string;
  onClose: () => void;
  onCancelled: (billing: SchoolBilling) => void;
}) {
  const { t } = useI18n();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      onCancelled(await billingApi.cancelSubscription(reason.trim() || null));
    } catch (e) {
      setError(errorMessage(toApiError(e), t));
      setSaving(false);
    }
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("billing.end.title")}
      description={t("billing.end.body", { date: endsOn })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void submit(e)} noValidate>
        <FormAlert message={error} />
        <TextAreaField
          label={t("billing.end.reason")}
          name="reason"
          value={reason}
          maxLength={500}
          rows={3}
          onChange={(e) => setReason(e.target.value)}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("billing.end.back")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={saving}>
            {saving ? t("common.saving") : t("billing.end.confirm")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
