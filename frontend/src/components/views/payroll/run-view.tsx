"use client";

import {
  BadgeCheck,
  Banknote,
  Download,
  FileText,
  Lock,
  Pencil,
  Plus,
  Printer,
  RefreshCw,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPaise, formatPlainDate, parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import {
  adjustmentIsMoney,
  formatPayDays,
  groupWarnings,
  parseLopDays,
  runActions,
  validateAdjustment,
  warningKey,
  type AdjustmentValues,
  type RunActions,
} from "@/lib/payroll";
import { downloadPayrollFile, payrollApi, payrollPaths } from "@/lib/payroll-api";
import { ADJUSTMENT_KINDS, type AdjustmentKind, type PayslipRow, type RunDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { BackLink, PayrollNav, RunStatusPill, useMonthLabel } from "./payroll-ui";

type Open =
  | { kind: "approve" }
  | { kind: "delete" }
  | { kind: "paid" }
  | { kind: "adjust" }
  | { kind: "tds"; slip: PayslipRow }
  | { kind: "removeAdjustment"; id: string; name: string }
  | null;

/**
 * /app/payroll/runs/[id]: a month's payroll for review. A draft can be recalculated, adjusted, approved (which locks
 * it) or deleted; an approved month can be marked paid and gives the bank, PF, ESI, PT and TDS files.
 */
export function PayrollRunView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const router = useRouter();
  const locale = localeFor(lang);
  const monthLabel = useMonthLabel();
  const run = useApiData(`payroll:run:${id}`, () => payrollApi.run(id));
  const [current, setCurrent] = useState<RunDetail | null>(null);
  const [open, setOpen] = useState<Open>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  // The latest figures: an action's answer until a load brings the same version or a newer one.
  const r = current && run.data && current.id === run.data.id && current.version > run.data.version ? current : run.data;

  const back = <BackLink href="/app/payroll" label={t("payroll.run.back")} />;

  if (run.error && !r) {
    return (
      <>
        {back}
        <section className="card">
          {run.error.status === 404 ? (
            <p className="empty" data-testid="run-not-found">
              {t("payroll.run.notFound")}
            </p>
          ) : (
            <ErrorState error={run.error} onRetry={run.reload} />
          )}
        </section>
      </>
    );
  }
  if (!r) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  const can = runActions(r, me);
  const updated = (next: RunDetail, message?: string) => {
    setCurrent(next);
    setOpen(null);
    run.reload();
    if (message) toast(message);
  };
  const recalculate = async () => {
    setBusy(true);
    setActionError(null);
    try {
      updated(await payrollApi.recalculate(r.id), t("payroll.run.recalculated"));
    } catch (caught) {
      setActionError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {back}
      <PageHead
        eyebrow={t("payroll.eyebrow")}
        title={t("payroll.run.title", { month: monthLabel(r.month) })}
        actions={
          <>
            <Link href={`/app/payroll/runs/${r.id}/payslips`} className="btn">
              <Printer size={18} aria-hidden="true" />
              {t("payroll.run.printPayslips")}
            </Link>
            {can.recalculate ? (
              <button type="button" className="btn" onClick={recalculate} disabled={busy}>
                <RefreshCw size={18} aria-hidden="true" />
                {busy ? t("common.working") : t("payroll.run.recalculate")}
              </button>
            ) : null}
            {can.approve ? (
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => setOpen({ kind: "approve" })}
                data-testid="approve-run"
              >
                <BadgeCheck size={18} aria-hidden="true" />
                {t("payroll.run.approve")}
              </button>
            ) : null}
            {can.markPaid ? (
              <button type="button" className="btn btn-primary" onClick={() => setOpen({ kind: "paid" })}>
                <Banknote size={18} aria-hidden="true" />
                {t("payroll.run.markPaid")}
              </button>
            ) : null}
            {can.remove ? (
              <button type="button" className="btn" onClick={() => setOpen({ kind: "delete" })}>
                <Trash2 size={18} aria-hidden="true" />
                {t("payroll.run.delete")}
              </button>
            ) : null}
          </>
        }
      />
      <PayrollNav />
      <FormAlert message={actionError} />

      <RunSummary run={r} locale={locale} />
      <RunWarnings run={r} />
      <PayslipsCard run={r} can={can} onTds={(slip) => setOpen({ kind: "tds", slip })} />
      <AdjustmentsCard
        run={r}
        can={can}
        locale={locale}
        onAdd={() => setOpen({ kind: "adjust" })}
        onRemove={(adjustmentId, name) => setOpen({ kind: "removeAdjustment", id: adjustmentId, name })}
      />
      {can.files ? <FilesCard run={r} /> : null}

      <ConfirmDialog
        open={open?.kind === "approve"}
        title={t("payroll.approve.title", { month: monthLabel(r.month) })}
        body={t("payroll.approve.body", {
          count: r.totals.staffCount,
          net: formatPaise(r.totals.netPaise),
        })}
        confirmLabel={t("payroll.approve.confirm")}
        danger={false}
        onClose={() => setOpen(null)}
        onConfirm={async () => {
          try {
            updated(await payrollApi.approve(r.id), t("payroll.approve.done", { month: monthLabel(r.month) }));
          } catch (caught) {
            run.reload();
            throw caught;
          }
        }}
      />
      <ConfirmDialog
        open={open?.kind === "delete"}
        title={t("payroll.delete.title", { month: monthLabel(r.month) })}
        body={t("payroll.delete.body")}
        confirmLabel={t("payroll.run.delete")}
        onClose={() => setOpen(null)}
        onConfirm={async () => {
          await payrollApi.deleteRun(r.id);
          setOpen(null);
          toast(t("payroll.delete.done", { month: monthLabel(r.month) }));
          router.push("/app/payroll");
        }}
      />
      <ConfirmDialog
        open={open?.kind === "removeAdjustment"}
        title={t("payroll.adjustment.removeTitle")}
        body={t("payroll.adjustment.removeBody", { name: open?.kind === "removeAdjustment" ? open.name : "" })}
        confirmLabel={t("common.remove")}
        onClose={() => setOpen(null)}
        onConfirm={async () => {
          if (open?.kind !== "removeAdjustment") return;
          updated(await payrollApi.removeAdjustment(r.id, open.id), t("payroll.adjustment.removed"));
        }}
      />
      {open?.kind === "paid" ? (
        <PaidDialog run={r} onClose={() => setOpen(null)} onDone={(next) => updated(next, t("payroll.paid.done"))} />
      ) : null}
      {open?.kind === "adjust" ? (
        <AdjustmentDialog
          run={r}
          onClose={() => setOpen(null)}
          onDone={(next) => updated(next, t("payroll.adjustment.added"))}
        />
      ) : null}
      {open?.kind === "tds" ? (
        <TdsDialog
          run={r}
          slip={open.slip}
          onClose={() => setOpen(null)}
          onDone={(next, message) => updated(next, message)}
        />
      ) : null}
    </>
  );
}

function Total({ label, value, testId, strong }: { label: string; value: string; testId?: string; strong?: boolean }) {
  return (
    <div className="amount" data-testid={testId}>
      <span>{label}</span>
      <b className={`num${strong ? " text-accent" : ""}`}>{value}</b>
    </div>
  );
}

function RunSummary({ run, locale }: { run: RunDetail; locale: string }) {
  const { t } = useI18n();
  const totals = run.totals;
  const basis =
    run.prorationBasis === "WORKING_DAYS"
      ? t("payroll.run.basisWorking", { days: run.workingDays })
      : t("payroll.run.basisCalendar", { days: run.daysInMonth });
  return (
    <section className="card flex flex-col gap-3.5" aria-labelledby="run-summary-title" data-testid="run-summary">
      <div className="card-head mb-0">
        <div className="min-w-0">
          <h2 id="run-summary-title" className="flex flex-wrap items-center gap-2">
            {t("payroll.run.summary")}
            <span data-testid="run-status">
              <RunStatusPill status={run.status} />
            </span>
          </h2>
          <p className="mt-1 text-sm text-ink-2">
            {[
              basis,
              t("payroll.run.calculatedAt", { date: formatDateTime(run.calculatedAt, locale) }),
              run.taxFinancialYear ? t("payroll.run.taxYear", { year: run.taxFinancialYear }) : null,
            ]
              .filter(Boolean)
              .join(" · ")}
          </p>
        </div>
      </div>
      <div className="amounts payroll-amounts" data-testid="run-totals">
        <Total label={t("payroll.col.staff")} value={String(totals.staffCount)} />
        <Total label={t("payroll.col.gross")} value={formatPaise(totals.grossPaise)} />
        <Total label={t("payroll.col.deductions")} value={formatPaise(totals.deductionsPaise)} />
        <Total label={t("payroll.col.net")} value={formatPaise(totals.netPaise)} testId="run-net" strong />
        <Total label={t("payroll.col.employer")} value={formatPaise(totals.employerPaise)} />
      </div>
      <dl className="payroll-statutory" data-testid="run-statutory">
        <div>
          <dt>{t("payroll.run.pf")}</dt>
          <dd className="num">
            {t("payroll.run.pfSplit", {
              employee: formatPaise(totals.pfEmployeePaise),
              employer: formatPaise(totals.pfEmployerPaise),
            })}
          </dd>
        </div>
        <div>
          <dt>{t("payroll.run.pfCharges")}</dt>
          <dd className="num">
            {t("payroll.run.pfChargesSplit", {
              edli: formatPaise(totals.edliPaise),
              admin: formatPaise(totals.pfAdminPaise),
            })}
          </dd>
        </div>
        <div>
          <dt>{t("payroll.run.esi")}</dt>
          <dd className="num">
            {t("payroll.run.pfSplit", {
              employee: formatPaise(totals.esiEmployeePaise),
              employer: formatPaise(totals.esiEmployerPaise),
            })}
          </dd>
        </div>
        <div>
          <dt>{t("payroll.run.pt")}</dt>
          <dd className="num">{formatPaise(totals.ptPaise)}</dd>
        </div>
        <div>
          <dt>{t("payroll.run.tds")}</dt>
          <dd className="num">{formatPaise(totals.tdsPaise)}</dd>
        </div>
      </dl>
      {run.status === "DRAFT" ? (
        <p className="text-[13px] text-ink-3">{t("payroll.run.draftNote")}</p>
      ) : (
        <p className="flex flex-wrap items-center gap-1.5 text-[13.5px] text-ink-2" data-testid="run-locked">
          <Lock size={15} aria-hidden="true" />
          {[
            t("payroll.run.approvedBy", {
              name: run.approvedByName ?? t("common.system"),
              date: formatDateTime(run.approvedAt, locale),
            }),
            run.paidOn
              ? t("payroll.run.paidOn", {
                  date: formatPlainDate(run.paidOn, locale),
                  name: run.paidByName ?? t("common.system"),
                })
              : null,
            run.paymentReference ? t("payroll.run.reference", { reference: run.paymentReference }) : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        </p>
      )}
    </section>
  );
}

function RunWarnings({ run }: { run: RunDetail }) {
  const { t } = useI18n();
  const groups = groupWarnings(run.warnings);
  if (groups.length === 0) return null;
  return (
    <section className="card" aria-labelledby="run-warnings-title" data-testid="run-warnings">
      <div className="card-head">
        <h2 id="run-warnings-title">{t("payroll.warnings.title")}</h2>
        <Pill tone="warn">{plural(t, "payroll.warnings.count", run.warnings.length)}</Pill>
      </div>
      <ul className="flex flex-col gap-2">
        {groups.map((group) => (
          <li key={group.code} className="alert alert-warn" data-testid={`warning-${group.code}`}>
            <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
            <span>
              {t(warningKey(group.code), { detail: group.detail ?? "" })}
              {group.names.length > 0 ? (
                <>
                  {" "}
                  <span className="block font-normal">{group.names.join(", ")}</span>
                </>
              ) : null}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function PayslipsCard({ run, can, onTds }: { run: RunDetail; can: RunActions; onTds: (slip: PayslipRow) => void }) {
  const { t } = useI18n();
  const slips = run.payslips;
  return (
    <section className="card" aria-labelledby="run-payslips-title">
      <div className="card-head">
        <div>
          <h2 id="run-payslips-title">{t("payroll.run.payslips")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("payroll.run.payslipsSub")}</p>
        </div>
      </div>
      {slips.length === 0 ? (
        <p className="empty" data-testid="run-payslips-empty">
          {t("payroll.run.noPayslips")}{" "}
          <Link href="/app/payroll/staff" className="link font-semibold">
            {t("payroll.runs.needStructureLink")}
          </Link>
        </p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table payroll-table" data-testid="run-payslips-table">
              <thead>
                <tr>
                  <th scope="col">{t("payroll.col.name")}</th>
                  <th scope="col" className="r">
                    {t("payroll.col.days")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.gross")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.pf")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.esi")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.pt")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.tds")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.other")}
                  </th>
                  <th scope="col" className="r">
                    {t("payroll.col.net")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {slips.map((slip) => (
                  <tr key={slip.id} data-testid="run-payslip-row">
                    <td>
                      <Link href={`/app/payroll/payslips/${slip.id}`} className="link font-semibold">
                        {slip.name}
                      </Link>
                      <span className="block text-[12.5px] text-ink-3">
                        {[slip.employeeCode, slip.departmentName].filter(Boolean).join(" · ") || "—"}
                        {slip.hasBank ? null : (
                          <>
                            {" · "}
                            <span className="text-warn">{t("payroll.run.noBank")}</span>
                          </>
                        )}
                      </span>
                    </td>
                    <td className="r num whitespace-nowrap">
                      {formatPayDays(slip.payableDays)}
                      {slip.lopDays > 0 ? (
                        <span className="block text-[12px] text-bad">
                          {t("payroll.run.lop", { days: formatPayDays(slip.lopDays) })}
                        </span>
                      ) : null}
                    </td>
                    <td className="r num">{formatPaise(slip.grossPaise)}</td>
                    <td className="r num">{formatPaise(slip.pfEmployeePaise)}</td>
                    <td className="r num">{formatPaise(slip.esiEmployeePaise)}</td>
                    <td className="r num">{formatPaise(slip.ptPaise)}</td>
                    <td className="r num whitespace-nowrap">
                      <TdsCell slip={slip} can={can} onTds={onTds} />
                    </td>
                    <td className="r num">{formatPaise(slip.otherDeductionsPaise)}</td>
                    <td className="r num font-semibold">{formatPaise(slip.netPaise)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("payroll.total")}</th>
                  <td />
                  <td className="r num">{formatPaise(run.totals.grossPaise)}</td>
                  <td className="r num">{formatPaise(run.totals.pfEmployeePaise)}</td>
                  <td className="r num">{formatPaise(run.totals.esiEmployeePaise)}</td>
                  <td className="r num">{formatPaise(run.totals.ptPaise)}</td>
                  <td className="r num">{formatPaise(run.totals.tdsPaise)}</td>
                  <td className="r num">
                    {formatPaise(
                      run.totals.deductionsPaise -
                        run.totals.pfEmployeePaise -
                        run.totals.esiEmployeePaise -
                        run.totals.ptPaise -
                        run.totals.tdsPaise,
                    )}
                  </td>
                  <td className="r num">{formatPaise(run.totals.netPaise)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="run-payslips-cards">
            {slips.map((slip) => (
              <li key={slip.id} className="rowcard items-start">
                <div className="min-w-0 flex-1">
                  <Link href={`/app/payroll/payslips/${slip.id}`} className="link block truncate font-semibold">
                    {slip.name}
                  </Link>
                  <span className="block text-[12.5px] text-ink-3">
                    {t("payroll.run.cardLine", {
                      days: formatPayDays(slip.payableDays),
                      gross: formatPaise(slip.grossPaise),
                      deductions: formatPaise(slip.deductionsPaise),
                    })}
                  </span>
                  <span className="mt-1 flex flex-wrap gap-1">
                    {slip.lopDays > 0 ? (
                      <Pill tone="bad">{t("payroll.run.lop", { days: formatPayDays(slip.lopDays) })}</Pill>
                    ) : null}
                    {slip.tdsOverridden ? <Pill tone="info">{t("payroll.tds.overridden")}</Pill> : null}
                    {slip.hasBank ? null : <Pill tone="warn">{t("payroll.run.noBank")}</Pill>}
                  </span>
                  {can.adjust ? (
                    <button type="button" className="btn btn-sm mt-2" onClick={() => onTds(slip)}>
                      <Pencil size={14} aria-hidden="true" />
                      {t("payroll.tds.change")}
                    </button>
                  ) : null}
                </div>
                <b className="num">{formatPaise(slip.netPaise)}</b>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

function TdsCell({ slip, can, onTds }: { slip: PayslipRow; can: RunActions; onTds: (slip: PayslipRow) => void }) {
  const { t } = useI18n();
  const amount = formatPaise(slip.tdsPaise);
  return (
    <span className="inline-flex items-center justify-end gap-1">
      <span title={slip.tdsOverridden ? (slip.tdsOverrideReason ?? undefined) : undefined}>
        {amount}
        {slip.tdsOverridden ? <span className="text-info"> *</span> : null}
      </span>
      {can.adjust ? (
        <button
          type="button"
          className="iconbtn"
          aria-label={t("payroll.tds.changeFor", { name: slip.name })}
          onClick={() => onTds(slip)}
        >
          <Pencil size={14} aria-hidden="true" />
        </button>
      ) : null}
    </span>
  );
}

function AdjustmentsCard({
  run,
  can,
  locale,
  onAdd,
  onRemove,
}: {
  run: RunDetail;
  can: RunActions;
  locale: string;
  onAdd: () => void;
  onRemove: (id: string, name: string) => void;
}) {
  const { t } = useI18n();
  if (!can.adjust && run.adjustments.length === 0) return null;
  return (
    <section className="card" aria-labelledby="run-adjustments-title" data-testid="run-adjustments">
      <div className="card-head">
        <div>
          <h2 id="run-adjustments-title">{t("payroll.adjustment.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("payroll.adjustment.sub")}</p>
        </div>
        {can.adjust && run.payslips.length > 0 ? (
          <button type="button" className="btn" onClick={onAdd}>
            <Plus size={18} aria-hidden="true" />
            {t("payroll.adjustment.add")}
          </button>
        ) : null}
      </div>
      {run.adjustments.length === 0 ? (
        <p className="empty">{t("payroll.adjustment.empty")}</p>
      ) : (
        <ul className="list">
          {run.adjustments.map((a) => (
            <li key={a.id} className="li items-center">
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2 font-semibold">
                  {a.name ?? "—"}
                  <Pill tone={a.kind === "DEDUCTION" ? "warn" : a.kind === "LOP_CORRECTION" ? "info" : "good"}>
                    {translateOr(t, `payroll.adjustmentKind.${a.kind}`, a.kind)}
                  </Pill>
                  <span className="num">
                    {a.amountPaise !== null
                      ? formatPaise(a.amountPaise)
                      : t("payroll.adjustment.days", { days: formatPayDays(a.days ?? 0) })}
                  </span>
                </p>
                <p className="text-[13px] text-ink-2">{a.reason}</p>
                <p className="text-[12.5px] text-ink-3">
                  {t("payroll.adjustment.by", {
                    name: a.createdByName ?? t("common.system"),
                    date: formatDateTime(a.createdAt, locale),
                  })}
                </p>
              </div>
              {can.adjust ? (
                <button
                  type="button"
                  className="iconbtn"
                  aria-label={t("payroll.adjustment.removeFor", { name: a.name ?? "" })}
                  onClick={() => onRemove(a.id, a.name ?? "")}
                >
                  <Trash2 size={18} aria-hidden="true" />
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

type FileKey = "bank" | "ecr" | "esi" | "pt" | "tds";

function FilesCard({ run }: { run: RunDetail }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [busy, setBusy] = useState<FileKey | null>(null);
  const [error, setError] = useState<string | null>(null);
  const files: { key: FileKey; label: string; path: string; name: string }[] = [
    { key: "bank", label: t("payroll.files.bank"), path: payrollPaths.bankFile(run.id), name: `bank-transfer-${run.month}.csv` },
    { key: "ecr", label: t("payroll.files.ecr"), path: payrollPaths.ecr(run.id), name: `epfo-ecr-${run.month}.txt` },
    { key: "esi", label: t("payroll.files.esi"), path: payrollPaths.esi(run.id), name: `esi-contribution-${run.month}.csv` },
    { key: "pt", label: t("payroll.files.pt"), path: payrollPaths.ptCsv(run.id), name: `professional-tax-${run.month}.csv` },
    { key: "tds", label: t("payroll.files.tds"), path: payrollPaths.tdsCsv(run.id), name: `tds-${run.month}.csv` },
  ];
  const download = async (file: (typeof files)[number]) => {
    setBusy(file.key);
    setError(null);
    try {
      const name = await downloadPayrollFile(file.path, file.name);
      toast(t("payroll.files.downloaded", { name }));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };
  return (
    <section className="card" aria-labelledby="run-files-title" data-testid="run-files">
      <div className="card-head">
        <div>
          <h2 id="run-files-title">{t("payroll.files.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("payroll.files.sub")}</p>
        </div>
      </div>
      <FormAlert message={error} />
      <div className="flex flex-wrap gap-2">
        {files.map((file) => (
          <button
            key={file.key}
            type="button"
            className={file.key === "bank" ? "btn btn-primary" : "btn"}
            onClick={() => download(file)}
            disabled={busy !== null}
            data-testid={`download-${file.key}`}
          >
            <Download size={18} aria-hidden="true" />
            {busy === file.key ? t("common.working") : file.label}
          </button>
        ))}
        <Link href={`/app/payroll/runs/${run.id}/bank-advice`} className="btn" data-testid="bank-advice-link">
          <FileText size={18} aria-hidden="true" />
          {t("payroll.files.advice")}
        </Link>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ dialogs */

function PaidDialog({
  run,
  onClose,
  onDone,
}: {
  run: RunDetail;
  onClose: () => void;
  onDone: (run: RunDetail) => void;
}) {
  const { t } = useI18n();
  const today = todayInIndia();
  const first = `${run.month}-01`;
  const form = useForm({ paidOn: today < first ? first : today, reference: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    const paidOn = form.values.paidOn;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(paidOn)) problems.paidOn = "validation.date";
    else if (paidOn < first) problems.paidOn = "payroll.v.paidBefore";
    else if (paidOn > today) problems.paidOn = "payroll.v.paidFuture";
    if (form.values.reference.trim().length > 100) problems.reference = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    let next: RunDetail | undefined;
    const ok = await form.submit(t, async () => {
      next = await payrollApi.markPaid(run.id, { paidOn, reference: form.values.reference.trim() || null });
    });
    if (ok && next) onDone(next);
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("payroll.paid.title")}
      description={t("payroll.paid.description", { net: formatPaise(run.totals.netPaise) })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("payroll.paid.date")}
          name="paidOn"
          type="date"
          value={form.values.paidOn}
          min={first}
          max={today}
          onChange={(e) => form.set("paidOn", e.target.value)}
          error={form.errors.paidOn}
          required
          data-autofocus
        />
        <TextField
          label={t("payroll.paid.reference")}
          name="reference"
          value={form.values.reference}
          maxLength={100}
          hint={t("payroll.paid.referenceHint")}
          onChange={(e) => form.set("reference", e.target.value)}
          error={form.errors.reference}
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("payroll.paid.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function AdjustmentDialog({
  run,
  onClose,
  onDone,
}: {
  run: RunDetail;
  onClose: () => void;
  onDone: (run: RunDetail) => void;
}) {
  const { t } = useI18n();
  const form = useForm<AdjustmentValues>({ userId: "", kind: "ARREARS", amount: "", days: "", reason: "" });
  const { values, set, errors } = form;
  const money = adjustmentIsMoney(values.kind);
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateAdjustment(values, parseRupees), t, event.currentTarget)) return;
    let next: RunDetail | undefined;
    const ok = await form.submit(t, async () => {
      next = await payrollApi.addAdjustment(run.id, {
        userId: values.userId,
        kind: values.kind,
        amountPaise: money ? parseRupees(values.amount) : null,
        days: money ? null : parseLopDays(values.days),
        reason: values.reason.trim(),
      });
    });
    if (ok && next) onDone(next);
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("payroll.adjustment.add")}
      description={t("payroll.adjustment.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <SelectField
          label={t("payroll.adjustment.person")}
          name="userId"
          value={values.userId}
          onChange={(e) => set("userId", e.target.value)}
          error={errors.userId}
          required
          data-autofocus
          options={[
            { value: "", label: t("common.choose") },
            ...run.payslips.map((slip) => ({
              value: slip.userId,
              label: slip.employeeCode ? `${slip.name} (${slip.employeeCode})` : slip.name,
            })),
          ]}
        />
        <SelectField
          label={t("payroll.adjustment.kind")}
          name="kind"
          value={values.kind}
          onChange={(e) => set("kind", e.target.value as AdjustmentKind)}
          hint={t(`payroll.adjustmentKind.${values.kind}.hint`)}
          error={errors.kind}
          options={ADJUSTMENT_KINDS.map((kind) => ({ value: kind, label: t(`payroll.adjustmentKind.${kind}`) }))}
        />
        {money ? (
          <TextField
            label={t("payroll.adjustment.amount")}
            name="amount"
            inputMode="decimal"
            value={values.amount}
            onChange={(e) => set("amount", e.target.value)}
            error={errors.amount}
            required
          />
        ) : (
          <TextField
            label={t("payroll.adjustment.lopDays")}
            name="days"
            inputMode="decimal"
            value={values.days}
            hint={t("payroll.adjustment.lopDaysHint")}
            onChange={(e) => set("days", e.target.value)}
            error={errors.days}
            required
          />
        )}
        <TextAreaField
          label={t("payroll.adjustment.reason")}
          name="reason"
          value={values.reason}
          maxLength={500}
          onChange={(e) => set("reason", e.target.value)}
          error={errors.reason}
          required
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("payroll.adjustment.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function TdsDialog({
  run,
  slip,
  onClose,
  onDone,
}: {
  run: RunDetail;
  slip: PayslipRow;
  onClose: () => void;
  onDone: (run: RunDetail, message: string) => void;
}) {
  const { t } = useI18n();
  const form = useForm({
    amount: rupeesInput(slip.tdsPaise),
    reason: slip.tdsOverridden ? (slip.tdsOverrideReason ?? "") : "",
  });
  const [clearing, setClearing] = useState(false);
  const [clearError, setClearError] = useState<string | null>(null);
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    const paise = parseRupees(form.values.amount);
    if (paise === null) problems.amount = "payroll.v.tds";
    if (!form.values.reason.trim()) problems.reason = "validation.required";
    else if (form.values.reason.trim().length > 500) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    let next: RunDetail | undefined;
    const ok = await form.submit(t, async () => {
      next = await payrollApi.overrideTds(run.id, slip.id, { amountPaise: paise ?? 0, reason: form.values.reason.trim() });
    });
    if (ok && next) onDone(next, t("payroll.tds.saved", { name: slip.name }));
  };
  const clear = async () => {
    setClearing(true);
    setClearError(null);
    try {
      onDone(await payrollApi.clearTdsOverride(run.id, slip.id), t("payroll.tds.cleared", { name: slip.name }));
    } catch (caught) {
      setClearError(errorMessage(toApiError(caught), t));
    } finally {
      setClearing(false);
    }
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("payroll.tds.title", { name: slip.name })}
      description={t("payroll.tds.description", { amount: formatPaise(slip.tdsCalculatedPaise) })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError ?? clearError} />
        <TextField
          label={t("payroll.tds.amount")}
          name="amount"
          inputMode="decimal"
          value={form.values.amount}
          onChange={(e) => form.set("amount", e.target.value)}
          error={form.errors.amount}
          required
          data-autofocus
        />
        <TextAreaField
          label={t("payroll.tds.reason")}
          name="reason"
          value={form.values.reason}
          maxLength={500}
          hint={t("payroll.tds.reasonHint")}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          required
        />
        <div className="flex flex-wrap justify-end gap-2 pt-1">
          {slip.tdsOverridden ? (
            <button type="button" className="btn mr-auto" onClick={clear} disabled={clearing || form.submitting}>
              {clearing ? t("common.working") : t("payroll.tds.useCalculated")}
            </button>
          ) : null}
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting || clearing}>
            {form.submitting ? t("common.saving") : t("payroll.tds.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
