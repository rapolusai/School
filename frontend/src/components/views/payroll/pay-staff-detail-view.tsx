"use client";

import { Eye, Pencil, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPaise, formatPlainDate, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import {
  blankToNull,
  MONTH_PATTERN,
  structureTotals,
  validateProfile,
  type ProfileValues,
} from "@/lib/payroll";
import { payrollApi } from "@/lib/payroll-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type {
  PayProfileFull,
  PayStaffDetail,
  SalaryComponentView,
  StructureView,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { BackLink, PayrollNav, RunStatusPill, useMonthLabel, yesNo } from "./payroll-ui";

type Open = "profile" | "structure" | { deleteStructure: StructureView } | null;

/** /app/payroll/staff/[id]: one person's pay details (masked), salary structures with history, and payslips. */
export function PayStaffDetailView({ userId }: { userId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { me } = useAuth();
  const { toast } = useToast();
  const monthLabel = useMonthLabel();
  const canManage = hasPermission(me, PERMISSIONS.payrollManage);
  const person = useApiData(`payroll:person:${userId}`, () => payrollApi.person(userId));
  const [open, setOpen] = useState<Open>(null);
  const p = person.data;
  const back = <BackLink href="/app/payroll/staff" label={t("payroll.staff.back")} />;

  if (person.error && !p) {
    return (
      <>
        {back}
        <section className="card">
          {person.error.status === 404 ? (
            <p className="empty">{t("payroll.staff.notFound")}</p>
          ) : (
            <ErrorState error={person.error} onRetry={person.reload} />
          )}
        </section>
      </>
    );
  }
  if (!p) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  const current = p.structures.find((s) => s.current) ?? null;
  return (
    <>
      {back}
      <PageHead
        eyebrow={t("payroll.eyebrow")}
        title={p.name}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setOpen("structure")}>
              <Plus size={18} aria-hidden="true" />
              {p.structures.length > 0 ? t("payroll.structure.revise") : t("payroll.structure.add")}
            </button>
          ) : null
        }
      />
      <PayrollNav />
      <p className="-mt-2 text-ink-2">
        {[p.employeeCode, p.designation, p.departmentName, servicePeriod(p, locale)].filter(Boolean).join(" · ")}
        {p.active ? null : (
          <>
            {" "}
            <Pill>{t("payroll.staff.inactive")}</Pill>
          </>
        )}
      </p>
      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2">
        <StructureCard person={p} current={current} monthLabel={monthLabel} />
        <PayDetailsCard person={p} canManage={canManage} onEdit={() => setOpen("profile")} />
      </div>
      <HistoryCard
        person={p}
        canManage={canManage}
        monthLabel={monthLabel}
        onDelete={(structure) => setOpen({ deleteStructure: structure })}
      />
      <PayslipsList person={p} monthLabel={monthLabel} />

      {open === "profile" ? (
        <ProfileDialog
          person={p}
          onClose={() => setOpen(null)}
          onSaved={() => {
            setOpen(null);
            person.reload();
            toast(t("payroll.profile.saved", { name: p.name }));
          }}
        />
      ) : null}
      {open === "structure" ? (
        <StructureDialog
          person={p}
          current={current}
          onClose={() => setOpen(null)}
          onSaved={(saved) => {
            setOpen(null);
            person.reload();
            toast(t("payroll.structure.saved", { month: monthLabel(saved.effectiveFrom) }));
          }}
        />
      ) : null}
      <ConfirmDialog
        open={typeof open === "object" && open !== null}
        title={t("payroll.structure.deleteTitle")}
        body={
          typeof open === "object" && open !== null
            ? t("payroll.structure.deleteBody", { month: monthLabel(open.deleteStructure.effectiveFrom) })
            : ""
        }
        confirmLabel={t("common.delete")}
        onClose={() => setOpen(null)}
        onConfirm={async () => {
          if (typeof open !== "object" || open === null) return;
          await payrollApi.deleteStructure(open.deleteStructure.id);
          setOpen(null);
          person.reload();
          toast(t("payroll.structure.deleted"));
        }}
      />
    </>
  );
}

