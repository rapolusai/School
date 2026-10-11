"use client";

import { Printer, ReceiptText } from "lucide-react";
import Link from "next/link";
import { AccessDenied } from "@/components/access";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { formatPayDays } from "@/lib/payroll";
import { payrollApi } from "@/lib/payroll-api";
import type { PayslipLineView, PayslipView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { BackLink, PayrollNav, RunStatusPill, useMonthLabel } from "./payroll-ui";

/** "XXXX 9012": the only part of a bank account a payslip shows. */
export function bankLine(last4: string | null, ifsc: string | null): string | null {
  if (!last4) return null;
  return [`XXXX ${last4}`, ifsc].filter(Boolean).join(" · ");
}

function PrintButton({ label }: { label: string }) {
  return (
    <button type="button" className="btn" onClick={() => window.print()}>
      <Printer size={18} aria-hidden="true" />
      {label}
    </button>
  );
}

function Lines({
  title,
  lines,
  totalLabel,
  totalPaise,
  showFull,
  testId,
}: {
  title: string;
  lines: PayslipLineView[];
  totalLabel: string;
  totalPaise: number;
  showFull?: boolean;
  testId: string;
}) {
  const { t } = useI18n();
  return (
    <table className="table payslip-lines" data-testid={testId}>
      <thead>
        <tr>
          <th scope="col">{title}</th>
          {showFull ? (
            <th scope="col" className="r">
              {t("payslip.fullMonth")}
            </th>
          ) : null}
          <th scope="col" className="r">
            {t("payslip.amount")}
          </th>
        </tr>
      </thead>
      <tbody>
        {lines.length === 0 ? (
          <tr>
            <td colSpan={showFull ? 3 : 2} className="text-ink-3">
              {t("payslip.none")}
            </td>
          </tr>
        ) : (
          lines.map((line, index) => (
            <tr key={`${line.code}-${index}`}>
              <td>{line.name}</td>
              {showFull ? (
                <td className="r num text-ink-3">{line.fullPaise !== null ? formatPaise(line.fullPaise) : ""}</td>
              ) : null}
              <td className="r num">{formatPaise(line.amountPaise)}</td>
            </tr>
          ))
        )}
      </tbody>
      <tfoot>
        <tr>
          <th scope="row" colSpan={showFull ? 2 : 1}>
            {totalLabel}
          </th>
          <td className="r num">{formatPaise(totalPaise)}</td>
        </tr>
      </tfoot>
    </table>
  );
}

/**
 * A payslip as printed on A4: the school, the month, the person (PAN masked, bank account by its last four digits),
 * the days, earnings, deductions, the employer's contributions, and the net pay in figures and words.
 */
export function PayslipDocument({ payslip, printable = true }: { payslip: PayslipView; printable?: boolean }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const monthLabel = useMonthLabel();
  const p = payslip.person;
  const school = payslip.school;
  const draft = payslip.status === "DRAFT";
  const bank = bankLine(p.bankLast4, p.bankIfsc);
  const registrations = [
    school.pfEstablishmentCode ? t("payslip.pfCode", { code: school.pfEstablishmentCode }) : null,
    school.esiEmployerCode ? t("payslip.esiCode", { code: school.esiEmployerCode }) : null,
    school.tan ? t("payslip.tan", { code: school.tan }) : null,
  ].filter(Boolean);

  return (
    <article
      className={`payslip receipt${printable ? " print-area" : ""}${draft ? " receipt-cancelled" : ""}`}
      aria-label={t("payslip.label", { name: p.name, month: monthLabel(payslip.month) })}
      data-stamp={draft ? t("payroll.status.DRAFT") : undefined}
      data-testid="payslip"
    >
      <header className="receipt-head">
        <div className="min-w-0">
          <p className="receipt-school">{school.name}</p>
          {school.address || school.city ? (
            <p className="text-[13px] text-ink-2">{[school.address, school.city].filter(Boolean).join(", ")}</p>
          ) : null}
          <p className="text-[12.5px] text-ink-3">
            {[school.phone ? t("payslip.phone", { phone: school.phone }) : null, school.email]
              .filter(Boolean)
              .join(" · ")}
          </p>
        </div>
        <div className="receipt-title">
          <h2>{t("payslip.title")}</h2>
          <p className="font-semibold" data-testid="payslip-month">
            {monthLabel(payslip.month)}
          </p>
          <RunStatusPill status={payslip.status} />
        </div>
      </header>

      <dl className="receipt-meta">
        <div>
          <dt>{t("payslip.name")}</dt>
          <dd data-testid="payslip-name">{p.name}</dd>
        </div>
        <div>
          <dt>{t("payslip.employeeCode")}</dt>
          <dd className="mono">{p.employeeCode ?? "—"}</dd>
        </div>
        <div>
          <dt>{t("payslip.designation")}</dt>
          <dd>{[p.designation, p.departmentName].filter(Boolean).join(" · ") || "—"}</dd>
        </div>
        <div>
          <dt>{t("payslip.joined")}</dt>
          <dd>{p.dateOfJoining ? formatPlainDate(p.dateOfJoining, locale) : "—"}</dd>
        </div>
        <div>
          <dt>{t("payslip.pan")}</dt>
          <dd className="mono" data-testid="payslip-pan">
            {p.panMasked ?? t("payslip.notGiven")}
          </dd>
        </div>
        <div>
          <dt>{t("payslip.bank")}</dt>
          <dd className="mono" data-testid="payslip-bank">
            {bank ?? t("payslip.notGiven")}
          </dd>
        </div>
        {p.uan ? (
          <div>
            <dt>{t("payslip.uan")}</dt>
            <dd className="mono">{p.uan}</dd>
          </div>
        ) : null}
        {p.esiNumber ? (
          <div>
            <dt>{t("payslip.esiNumber")}</dt>
            <dd className="mono">{p.esiNumber}</dd>
          </div>
        ) : null}
        {p.dateOfLeaving ? (
          <div>
            <dt>{t("payslip.left")}</dt>
            <dd>{formatPlainDate(p.dateOfLeaving, locale)}</dd>
          </div>
        ) : null}
        <div>
          <dt>{t("payslip.payableDays")}</dt>
          <dd className="num" data-testid="payslip-days">
            {t("payslip.daysOf", {
              days: formatPayDays(payslip.payableDays),
              basis: formatPayDays(payslip.basisDays),
            })}
          </dd>
        </div>
        <div>
          <dt>{t("payslip.lopDays")}</dt>
          <dd className="num" data-testid="payslip-lop">
            {formatPayDays(payslip.lopDays)}
          </dd>
        </div>
        <div>
          <dt>{t("payslip.paidOn")}</dt>
          <dd>{payslip.paidOn ? formatPlainDate(payslip.paidOn, locale) : t("payslip.notPaid")}</dd>
        </div>
      </dl>

      <div className="payslip-cols">
        <Lines
          title={t("payslip.earnings")}
          lines={payslip.earnings}
          totalLabel={t("payslip.gross")}
          totalPaise={payslip.grossPaise}
          showFull
          testId="payslip-earnings"
        />
        <Lines
          title={t("payslip.deductions")}
          lines={payslip.deductions}
          totalLabel={t("payslip.totalDeductions")}
          totalPaise={payslip.deductionsPaise}
          testId="payslip-deductions"
        />
      </div>

      <div className="payslip-net" data-testid="payslip-net">
        <span>{t("payslip.net")}</span>
        <b className="num">{formatPaise(payslip.netPaise)}</b>
      </div>
      <p className="receipt-words" data-testid="payslip-words">
        <span className="text-ink-3">{t("payslip.inWords")}</span> {payslip.netInWords}
      </p>

      {payslip.employer.length > 0 ? (
        <div className="payslip-employer">
          <Lines
            title={t("payslip.employer")}
            lines={payslip.employer}
            totalLabel={t("payslip.employerTotal")}
            totalPaise={payslip.employerPaise}
            testId="payslip-employer"
          />
          <p className="text-[12.5px] text-ink-3">{t("payslip.employerNote")}</p>
        </div>
      ) : null}

      <p className="text-[12.5px] text-ink-3">
        {t("payslip.tax", {
          income: formatPaise(payslip.taxableYearProjectedPaise),
          tax: formatPaise(payslip.taxYearPaise),
        })}
      </p>

      <p className="receipt-foot">
        {[t("payslip.footer"), ...registrations].join(" · ")}
        {payslip.prorationBasis === "WORKING_DAYS" ? ` · ${t("payslip.basisWorking")}` : ""}
      </p>
    </article>
  );
}

/** /app/payroll/payslips/[id]: one payslip for payroll readers, with Print. */
export function PayrollPayslipView({ id }: { id: string }) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const slip = useApiData(`payroll:payslip:${id}`, () => payrollApi.payslip(id));
  const s = slip.data;
  const back = s ? (
    <BackLink href={`/app/payroll/runs/${s.runId}`} label={t("payslip.backToRun", { month: monthLabel(s.month) })} />
  ) : (
    <BackLink href="/app/payroll" label={t("payroll.run.back")} />
  );
  return (
    <>
      {back}
      {s ? (
        <div className="page-head no-print">
          <div className="min-w-0">
            <p className="eyebrow">{t("payroll.eyebrow")}</p>
            <h1 className="mt-1">{t("payslip.pageTitle", { name: s.person.name, month: monthLabel(s.month) })}</h1>
          </div>
          <div className="flex flex-wrap gap-2">
            <PrintButton label={t("payslip.print")} />
          </div>
        </div>
      ) : null}
      <section className="card">
        {slip.error && !s ? (
          slip.error.status === 404 ? (
            <p className="empty">{t("payslip.notFound")}</p>
          ) : (
            <ErrorState error={slip.error} onRetry={slip.reload} />
          )
        ) : !s ? (
          <LoadingRows rows={6} />
        ) : (
          <PayslipDocument payslip={s} />
        )}
      </section>
    </>
  );
}

