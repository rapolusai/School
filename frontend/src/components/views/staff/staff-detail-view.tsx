"use client";

import { ArrowLeft, LogOut, Pencil, UserRoundPen } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPhone, formatPlainDate, initials, todayInIndia } from "@/lib/format";
import { localeFor, roleLabel, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { staffApi } from "@/lib/staff-api";
import type { LeaveBalance, StaffDetail, StaffLeaveRequest, StaffPersonMonth } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { BalanceDialog, LeaveActionDialog } from "./leave-dialogs";
import { BalanceGrid, RequestList } from "./leave-parts";
import { LeavingDialog, ProfileDialog } from "./staff-form";
import {
  employmentLabel,
  formatClock,
  formatNumber,
  STAFF_DAY_LABEL,
  StaffCountsBar,
  staffMark,
  StaffMarkBadge,
  StaffMarkLegend,
} from "./staff-shared";

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

type Open = "profile" | "leaving" | null;

/** One staff member: profile, this month's attendance and leave. */
export function StaffDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = hasPermission(me, PERMISSIONS.staffManage);
  const staff = useApiData(`staff:${id}`, () => staffApi.get(id));
  const [open, setOpen] = useState<Open>(null);
  const s = staff.data;

  const back = (
    <Link href="/app/staff" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("staff.back")}
    </Link>
  );

  if (staff.error && !s) {
    return (
      <>
        {back}
        <section className="card">
          {staff.error.status === 404 ? (
            <p className="empty" data-testid="staff-not-found">
              {t("staff.notFound")}
            </p>
          ) : (
            <ErrorState error={staff.error} onRetry={staff.reload} />
          )}
        </section>
      </>
    );
  }
  if (!s) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      </>
    );
  }

  const p = s.profile;
  const active = s.status === "ACTIVE";
  const saved = (message: string) => {
    setOpen(null);
    staff.reload();
    toast(message);
  };

  return (
    <>
      {back}
      <div className="page-head">
        <div className="person min-w-0">
          <span className="avatar h-12 w-12 text-base" aria-hidden="true">
            {initials(s.name)}
          </span>
          <div className="min-w-0">
            <p className="eyebrow">{t("staff.member")}</p>
            <h1 className="mt-0.5 flex flex-wrap items-center gap-2" data-testid="staff-name">
              {s.name}
              <Pill tone={active ? "good" : "neutral"} dot>
                {t(active ? "staff.status.ACTIVE" : "staff.status.LEFT")}
              </Pill>
              {!s.profileComplete ? <Pill tone="warn">{t("staff.profileIncomplete")}</Pill> : null}
            </h1>
            <p className="mt-1 text-sm text-ink-2">
              {[p?.employeeCode, p?.designation, p?.department?.name].filter(Boolean).join(" · ") || s.email}
            </p>
          </div>
        </div>
        {canManage && active ? (
          <div className="flex flex-wrap gap-2">
            {p ? (
              <button type="button" className="btn" onClick={() => setOpen("leaving")}>
                <LogOut size={18} aria-hidden="true" />
                {t("staff.leaving.button")}
              </button>
            ) : null}
            <button type="button" className="btn btn-primary" onClick={() => setOpen("profile")}>
              {p ? <Pencil size={18} aria-hidden="true" /> : <UserRoundPen size={18} aria-hidden="true" />}
              {p ? t("staff.profile.edit") : t("staff.profile.complete")}
            </button>
          </div>
        ) : null}
      </div>

      {!active ? (
        <section className="alert alert-info" role="status" data-testid="staff-left">
          {p?.dateOfLeaving
            ? t("staff.leftOn", { date: formatPlainDate(p.dateOfLeaving, locale) })
            : t("staff.signInDisabled")}
          {p?.leavingReason ? ` ${t("staff.leftReason", { reason: p.leavingReason })}` : ""}
        </section>
      ) : !p ? (
        <section className="alert alert-info" role="status" data-testid="staff-incomplete">
          {canManage ? t("staff.detail.incomplete.manage") : t("staff.detail.incomplete")}
        </section>
      ) : null}

      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        <section className="card" aria-labelledby="staff-profile-heading">
          <div className="card-head">
            <h2 id="staff-profile-heading">{t("staff.detail.profile")}</h2>
          </div>
          <dl className="kv" data-testid="staff-profile">
            <dt>{t("staff.field.employeeCode")}</dt>
            <dd className="mono">{p?.employeeCode ?? "—"}</dd>
            <dt>{t("staff.field.designation")}</dt>
            <dd>{p?.designation ?? "—"}</dd>
            <dt>{t("staff.field.department")}</dt>
            <dd>{p?.department?.name ?? "—"}</dd>
            <dt>{t("staff.field.employmentType")}</dt>
            <dd>{employmentLabel(t, p?.employmentType)}</dd>
            <dt>{t("staff.field.dateOfJoining")}</dt>
            <dd>{p ? formatPlainDate(p.dateOfJoining, locale) : "—"}</dd>
            <dt>{t("staff.field.mobile")}</dt>
            <dd>
              {p?.mobile ? (
                <a href={`tel:+91${p.mobile}`} className="link num">
                  {formatPhone(p.mobile)}
                </a>
              ) : (
                "—"
              )}
            </dd>
            <dt>{t("users.field.email")}</dt>
            <dd className="break-all">{s.email}</dd>
            <dt>{t("users.field.roles")}</dt>
            <dd>{s.roles.map((r) => roleLabel(t, r)).join(", ")}</dd>
            <dt>{t("staff.field.qualifications")}</dt>
            <dd className="whitespace-pre-line">{p?.qualifications ?? "—"}</dd>
            <dt>{t("staff.field.emergency")}</dt>
            <dd>
              {p?.emergencyContactName
                ? `${p.emergencyContactName} · ${formatPhone(p.emergencyContactMobile)}`
                : "—"}
            </dd>
            <dt>{t("staff.detail.approver")}</dt>
            <dd>
              {s.leaveApprover.routing === "DEPARTMENT_HEAD" && s.leaveApprover.departmentHead
                ? t("staff.approver.head", { name: s.leaveApprover.departmentHead.name })
                : t("staff.approver.school")}
            </dd>
            <dt>{t("staff.detail.lastSignIn")}</dt>
            <dd>{s.lastLoginAt ? formatDateTime(s.lastLoginAt, locale) : t("staff.detail.neverSignedIn")}</dd>
          </dl>
        </section>

        <StaffMonthCard userId={s.userId} />
      </div>

      <StaffLeaveCard staff={s} canManage={canManage} />

      {canManage && open === "profile" ? (
        <ProfileDialog
          open
          staff={s}
          onClose={() => setOpen(null)}
          onSaved={(next) => saved(t("staff.profile.saved", { name: next.name }))}
        />
      ) : null}
      {canManage && open === "leaving" ? (
        <LeavingDialog
          open
          staff={s}
          onClose={() => setOpen(null)}
          onSaved={(next) => saved(t("staff.leaving.saved", { name: next.name }))}
        />
      ) : null}
    </>
  );
}

