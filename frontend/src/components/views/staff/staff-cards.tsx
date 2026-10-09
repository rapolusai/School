"use client";

import { ArrowRight, Clock, Inbox, UserCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { leaveApi, staffAttendanceApi } from "@/lib/staff-api";
import type { MyStaffDay } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { dateRange, formatClock, formatDays, STAFF_DAY_LABEL, staffDayTone } from "./staff-shared";

/** Dashboard cards of the staff slice: check in/out, leave waiting for me, staff present today. */
export function StaffDashboardCards() {
  const { me } = useAuth();
  const canRequest = hasPermission(me, PERMISSIONS.leaveRequest);
  const canApprove = hasPermission(me, PERMISSIONS.leaveApprove);
  const canRead = hasPermission(me, PERMISSIONS.staffRead);
  if (!canRequest && !canApprove && !canRead) return null;
  return (
    <div className="grid grid-cols-1 gap-3.5 md:grid-cols-2 xl:grid-cols-3" data-testid="staff-dashboard-cards">
      {canRequest ? <CheckInCard /> : null}
      {canRequest || canApprove ? <LeaveInboxCard alwaysShow={canApprove} /> : null}
      {canRead ? <StaffTodayCard /> : null}
    </div>
  );
}

/** Self check-in and check-out, once a day each, on the server's clock. */
export function CheckInCard() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const today = useApiData("staff-attendance:me", staffAttendanceApi.myToday);
  const [result, setResult] = useState<MyStaffDay | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const day = result ?? today.data;

  if (today.error && !day) {
    // Someone with leave.request but no staff record (404) simply has no card.
    if (today.error.status === 404) return null;
    return (
      <section className="card" data-testid="check-in-card">
        <ErrorState error={today.error} onRetry={today.reload} />
      </section>
    );
  }

  const act = async (kind: "in" | "out") => {
    if (busy) return;
    if (note.trim().length > 200) {
      setError(t("staffAttendance.v.note"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const next = kind === "in" ? await staffAttendanceApi.checkIn(note) : await staffAttendanceApi.checkOut(note);
      setResult(next);
      setNote("");
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
      today.reload();
      setResult(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="check-in-title" data-testid="check-in-card">
      <div className="flex items-center justify-between gap-2">
        <h2 id="check-in-title" className="text-[13px] font-medium text-ink-2 font-body">
          {t("staffAttendance.me.title")}
        </h2>
        <Clock size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {!day ? (
        <LoadingRows rows={2} />
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <p className="font-display text-lg font-bold leading-tight" data-testid="check-in-state">
              {day.onLeave
                ? t("staffAttendance.me.onLeave")
                : day.checkOutAt
                  ? t("staffAttendance.me.done")
                  : day.checkInAt
                    ? t("staffAttendance.me.in", { time: formatClock(day.checkInAt, locale) })
                    : t("staffAttendance.me.notYet")}
            </p>
            {day.status ? (
              <Pill tone={staffDayTone(day.status)} dot>
                {t(STAFF_DAY_LABEL[day.status])}
              </Pill>
            ) : null}
          </div>
          {day.checkInAt || day.checkOutAt ? (
            <dl className="kv text-[13.5px]" data-testid="check-in-times">
              {day.checkInAt ? (
                <>
                  <dt>{t("staffAttendance.checkedIn")}</dt>
                  <dd>
                    {formatClock(day.checkInAt, locale)}
                    {day.checkInNote ? ` · “${day.checkInNote}”` : ""}
                  </dd>
                </>
              ) : null}
              {day.checkOutAt ? (
                <>
                  <dt>{t("staffAttendance.checkedOut")}</dt>
                  <dd>
                    {formatClock(day.checkOutAt, locale)}
                    {day.checkOutNote ? ` · “${day.checkOutNote}”` : ""}
                  </dd>
                </>
              ) : null}
            </dl>
          ) : null}
          {!day.workingDay && !day.checkInAt ? (
            <p className="text-[13px] text-ink-3">{t("staffAttendance.me.offDay")}</p>
          ) : null}
          <FormAlert message={error} />
          {day.canCheckIn || day.canCheckOut ? (
            <form
              method="post"
              className="flex flex-col gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                void act(day.canCheckIn ? "in" : "out");
              }}
            >
              <label className="field">
                <span className="field-label">{t("staffAttendance.me.note")}</span>
                <input
                  className="input"
                  name="note"
                  value={note}
                  maxLength={200}
                  onChange={(e) => setNote(e.target.value)}
                  placeholder={t("staffAttendance.me.notePlaceholder")}
                  autoComplete="off"
                />
              </label>
              <button
                type="submit"
                className={day.canCheckIn ? "btn btn-primary self-start" : "btn self-start"}
                disabled={busy}
                data-testid={day.canCheckIn ? "check-in" : "check-out"}
              >
                {busy ? t("common.working") : day.canCheckIn ? t("staffAttendance.me.checkIn") : t("staffAttendance.me.checkOut")}
              </button>
              <p className="field-hint">{t("staffAttendance.me.serverTime")}</p>
            </form>
          ) : null}
        </>
      )}
    </section>
  );
}

/** Leave requests waiting for the caller (department heads and approvers). */
export function LeaveInboxCard({ alwaysShow }: { alwaysShow: boolean }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const inbox = useApiData("leave:inbox:dashboard", () => leaveApi.inbox());
  const list = inbox.data ?? [];
  if (!alwaysShow && list.length === 0) return null;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="leave-inbox-title" data-testid="leave-inbox-card">
      <div className="flex items-center justify-between gap-2">
        <h2 id="leave-inbox-title" className="text-[13px] font-medium text-ink-2 font-body">
          {t("leave.inbox.card.title")}
        </h2>
        <Inbox size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {inbox.error && !inbox.data ? (
        <ErrorState error={inbox.error} onRetry={inbox.reload} />
      ) : !inbox.data ? (
        <LoadingRows rows={2} />
      ) : (
        <>
          <span className="kpi-value" data-testid="leave-inbox-count">
            {list.length}
          </span>
          {list.length === 0 ? (
            <p className="text-[13.5px] text-ink-2">{t("leave.inbox.card.empty")}</p>
          ) : (
            <ul className="flex flex-col gap-1.5 text-[13.5px]">
              {list.slice(0, 3).map((r) => (
                <li key={r.id} className="min-w-0">
                  <b>{r.userName}</b>
                  <span className="text-ink-2">
                    {" · "}
                    {[
                      r.leaveTypeName,
                      dateRange(r.fromDate, r.toDate, (d) => formatPlainDate(d, locale)),
                      r.halfDay ? t("leave.halfDay") : formatDays(t, r.days),
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </li>
              ))}
              {list.length > 3 ? (
                <li className="text-ink-3">{plural(t, "leave.inbox.card.more", list.length - 3)}</li>
              ) : null}
            </ul>
          )}
          <Link href="/app/leave?tab=inbox" className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
            {t("leave.inbox.card.link")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </>
      )}
    </section>
  );
}

/** Admin and principal: how many staff are in today. */
export function StaffTodayCard() {
  const { t } = useI18n();
  const today = useApiData("staff-attendance:today", staffAttendanceApi.today);
  const data = today.data;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="staff-today-title" data-testid="staff-today-card">
      <div className="flex items-center justify-between gap-2">
        <h2 id="staff-today-title" className="text-[13px] font-medium text-ink-2 font-body">
          {t("staffAttendance.today.title")}
        </h2>
        <UserCheck size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {today.error && !data ? (
        <ErrorState error={today.error} onRetry={today.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : (
        <>
          <p className="flex items-baseline gap-2">
            <span className="kpi-value" data-testid="staff-present">
              {data.present + data.halfDay}
            </span>
            <span className="text-[13px] text-ink-3">{t("staffAttendance.today.of", { total: data.activeStaff })}</span>
          </p>
          <p className="text-[13px] text-ink-2">
            {[
              t("staffAttendance.today.onLeave", { count: data.onLeave }),
              t("staffAttendance.today.absent", { count: data.absent }),
              t("staffAttendance.today.notMarked", { count: data.notMarked }),
            ].join(" · ")}
          </p>
          <p className="text-[12.5px] font-semibold text-ink-3">
            {data.workingDay ? plural(t, "staffAttendance.today.checkedIn", data.checkedIn) : t("staffAttendance.offDay")}
          </p>
          <Link href="/app/staff-attendance" className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
            {t("staffAttendance.open")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </>
      )}
    </section>
  );
}
