"use client";

import { Plus, Trash2, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPaise, formatPlainDate, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { localeFor, translateOr, useI18n, type Translate } from "@/lib/i18n";
import {
  ACCOUNT_PATTERN,
  blankToNull,
  IFSC_PATTERN,
  REGISTRATION_PATTERN,
  STATE_CODES,
  TAN_PATTERN,
} from "@/lib/payroll";
import { payrollApi } from "@/lib/payroll-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type {
  PayrollSettingsView,
  ProrationBasis,
  PtAppliesTo,
  PtSlabsView,
  PtSlabView,
  StatutoryRateView,
  TaxRuleView,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { AccountantNote, PayrollNav } from "./payroll-ui";

export function stateName(t: Translate, code: string): string {
  return translateOr(t, `payroll.state.${code}`, code);
}

function percent(value: number): string {
  return `${Number(value.toFixed(4))}%`;
}

export type SettingsValues = {
  payDay: string;
  prorationBasis: ProrationBasis;
  pfEnabled: boolean;
  pfOnFullWages: boolean;
  esiEnabled: boolean;
  ptEnabled: boolean;
  ptState: string;
  pfEstablishmentCode: string;
  esiEmployerCode: string;
  ptRegistration: string;
  tan: string;
  bankName: string;
  bankBranch: string;
  bankAccountNumber: string;
  bankIfsc: string;
};

export function settingsValues(s: PayrollSettingsView): SettingsValues {
  return {
    payDay: String(s.payDay),
    prorationBasis: s.prorationBasis,
    pfEnabled: s.pfEnabled,
    pfOnFullWages: s.pfOnFullWages,
    esiEnabled: s.esiEnabled,
    ptEnabled: s.ptEnabled,
    ptState: s.ptState ?? "",
    pfEstablishmentCode: s.pfEstablishmentCode ?? "",
    esiEmployerCode: s.esiEmployerCode ?? "",
    ptRegistration: s.ptRegistration ?? "",
    tan: s.tan ?? "",
    bankName: s.bankName ?? "",
    bankBranch: s.bankBranch ?? "",
    bankAccountNumber: s.bankAccountNumber ?? "",
    bankIfsc: s.bankIfsc ?? "",
  };
}

/** The checks PUT /api/payroll/settings makes. Exported for tests. */
export function validateSettings(values: SettingsValues): Problems {
  const problems: Problems = {};
  const day = values.payDay.trim();
  if (!/^\d{1,2}$/.test(day) || Number(day) < 1 || Number(day) > 31) problems.payDay = "payroll.v.payDay";
  if (values.ptEnabled && !values.ptState) problems.ptState = "payroll.v.ptState";
  for (const field of ["pfEstablishmentCode", "esiEmployerCode", "ptRegistration"] as const) {
    const v = values[field].trim();
    if (v && !REGISTRATION_PATTERN.test(v)) problems[field] = "payroll.v.registration";
  }
  if (values.tan.trim() && !TAN_PATTERN.test(values.tan.trim())) problems.tan = "payroll.v.tan";
  if (values.bankName.trim().length > 100) problems.bankName = "validation.tooLong";
  if (values.bankBranch.trim().length > 100) problems.bankBranch = "validation.tooLong";
  const account = values.bankAccountNumber.trim();
  const ifsc = values.bankIfsc.trim();
  if (account && !ACCOUNT_PATTERN.test(account)) problems.bankAccountNumber = "payroll.v.account";
  if (ifsc && !IFSC_PATTERN.test(ifsc)) problems.bankIfsc = "payroll.v.ifsc";
  if (account && !ifsc) problems.bankIfsc = "payroll.v.together";
  if (ifsc && !account) problems.bankAccountNumber = "payroll.v.together";
  return problems;
}

/** /app/payroll/settings: pay day, proration, PF/ESI/PT switches, registrations, statutory figures and PT slabs. */
export function PayrollSettingsView() {
  const { t } = useI18n();
  const { me } = useAuth();
  const canManage = hasPermission(me, PERMISSIONS.payrollManage);
  const settings = useApiData("payroll:settings", payrollApi.settings);
  const s = settings.data;
  return (
    <>
      <PageHead eyebrow={t("payroll.eyebrow")} title={t("payroll.settings.title")} />
      <PayrollNav />
      <AccountantNote />
      {settings.error && !s ? (
        <section className="card">
          <ErrorState error={settings.error} onRetry={settings.reload} />
        </section>
      ) : !s ? (
        <section className="card">
          <LoadingRows rows={8} />
        </section>
      ) : (
        <>
          <SettingsCard key={s.updatedAt ?? "new"} settings={s} canManage={canManage} onSaved={settings.reload} />
          <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2">
            <RatesCard rate={s.statutory.rate} />
            <TaxCard settings={s} />
          </div>
          <PtSlabsCard settings={s} canManage={canManage} />
        </>
      )}
    </>
  );
}

function SettingsCard({
  settings,
  canManage,
  onSaved,
}: {
  settings: PayrollSettingsView;
  canManage: boolean;
  onSaved: () => void;
}) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const form = useForm<SettingsValues>(settingsValues(settings));
  const { values, set, errors } = form;
  const states = [...new Set([...STATE_CODES, ...settings.statutory.ptStates])].sort((a, b) =>
    stateName(t, a).localeCompare(stateName(t, b)),
  );
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canManage) return;
    if (!form.check(validateSettings(values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await payrollApi.saveSettings({
        payDay: Number(values.payDay.trim()),
        prorationBasis: values.prorationBasis,
        pfEnabled: values.pfEnabled,
        pfOnFullWages: values.pfEnabled && values.pfOnFullWages,
        esiEnabled: values.esiEnabled,
        ptEnabled: values.ptEnabled,
        ptState: blankToNull(values.ptState, true),
        pfEstablishmentCode: blankToNull(values.pfEstablishmentCode, true),
        esiEmployerCode: blankToNull(values.esiEmployerCode, true),
        ptRegistration: blankToNull(values.ptRegistration, true),
        tan: blankToNull(values.tan, true),
        bankName: blankToNull(values.bankName),
        bankBranch: blankToNull(values.bankBranch),
        bankAccountNumber: blankToNull(values.bankAccountNumber),
        bankIfsc: blankToNull(values.bankIfsc, true),
      });
    });
    if (ok) {
      toast(t("payroll.settings.saved"));
      onSaved();
    }
  };
  const disabled = !canManage;
  return (
    <section className="card" aria-labelledby="payroll-settings-title">
      <div className="card-head">
        <div>
          <h2 id="payroll-settings-title">{t("payroll.settings.school")}</h2>
          <p className="mt-1 text-sm text-ink-2">
            {settings.saved && settings.updatedAt
              ? t("payroll.settings.updated", { date: formatDateTime(settings.updatedAt, localeFor(lang)) })
              : t("payroll.settings.defaults")}
          </p>
        </div>
      </div>
      <form method="post" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <fieldset className="flex flex-col gap-3" disabled={disabled}>
          <legend className="field-label mb-2 font-semibold text-ink">{t("payroll.settings.pay")}</legend>
          <div className="grid2">
            <TextField
              label={t("payroll.settings.payDay")}
              name="payDay"
              inputMode="numeric"
              value={values.payDay}
              maxLength={2}
              hint={t("payroll.settings.payDayHint")}
              onChange={(e) => set("payDay", e.target.value)}
              error={errors.payDay}
              required
            />
            <SelectField
              label={t("payroll.settings.basis")}
              name="prorationBasis"
              value={values.prorationBasis}
              onChange={(e) => set("prorationBasis", e.target.value as ProrationBasis)}
              hint={t("payroll.settings.basisHint")}
              error={errors.prorationBasis}
              options={[
                { value: "CALENDAR_DAYS", label: t("payroll.basis.CALENDAR_DAYS") },
                { value: "WORKING_DAYS", label: t("payroll.basis.WORKING_DAYS") },
              ]}
            />
          </div>
        </fieldset>
        <fieldset className="flex flex-col gap-3" disabled={disabled}>
          <legend className="field-label mb-2 font-semibold text-ink">{t("payroll.settings.statutory")}</legend>
          <label className="check">
            <input
              type="checkbox"
              name="pfEnabled"
              checked={values.pfEnabled}
              onChange={(e) => set("pfEnabled", e.target.checked)}
            />
            <span>{t("payroll.settings.pfEnabled")}</span>
          </label>
          {values.pfEnabled ? (
            <label className="check">
              <input
                type="checkbox"
                name="pfOnFullWages"
                checked={values.pfOnFullWages}
                onChange={(e) => set("pfOnFullWages", e.target.checked)}
              />
              <span>{t("payroll.settings.pfOnFullWages")}</span>
            </label>
          ) : null}
          <label className="check">
            <input
              type="checkbox"
              name="esiEnabled"
              checked={values.esiEnabled}
              onChange={(e) => set("esiEnabled", e.target.checked)}
            />
            <span>{t("payroll.settings.esiEnabled")}</span>
          </label>
          <label className="check">
            <input
              type="checkbox"
              name="ptEnabled"
              checked={values.ptEnabled}
              onChange={(e) => set("ptEnabled", e.target.checked)}
            />
            <span>{t("payroll.settings.ptEnabled")}</span>
          </label>
          <SelectField
            label={t("payroll.settings.ptState")}
            name="ptState"
            value={values.ptState}
            onChange={(e) => set("ptState", e.target.value)}
            hint={t("payroll.settings.ptStateHint")}
            error={errors.ptState}
            options={[
              { value: "", label: t("common.choose") },
              ...states.map((code) => ({ value: code, label: `${stateName(t, code)} (${code})` })),
            ]}
          />
        </fieldset>
        <fieldset className="flex flex-col gap-3" disabled={disabled}>
          <legend className="field-label mb-2 font-semibold text-ink">{t("payroll.settings.registrations")}</legend>
          <div className="grid2">
            <TextField
              label={t("payroll.settings.pfCode")}
              name="pfEstablishmentCode"
              value={values.pfEstablishmentCode}
              maxLength={30}
              onChange={(e) => set("pfEstablishmentCode", e.target.value.toUpperCase())}
              error={errors.pfEstablishmentCode}
            />
            <TextField
              label={t("payroll.settings.esiCode")}
              name="esiEmployerCode"
              value={values.esiEmployerCode}
              maxLength={30}
              onChange={(e) => set("esiEmployerCode", e.target.value.toUpperCase())}
              error={errors.esiEmployerCode}
            />
            <TextField
              label={t("payroll.settings.ptRegistration")}
              name="ptRegistration"
              value={values.ptRegistration}
              maxLength={30}
              onChange={(e) => set("ptRegistration", e.target.value.toUpperCase())}
              error={errors.ptRegistration}
            />
            <TextField
              label={t("payroll.settings.tan")}
              name="tan"
              value={values.tan}
              maxLength={10}
              placeholder="HYDA12345B"
              onChange={(e) => set("tan", e.target.value.toUpperCase())}
              error={errors.tan}
            />
          </div>
        </fieldset>
        <fieldset className="flex flex-col gap-3" disabled={disabled}>
          <legend className="field-label mb-2 font-semibold text-ink">{t("payroll.settings.bank")}</legend>
          <p className="-mt-1 text-[13px] text-ink-3">{t("payroll.settings.bankHint")}</p>
          <div className="grid2">
            <TextField
              label={t("payroll.settings.bankName")}
              name="bankName"
              value={values.bankName}
              maxLength={100}
              onChange={(e) => set("bankName", e.target.value)}
              error={errors.bankName}
            />
            <TextField
              label={t("payroll.settings.bankBranch")}
              name="bankBranch"
              value={values.bankBranch}
              maxLength={100}
              onChange={(e) => set("bankBranch", e.target.value)}
              error={errors.bankBranch}
            />
            <TextField
              label={t("payroll.settings.bankAccount")}
              name="bankAccountNumber"
              inputMode="numeric"
              value={values.bankAccountNumber}
              maxLength={18}
              onChange={(e) => set("bankAccountNumber", e.target.value.replace(/\s/g, ""))}
              error={errors.bankAccountNumber}
            />
            <TextField
              label={t("payroll.settings.bankIfsc")}
              name="bankIfsc"
              value={values.bankIfsc}
              maxLength={11}
              placeholder="SBIN0001234"
              onChange={(e) => set("bankIfsc", e.target.value.toUpperCase())}
              error={errors.bankIfsc}
            />
          </div>
        </fieldset>
        {canManage ? (
          <div className="flex justify-end">
            <button type="submit" className="btn btn-primary" disabled={form.submitting}>
              {form.submitting ? t("common.saving") : t("payroll.settings.save")}
            </button>
          </div>
        ) : null}
      </form>
    </section>
  );
}