/** Monday-first weeks of a month for the mini calendar; null pads the first week. */
export function calendarWeeks<T extends { date: string }>(days: T[]): (T | null)[][] {
  if (days.length === 0) return [];
  const first = new Date(`${days[0].date}T00:00:00Z`).getUTCDay();
  const cells: (T | null)[] = [...Array<null>((first + 6) % 7).fill(null), ...days];
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks: (T | null)[][] = [];
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
  return weeks;
}

function StaffMonthCard({ userId }: { userId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [month, setMonth] = useState(today.slice(0, 7));
  const data = useApiData(`staff:attendance:${userId}:${month}`, () => staffApi.attendanceOf(userId, month));
  const view: StaffPersonMonth | undefined = data.data?.month === month ? data.data : undefined;
  const weekday = new Intl.DateTimeFormat(locale, { weekday: "narrow", timeZone: "UTC" });
  // 2024-01-01 was a Monday.
  const weekdays = Array.from({ length: 7 }, (_, i) => weekday.format(new Date(Date.UTC(2024, 0, 1 + i))));

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="staff-month-heading" data-testid="staff-month">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="staff-month-heading">{t("staff.detail.attendance")}</h2>
        <label className="field">
          <span className="sr-only">{t("staffAttendance.month")}</span>
          <input
            type="month"
            className="input"
            name="month"
            value={month}
            max={today.slice(0, 7)}
            onChange={(e) => {
              if (MONTH.test(e.target.value)) setMonth(e.target.value);
            }}
          />
        </label>
      </div>
      {data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <StaffCountsBar counts={view.counts} />
          <p className="text-[13px] text-ink-2">
            {t("staffAttendance.daysWorked")}: <b className="num">{formatNumber(view.daysWorked)}</b>
          </p>
          <table className="w-full table-fixed border-separate border-spacing-1 text-center text-[13px]">
            <caption className="sr-only">{t("staff.detail.attendance")}</caption>
            <thead>
              <tr>
                {weekdays.map((d, i) => (
                  <th key={i} scope="col" className="font-medium text-ink-3">
                    {d}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {calendarWeeks(view.days).map((week, i) => (
                <tr key={i}>
                  {week.map((day, j) =>
                    day ? (
                      <td
                        key={day.date}
                        className={`h-11 rounded-md align-middle ${day.workingDay ? "bg-surface-2" : ""}`}
                        title={[
                          formatPlainDate(day.date, locale),
                          day.status ? t(STAFF_DAY_LABEL[day.status]) : null,
                          day.checkInAt ? `${t("staffAttendance.in")} ${formatClock(day.checkInAt, locale)}` : null,
                          day.checkOutAt ? `${t("staffAttendance.out")} ${formatClock(day.checkOutAt, locale)}` : null,
                        ]
                          .filter(Boolean)
                          .join(" · ")}
                      >
                        <span className="block text-[11px] text-ink-3 num">{Number(day.date.slice(8))}</span>
                        {day.status ? (
                          <StaffMarkBadge mark={staffMark(day.status)} />
                        ) : (
                          <span className="text-ink-3" aria-hidden="true">
                            ·
                          </span>
                        )}
                      </td>
                    ) : (
                      <td key={`pad-${j}`} />
                    ),
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          <StaffMarkLegend />
        </>
      )}
    </section>
  );
}

function StaffLeaveCard({ staff, canManage }: { staff: StaffDetail; canManage: boolean }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [yearId, setYearId] = useState("");
  const [editing, setEditing] = useState<LeaveBalance | null>(null);
  const [cancelling, setCancelling] = useState<StaffLeaveRequest | null>(null);
  const data = useApiData(`staff:leave:${staff.userId}:${yearId}`, () =>
    staffApi.leaveOf(staff.userId, yearId || undefined),
  );
  const view = data.data;

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="staff-leave-heading" data-testid="staff-leave">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="staff-leave-heading">{t("staff.detail.leave")}</h2>
        {view && view.years.length > 1 ? (
          <label className="field">
            <span className="sr-only">{t("leave.year")}</span>
            <select
              className="input"
              name="yearId"
              value={yearId || view.year?.id || ""}
              onChange={(e) => setYearId(e.target.value)}
            >
              {view.years.map((y) => (
                <option key={y.id} value={y.id}>
                  {y.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </div>
      {data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={3} />
      ) : !view.year ? (
        <p className="empty">{t("leave.noYear")}</p>
      ) : (
        <>
          <BalanceGrid balances={view.balances} onSetBalance={canManage ? setEditing : undefined} />
          <h3 className="mt-1 text-[14px] font-semibold">{t("leave.requests")}</h3>
          <RequestList
            requests={view.requests}
            empty={t("leave.noRequests")}
            testId="staff-leave-requests"
            actions={(r) =>
              r.canCancel ? (
                <button type="button" className="btn btn-sm" onClick={() => setCancelling(r)}>
                  {t("leave.cancel")}
                </button>
              ) : null
            }
          />
        </>
      )}
      {canManage && editing && view ? (
        <BalanceDialog
          key={editing.leaveTypeId}
          userId={staff.userId}
          name={staff.name}
          year={view.year}
          balance={editing}
          onClose={() => setEditing(null)}
          onSaved={(b) => {
            setEditing(null);
            data.reload();
            toast(t("leave.balance.saved", { type: b.leaveTypeName, name: staff.name }));
          }}
        />
      ) : null}
      {cancelling ? (
        <LeaveActionDialog
          key={cancelling.id}
          request={cancelling}
          action="cancel"
          onClose={() => setCancelling(null)}
          onDone={() => {
            setCancelling(null);
            data.reload();
            toast(t("leave.cancelled"));
          }}
        />
      ) : null}
    </section>
  );
}
