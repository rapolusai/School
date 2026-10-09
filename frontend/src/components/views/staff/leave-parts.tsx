"use client";

import { Pill } from "@/components/ui/pill";
import { formatDate, formatDateTime, initials } from "@/lib/format";
import { localeFor, translateOr, useI18n, type Translate } from "@/lib/i18n";
import type { LeaveBalance, StaffLeaveRequest } from "@/lib/types";
import { requestSummary } from "./leave-dialogs";
import { formatNumber, leaveStatusTone } from "./staff-shared";

/** One small card per leave type: days left, taken, waiting and the year's allowance. */
export function BalanceGrid({
  balances,
  onSetBalance,
}: {
  balances: LeaveBalance[];
  onSetBalance?: (balance: LeaveBalance) => void;
}) {
  const { t } = useI18n();
  const shown = balances.filter((b) => b.active || b.taken > 0 || b.pending > 0);
  if (shown.length === 0) return <p className="empty">{t("leave.noBalances")}</p>;
  return (
    <ul className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 xl:grid-cols-3" aria-label={t("leave.balances")} data-testid="leave-balances">
      {shown.map((b) => (
        <li
          key={b.leaveTypeId}
          className="flex flex-col gap-1 rounded-[10px] border border-line bg-surface p-3"
          data-testid={`balance-${b.code}`}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="min-w-0 truncate font-semibold">{b.leaveTypeName}</span>
            <span className="mono text-[12px] text-ink-3">{b.code}</span>
          </div>
          {b.lossOfPay ? (
            <p className="text-[13.5px] text-ink-2">{t("leave.lossOfPay.noLimit")}</p>
          ) : (
            <p className="flex items-baseline gap-1.5">
              <span className="kpi-value" data-testid="balance-available">
                {formatNumber(b.available)}
              </span>
              <span className="text-[13px] text-ink-3">{t("leave.left")}</span>
            </p>
          )}
          <p className="text-[12.5px] text-ink-3">
            {b.lossOfPay
              ? t("leave.takenOnly", { taken: formatNumber(b.taken), pending: formatNumber(b.pending) })
              : t("leave.balanceLine", {
                  opening: formatNumber(b.opening),
                  accrued: formatNumber(b.accrued),
                  taken: formatNumber(b.taken),
                  pending: formatNumber(b.pending),
                })}
          </p>
          {b.setByHand ? <p className="text-[12px] text-ink-3">{t("leave.setByHand")}</p> : null}
          {onSetBalance && !b.lossOfPay ? (
            <button type="button" className="linkbtn self-start text-[13px]" onClick={() => onSetBalance(b)}>
              {t("leave.balance.set")}
            </button>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

/** "Approved by Meena Iyer · 3 Oct 2026, 10:12 am · “Get well soon”" and the like. */
function decisionLine(t: Translate, r: StaffLeaveRequest, locale: string): string | null {
  if (r.status === "CANCELLED") {
    const by = r.cancelledByName ? t("leave.cancelledBy", { name: r.cancelledByName }) : t("leave.status.CANCELLED");
    return [by, r.cancelledAt ? formatDateTime(r.cancelledAt, locale) : null, r.cancelComment ? `“${r.cancelComment}”` : null]
      .filter(Boolean)
      .join(" · ");
  }
  if (r.status === "APPROVED" || r.status === "REJECTED") {
    const key = r.status === "APPROVED" ? "leave.approvedBy" : "leave.rejectedBy";
    return [
      t(key, { name: r.decidedByName ?? "—" }),
      r.decidedAt ? formatDateTime(r.decidedAt, locale) : null,
      r.decisionComment ? `“${r.decisionComment}”` : null,
    ]
      .filter(Boolean)
      .join(" · ");
  }
  return r.approverName ? t("leave.waitingFor", { name: r.approverName }) : t("leave.waitingForSchool");
}

/** Leave requests, newest first as the API sends them. `actions` adds buttons to a row. */
export function RequestList({
  requests,
  showPerson = false,
  showBalance = false,
  actions,
  empty,
  testId,
}: {
  requests: StaffLeaveRequest[];
  showPerson?: boolean;
  showBalance?: boolean;
  actions?: (request: StaffLeaveRequest) => React.ReactNode;
  empty: string;
  testId?: string;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  if (requests.length === 0) return <p className="empty">{empty}</p>;
  return (
    <ul className="list" data-testid={testId}>
      {requests.map((r) => {
        const buttons = actions?.(r);
        return (
          <li key={r.id} className="li flex-wrap" data-testid="leave-request">
            {showPerson ? (
              <span className="avatar" aria-hidden="true">
                {initials(r.userName ?? "?")}
              </span>
            ) : null}
            <div className="min-w-0 flex-1 basis-[220px]">
              {showPerson ? (
                <p className="font-semibold">
                  {r.userName}
                  <span className="ml-1.5 text-[13px] font-normal text-ink-3">
                    {[r.employeeCode, r.departmentName].filter(Boolean).join(" · ")}
                  </span>
                </p>
              ) : null}
              <p className={showPerson ? "text-[14px] text-ink" : "font-semibold"}>
                {requestSummary(t, r, locale)}
              </p>
              <p className="mt-0.5 break-words text-[13.5px] text-ink-2">{r.reason}</p>
              <p className="mt-0.5 text-[12.5px] text-ink-3">
                {[
                  t("leave.appliedOn", { date: formatDate(r.createdAt, locale) }),
                  decisionLine(t, r, locale),
                  showBalance && !r.lossOfPay && r.available !== null
                    ? t("leave.theirBalance", { days: formatNumber(r.available) })
                    : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Pill tone={leaveStatusTone(r.status)} dot>
                {translateOr(t, `leave.status.${r.status}`, r.status)}
              </Pill>
              {buttons}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
