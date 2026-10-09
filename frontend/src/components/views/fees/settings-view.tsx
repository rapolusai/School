"use client";

import { Pencil, Plus, Tags } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { feesApi } from "@/lib/fees-api";
import { formatPaise, parseRupees, rupeesInput } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import {
  FEE_HEAD_KINDS,
  LATE_FEE_MODES,
  MAX_AMOUNT_PAISE,
  type FeeHead,
  type FeeHeadKind,
  type LateFeeMode,
  type LateFeeRule,
} from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { FeesNav } from "./fee-ui";

/** /app/fees/settings: the school's fee heads and its late fee rule. */
export function FeeSettingsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const canManage = hasPermission(me, PERMISSIONS.feesManage);
  const heads = useApiData("fees:heads", feesApi.listHeads);
  return (
    <>
      <PageHead eyebrow={t("fees.eyebrow")} title={t("fees.settings.title")} />
      <FeesNav />
      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2">
        <HeadsCard heads={heads} canManage={canManage} />
        <LateFeeCard canManage={canManage} />
      </div>
    </>
  );
}

type HeadValues = { name: string; kind: FeeHeadKind; oneTime: boolean; active: boolean };

export function validateHead(values: HeadValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 60) problems.name = "validation.tooLong";
  return problems;
}