/** /app/payroll/runs/[id]/payslips: every payslip of a month, to print all at once (one per page). */
export function RunPayslipsView({ runId }: { runId: string }) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const slips = useApiData(`payroll:run-payslips:${runId}`, () => payrollApi.runPayslips(runId));
  const list = slips.data;
  const month = list?.[0]?.month;
  return (
    <>
      <BackLink href={`/app/payroll/runs/${runId}`} label={t("payslip.backToRun", { month: monthLabel(month) })} />
      <PageHead
        eyebrow={t("payroll.eyebrow")}
        title={month ? t("payslip.allTitle", { month: monthLabel(month) }) : t("payslip.allTitleNoMonth")}
        actions={list && list.length > 0 ? <PrintButton label={t("payslip.printAll")} /> : null}
      />
      <PayrollNav />
      {slips.error && !list ? (
        <section className="card">
          {slips.error.status === 404 ? (
            <p className="empty">{t("payroll.run.notFound")}</p>
          ) : (
            <ErrorState error={slips.error} onRetry={slips.reload} />
          )}
        </section>
      ) : !list ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : list.length === 0 ? (
        <section className="card">
          <p className="empty">{t("payroll.run.noPayslips")}</p>
        </section>
      ) : (
        <>
          <p className="no-print text-[13.5px] text-ink-2">{plural(t, "payslip.allCount", list.length)}</p>
          <div className="print-area payslip-batch" data-testid="payslip-batch">
            {list.map((slip) => (
              <section key={slip.id} className="card">
                <PayslipDocument payslip={slip} printable={false} />
              </section>
            ))}
          </div>
        </>
      )}
    </>
  );
}