function StructureLines({ structure }: { structure: StructureView }) {
  const { t } = useI18n();
  const earnings = structure.lines.filter((l) => l.kind === "EARNING");
  const deductions = structure.lines.filter((l) => l.kind === "DEDUCTION");
  return (
    <dl className="kv" data-testid="structure-lines">
      {earnings.map((line) => (
        <div key={line.componentId} className="contents">
          <dt>{line.name}</dt>
          <dd className="num">{formatPaise(line.monthlyPaise)}</dd>
        </div>
      ))}
      <dt className="font-semibold text-ink">{t("payroll.structure.gross")}</dt>
      <dd className="num font-bold">{formatPaise(structure.grossPaise)}</dd>
      {deductions.map((line) => (
        <div key={line.componentId} className="contents">
          <dt>{line.name}</dt>
          <dd className="num">−{formatPaise(line.monthlyPaise)}</dd>
        </div>
      ))}
      <dt>{t("payroll.structure.employer")}</dt>
      <dd className="num">{formatPaise(structure.employerPaise)}</dd>
      <dt className="font-semibold text-ink">{t("payroll.structure.ctc")}</dt>
      <dd className="num font-bold" data-testid="structure-ctc">
        {formatPaise(structure.ctcPaise)}
        <span className="block text-[12.5px] font-normal text-ink-3">
          {t("payroll.structure.ctcYear", { amount: formatPaise(structure.ctcPaise * 12) })}
        </span>
      </dd>
    </dl>
  );
}