function RatesCard({ rate }: { rate: StatutoryRateView | null }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <section className="card" aria-labelledby="payroll-rates-title" data-testid="payroll-rates">
      <div className="card-head">
        <div>
          <h2 id="payroll-rates-title">{t("payroll.rates.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">
            {rate ? t("payroll.rates.from", { date: formatPlainDate(rate.effectiveFrom, locale) }) : null}
          </p>
        </div>
      </div>
      {!rate ? (
        <p className="empty">{t("payroll.rates.none")}</p>
      ) : (
        <dl className="kv">
          <dt>{t("payroll.rates.pf")}</dt>
          <dd>
            {t("payroll.rates.pfValue", {
              employee: percent(rate.pfEmployeePercent),
              employer: percent(rate.pfEmployerPercent),
              ceiling: formatPaise(rate.pfWageCeilingPaise),
            })}
          </dd>
          <dt>{t("payroll.rates.eps")}</dt>
          <dd>
            {t("payroll.rates.epsValue", { percent: percent(rate.epsPercent), max: formatPaise(rate.epsMaxPaise) })}
          </dd>
          <dt>{t("payroll.rates.edli")}</dt>
          <dd>
            {t("payroll.rates.edliValue", {
              percent: percent(rate.edliPercent),
              max: formatPaise(rate.edliMaxPaise),
            })}
          </dd>
          <dt>{t("payroll.rates.admin")}</dt>
          <dd>
            {t("payroll.rates.adminValue", {
              percent: percent(rate.pfAdminPercent),
              min: formatPaise(rate.pfAdminMinPaise),
            })}
          </dd>
          <dt>{t("payroll.rates.esi")}</dt>
          <dd>
            {t("payroll.rates.esiValue", {
              employee: percent(rate.esiEmployeePercent),
              employer: percent(rate.esiEmployerPercent),
              ceiling: formatPaise(rate.esiWageCeilingPaise),
            })}
          </dd>
        </dl>
      )}
      {rate?.note ? <p className="mt-3 text-[12.5px] text-ink-3">{rate.note}</p> : null}
    </section>
  );
}

function TaxCard({ settings }: { settings: PayrollSettingsView }) {
  const { t } = useI18n();
  const st = settings.statutory;
  const rule: TaxRuleView | null = st.taxRule;
  return (
    <section className="card" aria-labelledby="payroll-tax-title" data-testid="payroll-tax">
      <div className="card-head">
        <div>
          <h2 id="payroll-tax-title">{t("payroll.tax.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">
            {rule ? t("payroll.tax.year", { year: rule.financialYear }) : null}
          </p>
        </div>
        <Pill tone="warn">{t("payroll.tax.verify")}</Pill>
      </div>
      {!rule ? (
        <p className="empty">{t("payroll.tax.none")}</p>
      ) : (
        <>
          {!st.taxRulesForThisYear ? (
            <div className="alert alert-warn mb-3" role="note">
              <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <span>{t("payroll.tax.earlierYear", { year: rule.financialYear, current: st.financialYear })}</span>
            </div>
          ) : null}
          <table className="table" data-testid="tax-slabs">
            <thead>
              <tr>
                <th scope="col">{t("payroll.tax.income")}</th>
                <th scope="col" className="r">
                  {t("payroll.tax.rate")}
                </th>
              </tr>
            </thead>
            <tbody>
              {rule.slabs.map((slab) => (
                <tr key={slab.fromPaise}>
                  <td className="num">
                    {slab.toPaise === null
                      ? t("payroll.tax.above", { from: formatPaise(slab.fromPaise) })
                      : t("payroll.tax.range", { from: formatPaise(slab.fromPaise), to: formatPaise(slab.toPaise) })}
                  </td>
                  <td className="r num">{percent(slab.ratePercent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <dl className="kv mt-3">
            <dt>{t("payroll.tax.standardDeduction")}</dt>
            <dd className="num">{formatPaise(rule.standardDeductionPaise)}</dd>
            <dt>{t("payroll.tax.rebate")}</dt>
            <dd>
              {t("payroll.tax.rebateValue", {
                max: formatPaise(rule.rebateMaxPaise),
                limit: formatPaise(rule.rebateIncomeLimitPaise),
              })}
            </dd>
            <dt>{t("payroll.tax.cess")}</dt>
            <dd className="num">{percent(rule.cessPercent)}</dd>
          </dl>
        </>
      )}
      <p className="mt-3 text-[12.5px] text-ink-3">{t("payroll.tax.newRegimeOnly")}</p>
    </section>
  );
}

function PtSlabsCard({ settings, canManage }: { settings: PayrollSettingsView; canManage: boolean }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const st = settings.statutory;
  const [state, setState] = useState(settings.ptState ?? st.ptStates[0] ?? "TG");
  const slabs = useApiData(`payroll:pt:${state}`, () => payrollApi.ptSlabs(state));
  const [editing, setEditing] = useState(false);
  const [resetting, setResetting] = useState(false);
  const states = [...new Set([...STATE_CODES, ...st.ptStates])].sort((a, b) =>
    stateName(t, a).localeCompare(stateName(t, b)),
  );
  const data = slabs.data && slabs.data.state === state ? slabs.data : undefined;
  return (
    <section className="card" aria-labelledby="payroll-pt-title" data-testid="payroll-pt">
      <div className="card-head">
        <div>
          <h2 id="payroll-pt-title">{t("payroll.pt.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("payroll.pt.sub")}</p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <label className="min-w-[180px]">
            <span className="sr-only">{t("payroll.settings.ptState")}</span>
            <select
              className="input"
              name="ptSlabState"
              value={state}
              aria-label={t("payroll.settings.ptState")}
              onChange={(e) => setState(e.target.value)}
            >
              {states.map((code) => (
                <option key={code} value={code}>
                  {stateName(t, code)}
                </option>
              ))}
            </select>
          </label>
          {canManage && data ? (
            <button type="button" className="btn" onClick={() => setEditing(true)}>
              {t("payroll.pt.edit")}
            </button>
          ) : null}
          {canManage && data?.custom ? (
            <button type="button" className="btn" onClick={() => setResetting(true)}>
              {t("payroll.pt.reset")}
            </button>
          ) : null}
        </div>
      </div>
      {slabs.error && !data ? (
        <ErrorState error={slabs.error} onRetry={slabs.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : data.slabs.length === 0 ? (
        <p className="empty">{t("payroll.pt.none", { state: stateName(t, state) })}</p>
      ) : (
        <>
          <p className="mb-2 flex flex-wrap items-center gap-2 text-[13px] text-ink-2">
            {data.custom ? <Pill tone="info">{t("payroll.pt.custom")}</Pill> : <Pill>{t("payroll.pt.published")}</Pill>}
            {data.effectiveFrom ? t("payroll.pt.from", { date: formatPlainDate(data.effectiveFrom, locale) }) : null}
          </p>
          <PtTable slabs={data.slabs} />
        </>
      )}
      {editing && data ? (
        <PtSlabsDialog
          view={data}
          onClose={() => setEditing(false)}
          onSaved={() => {
            setEditing(false);
            slabs.reload();
            toast(t("payroll.pt.saved", { state: stateName(t, state) }));
          }}
        />
      ) : null}
      <ConfirmDialog
        open={resetting}
        title={t("payroll.pt.resetTitle", { state: stateName(t, state) })}
        body={t("payroll.pt.resetBody")}
        confirmLabel={t("payroll.pt.reset")}
        onClose={() => setResetting(false)}
        onConfirm={async () => {
          await payrollApi.resetPtSlabs(state);
          setResetting(false);
          slabs.reload();
          toast(t("payroll.pt.resetDone", { state: stateName(t, state) }));
        }}
      />
    </section>
  );
}

const MONTH_KEYS = [
  "payroll.month.1",
  "payroll.month.2",
  "payroll.month.3",
  "payroll.month.4",
  "payroll.month.5",
  "payroll.month.6",
  "payroll.month.7",
  "payroll.month.8",
  "payroll.month.9",
  "payroll.month.10",
  "payroll.month.11",
  "payroll.month.12",
] as const;

function PtTable({ slabs }: { slabs: PtSlabView[] }) {
  const { t } = useI18n();
  return (
    <div className="table-wrap">
      <table className="table" data-testid="pt-slabs">
        <thead>
          <tr>
            <th scope="col">{t("payroll.pt.appliesTo")}</th>
            <th scope="col">{t("payroll.pt.gross")}</th>
            <th scope="col" className="r">
              {t("payroll.pt.amount")}
            </th>
            <th scope="col">{t("payroll.pt.months")}</th>
          </tr>
        </thead>
        <tbody>
          {slabs.map((slab, i) => (
            <tr key={i}>
              <td>{t(`payroll.appliesTo.${slab.appliesTo}`)}</td>
              <td className="num">
                {slab.toPaise === null
                  ? t("payroll.tax.above", { from: formatPaise(slab.fromPaise) })
                  : t("payroll.tax.range", { from: formatPaise(slab.fromPaise), to: formatPaise(slab.toPaise) })}
              </td>
              <td className="r num">{formatPaise(slab.amountPaise)}</td>
              <td>
                {slab.months.length === 0
                  ? t("payroll.pt.everyMonth")
                  : slab.months.map((m) => t(MONTH_KEYS[m - 1])).join(", ")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

type SlabRow = { appliesTo: PtAppliesTo; from: string; to: string; amount: string; months: string };

/** "2" or "2, 3" → [2, 3]; blank → [] (every month); null when not months 1 to 12. Exported for tests. */
export function parseMonths(text: string): number[] | null {
  const parts = text
    .split(/[,\s]+/)
    .map((p) => p.trim())
    .filter(Boolean);
  const months: number[] = [];
  for (const part of parts) {
    if (!/^\d{1,2}$/.test(part)) return null;
    const m = Number(part);
    if (m < 1 || m > 12) return null;
    if (!months.includes(m)) months.push(m);
  }
  return months.sort((a, b) => a - b);
}

/** The checks PUT /api/payroll/settings/pt-slabs/{state} makes. Exported for tests. */
export function validatePtSlabs(effectiveFrom: string, rows: SlabRow[]): Problems {
  const problems: Problems = {};
  if (!/^\d{4}-\d{2}-\d{2}$/.test(effectiveFrom)) problems.effectiveFrom = "validation.date";
  if (rows.length === 0) problems.effectiveFrom = "payroll.v.slabs";
  rows.forEach((row, i) => {
    const from = parseRupees(row.from);
    const to = row.to.trim() ? parseRupees(row.to) : null;
    const amount = parseRupees(row.amount);
    if (from === null) problems[`from-${i}`] = "payroll.v.rupees";
    if (row.to.trim() && to === null) problems[`to-${i}`] = "payroll.v.rupees";
    else if (from !== null && to !== null && to < from) problems[`to-${i}`] = "payroll.v.slabOrder";
    if (amount === null || amount > 10_000_00) problems[`amount-${i}`] = "payroll.v.ptAmount";
    if (parseMonths(row.months) === null) problems[`months-${i}`] = "payroll.v.months";
  });
  return problems;
}

function PtSlabsDialog({ view, onClose, onSaved }: { view: PtSlabsView; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<{ effectiveFrom: string; rows: SlabRow[] }>({
    effectiveFrom: todayInIndia().slice(0, 8) + "01",
    rows: view.slabs.map((s) => ({
      appliesTo: s.appliesTo,
      from: rupeesInput(s.fromPaise),
      to: s.toPaise === null ? "" : rupeesInput(s.toPaise),
      amount: rupeesInput(s.amountPaise),
      months: s.months.join(", "),
    })),
  });
  const { values, set, errors } = form;
  const setRow = (index: number, patch: Partial<SlabRow>) =>
    set(
      "rows",
      values.rows.map((row, i) => (i === index ? { ...row, ...patch } : row)),
    );
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validatePtSlabs(values.effectiveFrom, values.rows), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await payrollApi.savePtSlabs(view.state, {
        effectiveFrom: values.effectiveFrom,
        slabs: values.rows.map((row) => ({
          appliesTo: row.appliesTo,
          fromPaise: parseRupees(row.from) ?? 0,
          toPaise: row.to.trim() ? parseRupees(row.to) : null,
          amountPaise: parseRupees(row.amount) ?? 0,
          months: parseMonths(row.months) ?? [],
        })),
      });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("payroll.pt.editTitle", { state: stateName(t, view.state) })}
      description={t("payroll.pt.editDescription")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("payroll.pt.effectiveFrom")}
          name="effectiveFrom"
          type="date"
          value={values.effectiveFrom}
          onChange={(e) => set("effectiveFrom", e.target.value)}
          error={errors.effectiveFrom}
          required
          data-autofocus
        />
        <ol className="flex flex-col gap-3">
          {values.rows.map((row, i) => (
            <li key={i} className="pt-slab-row">
              <SelectField
                label={t("payroll.pt.appliesTo")}
                name={`appliesTo-${i}`}
                value={row.appliesTo}
                onChange={(e) => setRow(i, { appliesTo: e.target.value as PtAppliesTo })}
                options={(["ALL", "WOMEN", "OTHERS"] as const).map((a) => ({
                  value: a,
                  label: t(`payroll.appliesTo.${a}`),
                }))}
              />
              <TextField
                label={t("payroll.pt.fromRupees")}
                name={`from-${i}`}
                inputMode="decimal"
                value={row.from}
                onChange={(e) => setRow(i, { from: e.target.value })}
                error={errors[`from-${i}`]}
              />
              <TextField
                label={t("payroll.pt.toRupees")}
                name={`to-${i}`}
                inputMode="decimal"
                value={row.to}
                placeholder={t("payroll.pt.noLimit")}
                onChange={(e) => setRow(i, { to: e.target.value })}
                error={errors[`to-${i}`]}
              />
              <TextField
                label={t("payroll.pt.amount")}
                name={`amount-${i}`}
                inputMode="decimal"
                value={row.amount}
                onChange={(e) => setRow(i, { amount: e.target.value })}
                error={errors[`amount-${i}`]}
              />
              <TextField
                label={t("payroll.pt.months")}
                name={`months-${i}`}
                value={row.months}
                placeholder={t("payroll.pt.everyMonth")}
                hint={t("payroll.pt.monthsHint")}
                onChange={(e) => setRow(i, { months: e.target.value })}
                error={errors[`months-${i}`]}
              />
              <button
                type="button"
                className="iconbtn self-end"
                aria-label={t("payroll.pt.removeRow", { number: i + 1 })}
                onClick={() =>
                  set(
                    "rows",
                    values.rows.filter((_, j) => j !== i),
                  )
                }
              >
                <Trash2 size={18} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ol>
        <button
          type="button"
          className="btn btn-sm self-start"
          onClick={() =>
            set("rows", [...values.rows, { appliesTo: "ALL", from: "", to: "", amount: "", months: "" }])
          }
          disabled={values.rows.length >= 40}
        >
          <Plus size={16} aria-hidden="true" />
          {t("payroll.pt.addRow")}
        </button>
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