/** /app/my-payslips: the signed-in member of staff's own payslips (approved and paid months). */
export function MyPayslipsView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const monthLabel = useMonthLabel();
  const mine = useApiData("payroll:mine", payrollApi.myPayslips);
  if (mine.error?.status === 403) return <AccessDenied />;
  const list = mine.data;
  return (
    <>
      <PageHead eyebrow={t("payslip.mine.eyebrow")} title={t("payslip.mine.title")} />
      <section className="card" aria-labelledby="my-payslips-title">
        <div className="card-head">
          <div>
            <h2 id="my-payslips-title">{t("payslip.mine.list")}</h2>
            <p className="mt-1 text-sm text-ink-2">{t("payslip.mine.sub")}</p>
          </div>
        </div>
        {mine.error && !list ? (
          <ErrorState error={mine.error} onRetry={mine.reload} />
        ) : !list ? (
          <LoadingRows rows={4} />
        ) : list.length === 0 ? (
          <p className="empty" data-testid="my-payslips-empty">
            {t("payslip.mine.empty")}
          </p>
        ) : (
          <ul className="rowcards" data-testid="my-payslips">
            {list.map((row) => (
              <li key={row.id}>
                <Link href={`/app/my-payslips/${row.id}`} className="rowcard" data-testid="my-payslip-link">
                  <span className="badge-ic">
                    <ReceiptText size={18} aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <b className="block truncate font-semibold">{monthLabel(row.month)}</b>
                    <span className="block truncate text-[12.5px] text-ink-3">
                      {row.paidOn
                        ? t("payslip.mine.paidOn", { date: formatPlainDate(row.paidOn, locale) })
                        : t("payslip.mine.approved")}
                      {" · "}
                      {t("payslip.mine.gross", { amount: formatPaise(row.grossPaise) })}
                    </span>
                  </span>
                  <span className="flex flex-col items-end gap-1">
                    <span className="num font-semibold">{formatPaise(row.netPaise)}</span>
                    <span className="text-[12px] text-ink-3">{t("payslip.mine.net")}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

/** /app/my-payslips/[id]: one of the signed-in person's own payslips, with Print. */
export function MyPayslipView({ id }: { id: string }) {
  const { t } = useI18n();
  const monthLabel = useMonthLabel();
  const slip = useApiData(`payroll:mine:${id}`, () => payrollApi.myPayslip(id));
  if (slip.error?.status === 403) return <AccessDenied />;
  const s = slip.data;
  return (
    <>
      <BackLink href="/app/my-payslips" label={t("payslip.mine.back")} />
      {s ? (
        <div className="page-head no-print">
          <div className="min-w-0">
            <p className="eyebrow">{t("payslip.mine.eyebrow")}</p>
            <h1 className="mt-1">{t("payslip.mine.pageTitle", { month: monthLabel(s.month) })}</h1>
          </div>
          <div className="flex flex-wrap gap-2">
            <PrintButton label={t("payslip.print")} />
          </div>
        </div>
      ) : null}
      <section className="card">
        {slip.error && !s ? (
          slip.error.status === 404 ? (
            <p className="empty" data-testid="payslip-not-found">
              {t("payslip.notFound")}
            </p>
          ) : (
            <ErrorState error={slip.error} onRetry={slip.reload} />
          )
        ) : !s ? (
          <LoadingRows rows={6} />
        ) : (
          <PayslipDocument payslip={s} />
        )}
      </section>
    </>
  );
}