function StructureCard({
  person,
  current,
  monthLabel,
}: {
  person: PayStaffDetail;
  current: StructureView | null;
  monthLabel: (month: string) => string;
}) {
  const { t } = useI18n();
  const upcoming = person.structures.filter((s) => !s.current && s.effectiveFrom > (current?.effectiveFrom ?? ""));
  const next = upcoming.length > 0 ? upcoming[upcoming.length - 1] : null;
  return (
    <section className="card" aria-labelledby="structure-title" data-testid="structure-card">
      <div className="card-head">
        <div>
          <h2 id="structure-title">{t("payroll.structure.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">
            {current ? t("payroll.structure.from", { month: monthLabel(current.effectiveFrom) }) : null}
          </p>
        </div>
      </div>
      {current ? (
        <StructureLines structure={current} />
      ) : (
        <p className="empty" data-testid="structure-none">
          {t("payroll.structure.none")}
        </p>
      )}
      {next ? (
        <p className="mt-3 text-[13px] text-ink-2">
          {t("payroll.structure.upcoming", { month: monthLabel(next.effectiveFrom), gross: formatPaise(next.grossPaise) })}
        </p>
      ) : null}
      <p className="mt-3 text-[12.5px] text-ink-3">{t("payroll.structure.ctcNote")}</p>
    </section>
  );
}

function PayDetailsCard({
  person,
  canManage,
  onEdit,
}: {
  person: PayStaffDetail;
  canManage: boolean;
  onEdit: () => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [full, setFull] = useState<PayProfileFull | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const profile = person.payProfile;

  const reveal = async () => {
    if (full) {
      setFull(null);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      setFull(await payrollApi.fullProfile(person.userId));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="card" aria-labelledby="pay-details-title" data-testid="pay-details">
      <div className="card-head">
        <div>
          <h2 id="pay-details-title">{t("payroll.profile.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("payroll.profile.sub")}</p>
        </div>
        {canManage ? (
          <div className="flex flex-wrap gap-2">
            {profile.saved ? (
              <button type="button" className="btn btn-sm" onClick={reveal} disabled={loading}>
                <Eye size={16} aria-hidden="true" />
                {loading ? t("common.working") : full ? t("payroll.profile.hide") : t("payroll.profile.show")}
              </button>
            ) : null}
            <button type="button" className="btn btn-sm" onClick={onEdit}>
              <Pencil size={16} aria-hidden="true" />
              {t("common.edit")}
            </button>
          </div>
        ) : null}
      </div>
      <FormAlert message={error} />
      {!profile.saved ? (
        <p className="empty" data-testid="pay-details-none">
          {t("payroll.profile.none")}
        </p>
      ) : (
        <dl className="kv">
          <dt>{t("payroll.profile.account")}</dt>
          <dd className="mono" data-testid="pay-account">
            {full ? (full.bankAccountNumber ?? "—") : (profile.bankAccountMasked ?? t("payroll.staff.missing"))}
          </dd>
          <dt>{t("payroll.profile.ifsc")}</dt>
          <dd className="mono">{profile.bankIfsc ?? "—"}</dd>
          <dt>{t("payroll.profile.holder")}</dt>
          <dd>{profile.accountHolderName ?? person.name}</dd>
          <dt>{t("payroll.profile.pan")}</dt>
          <dd className="mono" data-testid="pay-pan">
            {full ? (full.pan ?? "—") : (profile.panMasked ?? "—")}
          </dd>
          <dt>{t("payroll.profile.uan")}</dt>
          <dd className="mono">{profile.uan ?? "—"}</dd>
          <dt>{t("payroll.profile.esi")}</dt>
          <dd className="mono">{profile.esiNumber ?? "—"}</dd>
          <dt>{t("payroll.profile.pfMember")}</dt>
          <dd>{yesNo(t, profile.pfMember)}</dd>
          <dt>{t("payroll.profile.ptWomen")}</dt>
          <dd>{yesNo(t, profile.ptWomenSlab)}</dd>
          {profile.updatedAt ? (
            <>
              <dt>{t("payroll.profile.updated")}</dt>
              <dd>{formatDateTime(profile.updatedAt, locale)}</dd>
            </>
          ) : null}
        </dl>
      )}
      {full ? <p className="mt-3 text-[12.5px] text-ink-3">{t("payroll.profile.audited")}</p> : null}
      <p className="mt-3 text-[12.5px] text-ink-3">{t("payroll.profile.noAadhaar")}</p>
    </section>
  );
}

function HistoryCard({
  person,
  canManage,
  monthLabel,
  onDelete,
}: {
  person: PayStaffDetail;
  canManage: boolean;
  monthLabel: (month: string) => string;
  onDelete: (structure: StructureView) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  if (person.structures.length === 0) return null;
  return (
    <section className="card" aria-labelledby="history-title" data-testid="structure-history">
      <div className="card-head">
        <div>
          <h2 id="history-title">{t("payroll.structure.history")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("payroll.structure.historySub")}</p>
        </div>
      </div>
      <ul className="list">
        {person.structures.map((s) => (
          <li key={s.id} className="li items-center">
            <div className="min-w-0 flex-1">
              <p className="flex flex-wrap items-center gap-2 font-semibold">
                {t("payroll.structure.from", { month: monthLabel(s.effectiveFrom) })}
                {s.current ? <Pill tone="good">{t("payroll.structure.current")}</Pill> : null}
                {s.locked ? <Pill>{t("payroll.structure.locked")}</Pill> : null}
              </p>
              <p className="text-[13px] text-ink-2">
                {t("payroll.structure.summary", { gross: formatPaise(s.grossPaise), ctc: formatPaise(s.ctcPaise) })}
              </p>
              {s.note ? <p className="text-[13px] text-ink-2">{s.note}</p> : null}
              <p className="text-[12.5px] text-ink-3">
                {t("payroll.structure.by", {
                  name: s.createdByName ?? t("common.system"),
                  date: formatDateTime(s.createdAt, locale),
                })}
              </p>
            </div>
            {canManage && !s.locked ? (
              <button
                type="button"
                className="iconbtn"
                aria-label={t("payroll.structure.deleteFor", { month: monthLabel(s.effectiveFrom) })}
                onClick={() => onDelete(s)}
              >
                <Trash2 size={18} aria-hidden="true" />
              </button>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

function PayslipsList({ person, monthLabel }: { person: PayStaffDetail; monthLabel: (month: string) => string }) {
  const { t } = useI18n();
  return (
    <section className="card" aria-labelledby="person-payslips-title">
      <div className="card-head">
        <h2 id="person-payslips-title">{t("payroll.staff.payslips")}</h2>
      </div>
      {person.payslips.length === 0 ? (
        <p className="empty">{t("payroll.staff.noPayslips")}</p>
      ) : (
        <ul className="rowcards">
          {person.payslips.map((slip) => (
            <li key={slip.id}>
              <Link href={`/app/payroll/payslips/${slip.id}`} className="rowcard">
                <span className="min-w-0 flex-1">
                  <b className="block truncate font-semibold">{monthLabel(slip.month)}</b>
                  <span className="block truncate text-[12.5px] text-ink-3">
                    {t("payslip.mine.gross", { amount: formatPaise(slip.grossPaise) })}
                  </span>
                </span>
                <span className="flex flex-col items-end gap-1">
                  <span className="num font-semibold">{formatPaise(slip.netPaise)}</span>
                  <RunStatusPill status={slip.status} />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/* ------------------------------------------------------------------ dialogs */

const EMPTY_PROFILE: ProfileValues = {
  bankAccountNumber: "",
  bankIfsc: "",
  accountHolderName: "",
  pan: "",
  uan: "",
  esiNumber: "",
  pfMember: true,
  ptWomenSlab: false,
};

function ProfileDialog({
  person,
  onClose,
  onSaved,
}: {
  person: PayStaffDetail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  // Editing needs the values in full, so opening the form is a recorded view (only once there is something saved).
  const full = useApiData(person.payProfile.saved ? `payroll:full:${person.userId}` : null, () =>
    payrollApi.fullProfile(person.userId),
  );
  const ready = !person.payProfile.saved || full.data !== undefined;
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("payroll.profile.edit", { name: person.name })}
      description={person.payProfile.saved ? t("payroll.profile.editAudited") : t("payroll.profile.editNew")}
      closeLabel={t("common.close")}
    >
      {full.error && !full.data ? (
        <ErrorState error={full.error} onRetry={full.reload} />
      ) : !ready ? (
        <LoadingRows rows={4} />
      ) : (
        <ProfileForm
          person={person}
          initial={
            full.data
              ? {
                  bankAccountNumber: full.data.bankAccountNumber ?? "",
                  bankIfsc: full.data.bankIfsc ?? "",
                  accountHolderName: full.data.accountHolderName ?? "",
                  pan: full.data.pan ?? "",
                  uan: full.data.uan ?? "",
                  esiNumber: full.data.esiNumber ?? "",
                  pfMember: full.data.pfMember,
                  ptWomenSlab: full.data.ptWomenSlab,
                }
              : EMPTY_PROFILE
          }
          onClose={onClose}
          onSaved={onSaved}
        />
      )}
    </Dialog>
  );
}

function ProfileForm({
  person,
  initial,
  onClose,
  onSaved,
}: {
  person: PayStaffDetail;
  initial: ProfileValues;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const form = useForm<ProfileValues>(initial);
  const { values, set, errors } = form;
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateProfile(values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await payrollApi.saveProfile(person.userId, {
        bankAccountNumber: blankToNull(values.bankAccountNumber),
        bankIfsc: blankToNull(values.bankIfsc, true),
        accountHolderName: blankToNull(values.accountHolderName),
        pan: blankToNull(values.pan, true),
        uan: blankToNull(values.uan),
        esiNumber: blankToNull(values.esiNumber),
        pfMember: values.pfMember,
        ptWomenSlab: values.ptWomenSlab,
      });
    });
    if (ok) onSaved();
  };
  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate autoComplete="off">
      <FormAlert message={form.formError} />
      <div className="grid2">
        <TextField
          label={t("payroll.profile.account")}
          name="bankAccountNumber"
          inputMode="numeric"
          value={values.bankAccountNumber}
          maxLength={18}
          onChange={(e) => set("bankAccountNumber", e.target.value.replace(/\s/g, ""))}
          error={errors.bankAccountNumber}
          data-autofocus
        />
        <TextField
          label={t("payroll.profile.ifsc")}
          name="bankIfsc"
          value={values.bankIfsc}
          maxLength={11}
          placeholder="SBIN0001234"
          onChange={(e) => set("bankIfsc", e.target.value.toUpperCase())}
          error={errors.bankIfsc}
        />
      </div>
      <TextField
        label={t("payroll.profile.holder")}
        name="accountHolderName"
        value={values.accountHolderName}
        maxLength={200}
        hint={t("payroll.profile.holderHint")}
        onChange={(e) => set("accountHolderName", e.target.value)}
        error={errors.accountHolderName}
      />
      <div className="grid2">
        <TextField
          label={t("payroll.profile.pan")}
          name="pan"
          value={values.pan}
          maxLength={10}
          placeholder="ABCDE1234F"
          onChange={(e) => set("pan", e.target.value.toUpperCase())}
          error={errors.pan}
        />
        <TextField
          label={t("payroll.profile.uan")}
          name="uan"
          inputMode="numeric"
          value={values.uan}
          maxLength={12}
          onChange={(e) => set("uan", e.target.value.trim())}
          error={errors.uan}
        />
      </div>
      <TextField
        label={t("payroll.profile.esi")}
        name="esiNumber"
        inputMode="numeric"
        value={values.esiNumber}
        maxLength={10}
        onChange={(e) => set("esiNumber", e.target.value.trim())}
        error={errors.esiNumber}
      />
      <label className="check">
        <input
          type="checkbox"
          name="pfMember"
          checked={values.pfMember}
          onChange={(e) => set("pfMember", e.target.checked)}
        />
        <span>{t("payroll.profile.pfMemberLabel")}</span>
      </label>
      <label className="check">
        <input
          type="checkbox"
          name="ptWomenSlab"
          checked={values.ptWomenSlab}
          onChange={(e) => set("ptWomenSlab", e.target.checked)}
        />
        <span>{t("payroll.profile.ptWomenLabel")}</span>
      </label>
      <p className="text-[12.5px] text-ink-3">{t("payroll.profile.noAadhaar")}</p>
      <div className="flex justify-end gap-2 pt-1">
        <button type="button" className="btn" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button type="submit" className="btn btn-primary" disabled={form.submitting}>
          {form.submitting ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

/** The month a new structure most likely starts: this month, or the one after the latest structure. */
export function suggestedStructureMonth(structures: StructureView[], today: string): string {
  const thisMonth = today.slice(0, 7);
  const latest = structures.reduce<string | null>((max, s) => (max === null || s.effectiveFrom > max ? s.effectiveFrom : max), null);
  if (!latest || latest < thisMonth) return thisMonth;
  const year = Number(latest.slice(0, 4));
  const month = Number(latest.slice(5, 7));
  return month === 12 ? `${year + 1}-01` : `${year}-${String(month + 1).padStart(2, "0")}`;
}

type StructureValues = { effectiveFrom: string; note: string; amounts: Record<string, string> };

/** The checks POST /api/payroll/staff/{id}/structures makes. Exported for tests. */
export function validateStructure(
  values: StructureValues,
  components: Pick<SalaryComponentView, "id" | "kind">[],
): Problems {
  const problems: Problems = {};
  if (!MONTH_PATTERN.test(values.effectiveFrom)) problems.effectiveFrom = "payroll.v.month";
  if (values.note.trim().length > 500) problems.note = "validation.tooLong";
  let earning = false;
  for (const c of components) {
    const text = values.amounts[c.id] ?? "";
    if (!text.trim()) continue;
    const paise = parseRupees(text);
    if (paise === null || paise <= 0) problems[`amount-${c.id}`] = "payroll.v.amount";
    else if (c.kind === "EARNING") earning = true;
  }
  if (!earning && !Object.keys(problems).some((k) => k.startsWith("amount-"))) {
    const first = components.find((c) => c.kind === "EARNING");
    if (first) problems[`amount-${first.id}`] = "payroll.v.needEarning";
  }
  return problems;
}

function StructureDialog({
  person,
  current,
  onClose,
  onSaved,
}: {
  person: PayStaffDetail;
  current: StructureView | null;
  onClose: () => void;
  onSaved: (saved: StructureView) => void;
}) {
  const { t } = useI18n();
  const components = useApiData("payroll:components", payrollApi.components);
  const active = (components.data ?? []).filter((c) => c.active);
  return (
    <Dialog
      open
      onClose={onClose}
      title={current ? t("payroll.structure.reviseFor", { name: person.name }) : t("payroll.structure.addFor", { name: person.name })}
      description={t("payroll.structure.dialog")}
      closeLabel={t("common.close")}
    >
      {components.error && !components.data ? (
        <ErrorState error={components.error} onRetry={components.reload} />
      ) : !components.data ? (
        <LoadingRows rows={5} />
      ) : active.length === 0 ? (
        <p className="empty">
          {t("payroll.structure.noComponents")}{" "}
          <Link href="/app/payroll/components" className="link font-semibold">
            {t("payroll.nav.components")}
          </Link>
        </p>
      ) : (
        <StructureForm person={person} current={current} components={active} onClose={onClose} onSaved={onSaved} />
      )}
    </Dialog>
  );
}

function StructureForm({
  person,
  current,
  components,
  onClose,
  onSaved,
}: {
  person: PayStaffDetail;
  current: StructureView | null;
  components: SalaryComponentView[];
  onClose: () => void;
  onSaved: (saved: StructureView) => void;
}) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const form = useForm<StructureValues>({
    effectiveFrom: suggestedStructureMonth(person.structures, todayInIndia()),
    note: "",
    amounts: Object.fromEntries(
      components.map((c) => {
        const line = current?.lines.find((l) => l.componentId === c.id);
        return [c.id, line ? rupeesInput(line.monthlyPaise) : ""];
      }),
    ),
  });
  const { values, set, errors } = form;
  const totals = structureTotals(
    components.map((c) => ({ kind: c.kind, amount: values.amounts[c.id] ?? "" })),
    parseRupees,
  );
  const setAmount = (id: string, amount: string) => {
    set("amounts", { ...values.amounts, [id]: amount });
    form.setErrors((prev) => (prev[`amount-${id}`] ? { ...prev, [`amount-${id}`]: undefined } : prev));
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateStructure(values, components), t, event.currentTarget)) return;
    const lines = components
      .filter((c) => (values.amounts[c.id] ?? "").trim())
      .map((c) => ({ componentId: c.id, monthlyPaise: parseRupees(values.amounts[c.id]) ?? 0 }));
    let saved: StructureView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await payrollApi.createStructure(person.userId, {
        effectiveFrom: values.effectiveFrom,
        note: values.note.trim() || null,
        lines,
      });
    });
    if (ok && saved) onSaved(saved);
  };
  const section = (kind: "EARNING" | "DEDUCTION") => (
    <fieldset className="flex flex-col gap-2.5">
      <legend className="field-label mb-1.5">
        {kind === "EARNING" ? t("payroll.structure.earnings") : t("payroll.structure.deductions")}
      </legend>
      {components
        .filter((c) => c.kind === kind)
        .map((c) => (
          <TextField
            key={c.id}
            label={c.name}
            name={`amount-${c.id}`}
            inputMode="decimal"
            value={values.amounts[c.id] ?? ""}
            onChange={(e) => setAmount(c.id, e.target.value)}
            error={errors[`amount-${c.id}`]}
            className="payroll-amount-field"
          />
        ))}
    </fieldset>
  );
  const serverLineErrors = Object.entries(errors).filter(([key, value]) => key.startsWith("lines") && value);
  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
      <FormAlert message={form.formError} />
      {serverLineErrors.length > 0 ? <FormAlert message={serverLineErrors.map(([, v]) => v).join(" ")} /> : null}
      <TextField
        label={t("payroll.structure.effectiveFrom")}
        name="effectiveFrom"
        type="month"
        value={values.effectiveFrom}
        hint={MONTH_PATTERN.test(values.effectiveFrom) ? monthLabel(values.effectiveFrom) : t("payroll.runs.monthHint")}
        onChange={(e) => set("effectiveFrom", e.target.value)}
        error={errors.effectiveFrom}
        required
        data-autofocus
      />
      <div className="grid2">
        {section("EARNING")}
        {components.some((c) => c.kind === "DEDUCTION") ? section("DEDUCTION") : null}
      </div>
      <div className="amounts payroll-structure-totals" aria-live="polite">
        <div className="amount">
          <span>{t("payroll.structure.gross")}</span>
          <b className="num" data-testid="structure-gross">
            {formatPaise(totals.grossPaise)}
          </b>
        </div>
        <div className="amount">
          <span>{t("payroll.structure.fixedDeductions")}</span>
          <b className="num">{formatPaise(totals.deductionsPaise)}</b>
        </div>
      </div>
      <p className="text-[12.5px] text-ink-3">{t("payroll.structure.statutoryNote")}</p>
      <TextAreaField
        label={t("payroll.structure.note")}
        name="note"
        value={values.note}
        maxLength={500}
        placeholder={t("payroll.structure.notePlaceholder")}
        onChange={(e) => set("note", e.target.value)}
        error={errors.note}
      />
      <div className="flex justify-end gap-2 pt-1">
        <button type="button" className="btn" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button type="submit" className="btn btn-primary" disabled={form.submitting}>
          {form.submitting ? t("common.saving") : t("payroll.structure.save")}
        </button>
      </div>
    </form>
  );
}

/** Joined and left dates for the staff page header. Exported for tests. */
export function servicePeriod(person: Pick<PayStaffDetail, "dateOfJoining" | "dateOfLeaving">, locale: string): string {
  return [person.dateOfJoining, person.dateOfLeaving].map((d) => (d ? formatPlainDate(d, locale) : "")).filter(Boolean).join(" – ");
}