function HeadsCard({ heads, canManage }: { heads: ApiData<FeeHead[]>; canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [editing, setEditing] = useState<FeeHead | "new" | null>(null);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const list = heads.data ?? [];

  const addDefaults = async () => {
    setAdding(true);
    setError(null);
    try {
      await feesApi.addDefaultHeads();
      heads.reload();
      toast(t("fees.heads.defaultsAdded"));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setAdding(false);
    }
  };

  return (
    <section className="card" aria-labelledby="heads-title">
      <div className="card-head">
        <div>
          <h2 id="heads-title">{t("fees.heads.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("fees.heads.sub")}</p>
        </div>
        {canManage ? (
          <button type="button" className="btn btn-primary" onClick={() => setEditing("new")}>
            <Plus size={18} aria-hidden="true" />
            {t("fees.heads.add")}
          </button>
        ) : null}
      </div>
      <FormAlert message={error} />
      {heads.error && !heads.data ? (
        <ErrorState error={heads.error} onRetry={heads.reload} />
      ) : !heads.data ? (
        <LoadingRows rows={4} />
      ) : list.length === 0 ? (
        <div className="empty flex flex-col items-center gap-3">
          <p>{t("fees.heads.empty")}</p>
          {canManage ? (
            <button type="button" className="btn" onClick={addDefaults} disabled={adding}>
              {adding ? t("common.working") : t("fees.heads.addDefaults")}
            </button>
          ) : null}
        </div>
      ) : (
        <ul className="list" data-testid="heads-list">
          {list.map((head) => (
            <li key={head.id} className="li items-center">
              <span className="badge-ic">
                <Tags size={18} aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2 font-semibold">
                  {head.name}
                  {head.oneTime ? <Pill tone="info">{t("fees.heads.oneTime")}</Pill> : null}
                  {!head.active ? <Pill>{t("fees.heads.inactive")}</Pill> : null}
                </p>
                <p className="text-[13px] text-ink-3">
                  {translateOr(t, `fees.headKind.${head.kind}`, head.kind)}
                  {head.inUse ? ` · ${t("fees.heads.inUse")}` : ""}
                </p>
              </div>
              {canManage ? (
                <button
                  type="button"
                  className="iconbtn"
                  aria-label={t("fees.heads.editNamed", { name: head.name })}
                  onClick={() => setEditing(head)}
                >
                  <Pencil size={18} aria-hidden="true" />
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {editing ? (
        <HeadDialog
          head={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            heads.reload();
            toast(t("fees.heads.saved", { name: saved.name }));
          }}
        />
      ) : null}
    </section>
  );
}

function HeadDialog({
  head,
  onClose,
  onSaved,
}: {
  head: FeeHead | null;
  onClose: () => void;
  onSaved: (head: FeeHead) => void;
}) {
  const { t } = useI18n();
  const form = useForm<HeadValues>(
    head
      ? { name: head.name, kind: head.kind, oneTime: head.oneTime, active: head.active }
      : { name: "", kind: "OTHER", oneTime: false, active: true },
  );
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateHead(values), t, event.currentTarget)) return;
    const body = { ...values, name: values.name.trim() };
    let saved: FeeHead | undefined;
    const ok = await form.submit(t, async () => {
      saved = head ? await feesApi.updateHead(head.id, body) : await feesApi.createHead(body);
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={head ? t("fees.heads.edit") : t("fees.heads.add")}
      description={t("fees.heads.dialog")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("fees.heads.name")}
          name="name"
          value={values.name}
          maxLength={60}
          placeholder={t("fees.heads.name.placeholder")}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
          required
          data-autofocus
        />
        <SelectField
          label={t("fees.heads.kind")}
          name="kind"
          value={values.kind}
          onChange={(e) => set("kind", e.target.value as FeeHeadKind)}
          hint={t("fees.heads.kind.hint")}
          error={errors.kind}
          options={FEE_HEAD_KINDS.map((kind) => ({ value: kind, label: t(`fees.headKind.${kind}`) }))}
        />
        <label className="check">
          <input
            type="checkbox"
            name="oneTime"
            checked={values.oneTime}
            onChange={(e) => set("oneTime", e.target.checked)}
          />
          <span>{t("fees.heads.oneTime.label")}</span>
        </label>
        {head ? (
          <label className="check">
            <input
              type="checkbox"
              name="active"
              checked={values.active}
              onChange={(e) => set("active", e.target.checked)}
            />
            <span>{t("fees.heads.active.label")}</span>
          </label>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
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

export type LateFeeValues = { mode: LateFeeMode; graceDays: string; flat: string; perDay: string; cap: string };

/** The same checks PUT /api/fees/late-fee-rule makes. */
export function validateLateFee(values: LateFeeValues): Problems {
  const problems: Problems = {};
  if (values.mode === "NONE") return problems;
  if (!/^\d{1,3}$/.test(values.graceDays.trim()) || Number(values.graceDays) > 365) {
    problems.graceDays = "fees.v.graceDays";
  }
  const amount = (text: string) => parseRupees(text);
  if (values.mode === "FLAT") {
    const flat = amount(values.flat);
    if (flat === null || flat < 1 || flat > MAX_AMOUNT_PAISE) problems.flatPaise = "fees.v.amount";
  } else {
    const perDay = amount(values.perDay);
    if (perDay === null || perDay < 1 || perDay > MAX_AMOUNT_PAISE) problems.perDayPaise = "fees.v.amount";
    if (values.cap.trim()) {
      const cap = amount(values.cap);
      if (cap === null || cap > MAX_AMOUNT_PAISE) problems.capPaise = "fees.v.amount";
      else if (perDay !== null && cap > 0 && cap < perDay) problems.capPaise = "fees.v.capBelowDay";
    }
  }
  return problems;
}

export function toLateFeeRule(values: LateFeeValues): LateFeeRule {
  const paise = (text: string) => parseRupees(text) ?? 0;
  if (values.mode === "NONE") return { mode: "NONE", graceDays: 0, flatPaise: 0, perDayPaise: 0, capPaise: 0 };
  return {
    mode: values.mode,
    graceDays: Number(values.graceDays.trim() || "0"),
    flatPaise: values.mode === "FLAT" ? paise(values.flat) : 0,
    perDayPaise: values.mode === "PER_DAY" ? paise(values.perDay) : 0,
    capPaise: values.mode === "PER_DAY" ? paise(values.cap) : 0,
  };
}

function lateFeeValues(rule: LateFeeRule): LateFeeValues {
  const text = (paise: number) => (paise ? rupeesInput(paise) : "");
  return {
    mode: rule.mode,
    graceDays: String(rule.graceDays),
    flat: text(rule.flatPaise),
    perDay: text(rule.perDayPaise),
    cap: text(rule.capPaise),
  };
}

/** One sentence that says what the rule charges. */
export function describeLateFee(rule: LateFeeRule, t: ReturnType<typeof useI18n>["t"]): string {
  if (rule.mode === "FLAT") {
    return t("fees.lateFee.describe.flat", { amount: formatPaise(rule.flatPaise), days: rule.graceDays });
  }
  if (rule.mode === "PER_DAY") {
    return rule.capPaise
      ? t("fees.lateFee.describe.perDayCap", {
          amount: formatPaise(rule.perDayPaise),
          days: rule.graceDays,
          cap: formatPaise(rule.capPaise),
        })
      : t("fees.lateFee.describe.perDay", { amount: formatPaise(rule.perDayPaise), days: rule.graceDays });
  }
  return t("fees.lateFee.describe.none");
}

function LateFeeCard({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const rule = useApiData("fees:late-fee-rule", feesApi.getLateFeeRule);
  return (
    <section className="card" aria-labelledby="late-fee-title">
      <div className="card-head">
        <div>
          <h2 id="late-fee-title">{t("fees.lateFee.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("fees.lateFee.sub")}</p>
        </div>
      </div>
      {rule.error && !rule.data ? (
        <ErrorState error={rule.error} onRetry={rule.reload} />
      ) : !rule.data ? (
        <LoadingRows rows={3} />
      ) : canManage ? (
        <LateFeeForm key={JSON.stringify(rule.data)} rule={rule.data} onSaved={rule.reload} />
      ) : (
        <p className="text-sm" data-testid="late-fee-rule">
          {describeLateFee(rule.data, t)}
        </p>
      )}
    </section>
  );
}

function LateFeeForm({ rule, onSaved }: { rule: LateFeeRule; onSaved: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<LateFeeValues>(lateFeeValues(rule));
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateLateFee(values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await feesApi.updateLateFeeRule(toLateFeeRule(values));
    });
    if (ok) {
      toast(t("fees.lateFee.saved"));
      onSaved();
    }
  };
  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
      <FormAlert message={form.formError} />
      <p className="text-sm text-ink-2" data-testid="late-fee-rule">
        {describeLateFee(rule, t)}
      </p>
      <fieldset className="field m-0 border-0 p-0">
        <legend className="field-label mb-1.5">{t("fees.lateFee.mode")}</legend>
        <div className="seg">
          {LATE_FEE_MODES.map((mode) => (
            <label key={mode}>
              <input
                type="radio"
                name="mode"
                value={mode}
                checked={values.mode === mode}
                onChange={() => set("mode", mode)}
              />
              {t(`fees.lateFee.mode.${mode}`)}
            </label>
          ))}
        </div>
      </fieldset>
      {values.mode !== "NONE" ? (
        <div className="grid2">
          <TextField
            label={t("fees.lateFee.graceDays")}
            name="graceDays"
            inputMode="numeric"
            value={values.graceDays}
            maxLength={3}
            hint={t("fees.lateFee.graceDays.hint")}
            onChange={(e) => set("graceDays", e.target.value)}
            error={errors.graceDays}
          />
          {values.mode === "FLAT" ? (
            <TextField
              label={t("fees.lateFee.flat")}
              name="flatPaise"
              inputMode="decimal"
              value={values.flat}
              onChange={(e) => set("flat", e.target.value)}
              error={errors.flatPaise}
              required
            />
          ) : (
            <TextField
              label={t("fees.lateFee.perDay")}
              name="perDayPaise"
              inputMode="decimal"
              value={values.perDay}
              onChange={(e) => set("perDay", e.target.value)}
              error={errors.perDayPaise}
              required
            />
          )}
          {values.mode === "PER_DAY" ? (
            <TextField
              label={t("fees.lateFee.cap")}
              name="capPaise"
              inputMode="decimal"
              value={values.cap}
              hint={t("fees.lateFee.cap.hint")}
              onChange={(e) => set("cap", e.target.value)}
              error={errors.capPaise}
            />
          ) : null}
        </div>
      ) : null}
      <div className="flex justify-end">
        <button type="submit" className="btn btn-primary" disabled={form.submitting}>
          {form.submitting ? t("common.saving") : t("fees.lateFee.save")}
        </button>
      </div>
    </form>
  );
}
