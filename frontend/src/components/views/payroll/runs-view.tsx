"use client";

import { Plus, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { MONTH_PATTERN } from "@/lib/payroll";
import { payrollApi } from "@/lib/payroll-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { RunDetail, RunList } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { PayrollNav, RunStatusPill, useMonthLabel } from "./payroll-ui";

/** The same checks POST /api/payroll/runs makes: a month up to the current one. Exported for tests. */
export function validateRunMonth(month: string, today: string): Problems {
  if (!MONTH_PATTERN.test(month.trim())) return { month: "payroll.v.month" };
  if (month.trim() > today.slice(0, 7)) return { month: "payroll.v.futureMonth" };
  return {};
}

/** /app/payroll: the school's monthly payroll runs, newest first, and a new month's run. */
export function PayrollRunsView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  const monthLabel = useMonthLabel();
  const router = useRouter();
  const canManage = hasPermission(me, PERMISSIONS.payrollManage);
  const runs = useApiData("payroll:runs", payrollApi.runs);
  const [creating, setCreating] = useState(false);
  const data = runs.data;

  return (
    <>
      <PageHead
        eyebrow={t("payroll.eyebrow")}
        title={t("payroll.runs.title")}
        actions={
          canManage && data ? (
            <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
              <Plus size={18} aria-hidden="true" />
              {t("payroll.runs.new")}
            </button>
          ) : null
        }
      />
      <PayrollNav />

      {data && data.needStructure > 0 ? (
        <div className="alert alert-warn" role="note" data-testid="runs-need-structure">
          <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
          <span>
            {plural(t, "payroll.runs.needStructure", data.needStructure)}{" "}
            <Link href="/app/payroll/staff?filter=structure" className="link font-semibold">
              {t("payroll.runs.needStructureLink")}
            </Link>
          </span>
        </div>
      ) : null}

      <section className="card" aria-labelledby="runs-title">
        <div className="card-head">
          <div>
            <h2 id="runs-title">{t("payroll.runs.list")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("payroll.runs.sub")}</p>
          </div>
        </div>
        {runs.error && !data ? (
          <ErrorState error={runs.error} onRetry={runs.reload} />
        ) : !data ? (
          <LoadingRows rows={4} />
        ) : data.runs.length === 0 ? (
          <p className="empty" data-testid="runs-empty">
            {canManage ? t("payroll.runs.emptyManage") : t("payroll.runs.empty")}
          </p>
        ) : (
          <RunsTable data={data} locale={locale} monthLabel={monthLabel} />
        )}
      </section>

      {creating && data ? (
        <NewRunDialog
          suggested={data.suggestedMonth}
          onClose={() => setCreating(false)}
          onCreated={(run) => {
            setCreating(false);
            router.push(`/app/payroll/runs/${run.id}`);
          }}
        />
      ) : null}
    </>
  );
}

function RunsTable({
  data,
  locale,
  monthLabel,
}: {
  data: RunList;
  locale: string;
  monthLabel: (month: string) => string;
}) {
  const { t } = useI18n();
  const when = (run: RunList["runs"][number]) =>
    run.paidOn
      ? t("payroll.runs.paidOn", { date: formatPlainDate(run.paidOn, locale) })
      : run.approvedByName
        ? t("payroll.runs.approvedBy", { name: run.approvedByName })
        : t("payroll.runs.notApproved");
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="runs-table">
          <thead>
            <tr>
              <th scope="col">{t("payroll.col.month")}</th>
              <th scope="col">{t("payroll.col.status")}</th>
              <th scope="col" className="r">
                {t("payroll.col.staff")}
              </th>
              <th scope="col" className="r">
                {t("payroll.col.gross")}
              </th>
              <th scope="col" className="r">
                {t("payroll.col.deductions")}
              </th>
              <th scope="col" className="r">
                {t("payroll.col.net")}
              </th>
              <th scope="col">{t("payroll.col.progress")}</th>
            </tr>
          </thead>
          <tbody>
            {data.runs.map((run) => (
              <tr key={run.id}>
                <td>
                  <Link href={`/app/payroll/runs/${run.id}`} className="link font-semibold whitespace-nowrap">
                    {monthLabel(run.month)}
                  </Link>
                </td>
                <td>
                  <RunStatusPill status={run.status} />
                </td>
                <td className="r num">{run.totals.staffCount}</td>
                <td className="r num">{formatPaise(run.totals.grossPaise)}</td>
                <td className="r num">{formatPaise(run.totals.deductionsPaise)}</td>
                <td className="r num font-semibold">{formatPaise(run.totals.netPaise)}</td>
                <td className="text-[13px] text-ink-2">{when(run)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid="runs-cards">
        {data.runs.map((run) => (
          <li key={run.id}>
            <Link href={`/app/payroll/runs/${run.id}`} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block truncate font-semibold">{monthLabel(run.month)}</b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {plural(t, "payroll.staffCount", run.totals.staffCount)} · {when(run)}
                </span>
              </span>
              <span className="flex flex-col items-end gap-1">
                <span className="num font-semibold">{formatPaise(run.totals.netPaise)}</span>
                <RunStatusPill status={run.status} />
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}

function NewRunDialog({
  suggested,
  onClose,
  onCreated,
}: {
  suggested: string;
  onClose: () => void;
  onCreated: (run: RunDetail) => void;
}) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const today = todayInIndia();
  const form = useForm({ month: suggested });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const month = form.values.month.trim();
    if (!form.check(validateRunMonth(month, today), t, event.currentTarget)) return;
    let run: RunDetail | undefined;
    const ok = await form.submit(t, async () => {
      run = await payrollApi.createRun(month);
    });
    if (ok && run) onCreated(run);
  };
  const valid = MONTH_PATTERN.test(form.values.month);
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("payroll.runs.newTitle")}
      description={t("payroll.runs.newDescription")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("payroll.runs.month")}
          name="month"
          type="month"
          value={form.values.month}
          max={today.slice(0, 7)}
          onChange={(e) => form.set("month", e.target.value)}
          hint={valid ? monthLabel(form.values.month) : t("payroll.runs.monthHint")}
          error={form.errors.month}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("payroll.runs.calculating") : t("payroll.runs.create")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
