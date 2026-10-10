"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n, type Translate } from "@/lib/i18n";
import { hasAnyPermission, hasPermission, PERMISSIONS, REPORT_PERMISSIONS } from "@/lib/permissions";
import { reportsApi } from "@/lib/reports-api";
import type { DashboardSummary, Me } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { formatPercent } from "../attendance/attendance-shared";
import { BarChart, compactRupees, FunnelBars, funnelRows, LineChart } from "./charts";

/* The staff dashboard: KPI tiles and charts from GET /api/dashboard, arranged for the person's role. */

export type DashboardLayout = "leadership" | "teacher" | "accountant" | "frontOffice" | "staff";

const NOT_STAFF = new Set(["PARENT", "STUDENT"]);

/** A school staff member: any role other than parent or student. */
export function isStaffMember(me: Pick<Me, "roles" | "platformAdmin"> | null | undefined): boolean {
  if (!me || me.platformAdmin) return false;
  return me.roles.some((role) => !NOT_STAFF.has(role));
}

/** Which arrangement of tiles and charts suits the person's roles (the most senior role wins). */
export function dashboardLayout(roles: readonly string[]): DashboardLayout {
  if (roles.includes("SCHOOL_ADMIN") || roles.includes("PRINCIPAL")) return "leadership";
  if (roles.includes("TEACHER")) return "teacher";
  if (roles.includes("ACCOUNTANT")) return "accountant";
  if (roles.includes("FRONT_OFFICE")) return "frontOffice";
  return "staff";
}

export type TileKey =
  | "sectionsToMark"
  | "attendanceToday"
  | "absentToday"
  | "students"
  | "feesToday"
  | "feesMonth"
  | "outstanding"
  | "overdue"
  | "staffPresent"
  | "leaveWaiting"
  | "enquiries"
  | "followUps"
  | "offers"
  | "circulars"
  | "homework"
  | "substitutions"
  | "clashes";

const EVERY_TILE: TileKey[] = [
  "sectionsToMark",
  "attendanceToday",
  "absentToday",
  "students",
  "feesToday",
  "feesMonth",
  "outstanding",
  "overdue",
  "staffPresent",
  "leaveWaiting",
  "enquiries",
  "followUps",
  "offers",
  "circulars",
  "homework",
  "substitutions",
  "clashes",
];

/** The tiles of each layout, in order. A tile shows only when its card came back (the person holds its permission). */
export const LAYOUT_TILES: Record<DashboardLayout, TileKey[]> = {
  leadership: [
    "attendanceToday",
    "absentToday",
    "students",
    "feesToday",
    "outstanding",
    "staffPresent",
    "leaveWaiting",
    "enquiries",
    "circulars",
    "homework",
    "clashes",
  ],
  teacher: ["sectionsToMark", "attendanceToday", "homework", "substitutions", "leaveWaiting"],
  accountant: ["feesToday", "feesMonth", "outstanding", "overdue", "students"],
  frontOffice: ["enquiries", "followUps", "offers", "students"],
  staff: EVERY_TILE,
};

export type ChartKey = "trend" | "collections" | "funnel" | "circulars" | "followUps";

export const LAYOUT_CHARTS: Record<DashboardLayout, ChartKey[]> = {
  leadership: ["trend", "collections", "funnel", "circulars", "followUps"],
  teacher: ["trend"],
  accountant: ["collections"],
  frontOffice: ["funnel", "followUps"],
  staff: ["trend", "collections", "funnel", "circulars", "followUps"],
};

export type Tile = {
  key: TileKey;
  label: string;
  value: string;
  sub?: string;
  href?: string;
  /** something is waiting for someone */
  attention?: boolean;
};

/**
 * The KPI tiles for a summary and layout, in order, leaving out those whose card is missing. `can` decides
 * whether a tile links to its screen. Pure; exported for tests.
 */
export function tilesFor(
  summary: DashboardSummary,
  layout: DashboardLayout,
  t: Translate,
  can: (permission: string) => boolean,
): Tile[] {
  const a = summary.attendance;
  const f = summary.fees;
  const ad = summary.admissions;
  const holidaySub = a?.holiday ? t("reports.tile.holiday", { name: a.holiday }) : undefined;

  const build: Record<TileKey, () => Tile | null> = {
    sectionsToMark: () => {
      if (!a || a.wholeSchool || a.ownSections.length === 0) return null;
      const toMark = a.ownSections.filter((s) => s.canMark && !s.marked).length;
      const marked = a.ownSections.filter((s) => s.marked).length;
      return {
        key: "sectionsToMark",
        label: t("reports.tile.sectionsToMark"),
        value: String(a.holiday ? 0 : toMark),
        sub: holidaySub ?? t("reports.tile.sectionsMarked", { marked, total: a.ownSections.length }),
        href: "/app/attendance",
        attention: !a.holiday && toMark > 0,
      };
    },
    attendanceToday: () => {
      if (!a || (!a.wholeSchool && a.sectionCount === 0)) return null;
      return {
        key: "attendanceToday",
        label: a.wholeSchool ? t("reports.tile.attendanceToday") : t("reports.tile.myAttendanceToday"),
        value: a.holiday ? "—" : formatPercent(a.presentPercent),
        sub:
          holidaySub ??
          (a.sectionCount === 0
            ? t("reports.tile.noSections")
            : t("reports.tile.sectionsMarked", { marked: a.sectionsMarked, total: a.sectionCount })),
        href: "/app/attendance",
        attention: !a.holiday && a.sectionsMarked < a.sectionCount,
      };
    },
    absentToday: () => {
      if (!a || !a.wholeSchool) return null;
      return {
        key: "absentToday",
        label: t("reports.tile.absentToday"),
        value: String(a.counts.absent),
        sub: holidaySub ?? plural(t, "reports.tile.onLeave", a.counts.leave),
        href: can(PERMISSIONS.attendanceRead) ? "/app/reports/absentees" : undefined,
      };
    },
    students: () =>
      summary.students
        ? {
            key: "students",
            label: t("reports.tile.students"),
            value: String(summary.students.onRoll),
            sub: plural(t, "reports.tile.admittedThisMonth", summary.students.admittedThisMonth),
            href: can(PERMISSIONS.studentsRead) ? "/app/students" : undefined,
          }
        : null,
    feesToday: () =>
      f
        ? {
            key: "feesToday",
            label: t("reports.tile.feesToday"),
            value: formatPaise(f.today.amountPaise),
            sub: plural(t, "reports.tile.receipts", f.today.receiptCount),
            href: "/app/fees",
          }
        : null,
    feesMonth: () =>
      f
        ? {
            key: "feesMonth",
            label: t("reports.tile.feesMonth"),
            value: formatPaise(f.thisMonth.amountPaise),
            sub: plural(t, "reports.tile.receipts", f.thisMonth.receiptCount),
            href: "/app/fees/receipts",
          }
        : null,
    outstanding: () =>
      f
        ? {
            key: "outstanding",
            label: t("reports.tile.outstanding"),
            value: formatPaise(f.outstandingPaise),
            sub: t("reports.tile.overdueAmount", { amount: formatPaise(f.overduePaise) }),
            href: "/app/fees/dues",
          }
        : null,
    overdue: () =>
      f
        ? {
            key: "overdue",
            label: t("reports.tile.overdue"),
            value: formatPaise(f.overduePaise),
            sub: plural(t, "reports.tile.studentCount", f.overdueStudents),
            href: "/app/fees/dues?view=overdue",
            attention: f.overduePaise > 0,
          }
        : null,
    staffPresent: () => {
      const s = summary.staff;
      if (!s) return null;
      return {
        key: "staffPresent",
        label: t("reports.tile.staffPresent"),
        value: s.workingDay ? String(s.present + s.halfDay) : "—",
        sub: s.workingDay
          ? t("reports.tile.staffSub", { total: s.activeStaff, leave: s.onLeave })
          : t("reports.tile.notWorkingDay"),
        href: can(PERMISSIONS.staffRead) ? "/app/staff-attendance" : undefined,
      };
    },
    leaveWaiting: () => {
      const l = summary.leave;
      if (!l || (!can(PERMISSIONS.leaveApprove) && l.waitingForMe === 0)) return null;
      return {
        key: "leaveWaiting",
        label: t("reports.tile.leaveWaiting"),
        value: String(l.waitingForMe),
        sub: t("reports.tile.leaveSub"),
        href: "/app/leave?tab=inbox",
        attention: l.waitingForMe > 0,
      };
    },
    enquiries: () =>
      ad
        ? {
            key: "enquiries",
            label: t("reports.tile.enquiries"),
            value: String(ad.openEnquiries),
            sub: t("reports.tile.inProgress", { count: ad.inProgress }),
            href: "/app/admissions",
          }
        : null,
    followUps: () =>
      ad
        ? {
            key: "followUps",
            label: t("reports.tile.followUps"),
            value: String(ad.followUps.dueToday + ad.followUps.overdue),
            sub: t("reports.tile.followUpsSub", { overdue: ad.followUps.overdue, upcoming: ad.followUps.upcoming }),
            href: "/app/admissions",
            attention: ad.followUps.overdue > 0,
          }
        : null,
    offers: () =>
      ad
        ? {
            key: "offers",
            label: t("reports.tile.offers"),
            value: String(ad.offersPending),
            sub: plural(t, "reports.tile.admittedThisYear", ad.admittedThisYear),
            href: "/app/admissions",
          }
        : null,
    circulars: () =>
      summary.circulars
        ? {
            key: "circulars",
            label: t("reports.tile.circulars"),
            value: String(summary.circulars.pendingApproval),
            sub: t("reports.tile.circularsSub"),
            href: can(PERMISSIONS.noticesSend) ? "/app/notices" : undefined,
            attention: summary.circulars.pendingApproval > 0,
          }
        : null,
    homework: () =>
      summary.homework
        ? {
            key: "homework",
            label: t("reports.tile.homework"),
            value: String(summary.homework.waitingForReview),
            sub: t("reports.tile.homeworkSub"),
            href: "/app/homework",
            attention: summary.homework.waitingForReview > 0,
          }
        : null,
    substitutions: () => {
      const d = summary.myDay;
      if (!d) return null;
      return {
        key: "substitutions",
        label: t("reports.tile.substitutions"),
        value: String(d.substitutions.length),
        sub: !d.workingDay
          ? t("reports.tile.notWorkingDay")
          : d.absent
            ? t("reports.tile.youAreAway")
            : plural(t, "reports.tile.classesToday", d.classes),
        href: "/app/timetable",
        attention: d.substitutions.length > 0,
      };
    },
    clashes: () => {
      const tt = summary.timetable;
      if (!tt) return null;
      return {
        key: "clashes",
        label: t("reports.tile.clashes"),
        value: String(tt.clashes),
        sub:
          tt.periodsToCover > 0
            ? t("reports.tile.coverSub", { covered: tt.periodsCovered, total: tt.periodsToCover })
            : plural(t, "reports.tile.warnings", tt.warnings),
        href: "/app/timetable?tab=clashes",
        attention: tt.clashes > 0 || tt.periodsCovered < tt.periodsToCover,
      };
    },
  };

  return LAYOUT_TILES[layout].map((key) => build[key]()).filter((tile): tile is Tile => tile !== null);
}

/** Which chart cards to show: the layout's, in order, when their data came back. Pure; exported for tests. */
export function chartsFor(summary: DashboardSummary, layout: DashboardLayout): ChartKey[] {
  const a = summary.attendance;
  const present: Record<ChartKey, boolean> = {
    trend: Boolean(a && (a.wholeSchool || a.ownSections.length > 0)),
    collections: Boolean(summary.fees),
    funnel: Boolean(summary.admissions),
    circulars: Boolean(summary.circulars && summary.circulars.items.length > 0),
    followUps: Boolean(summary.admissions && summary.admissions.followUps.items.length > 0),
  };
  return LAYOUT_CHARTS[layout].filter((key) => present[key]);
}

/** Day of the month for the collections axis: 1, 5, 10, 15… Exported for tests. */
export function dayTick(date: string): string | undefined {
  const day = Number(date.slice(8, 10));
  return day === 1 || day % 5 === 0 ? String(day) : undefined;
}

export function StaffDashboard() {
  const { me } = useAuth();
  const summary = useApiData(me ? "dashboard:summary" : null, reportsApi.dashboard);
  if (!me) return null;

  if (summary.error && !summary.data) {
    return (
      <section className="card" data-testid="staff-dashboard">
        <ErrorState error={summary.error} onRetry={summary.reload} />
      </section>
    );
  }
  if (!summary.data) {
    return (
      <section className="card" data-testid="staff-dashboard" aria-busy="true">
        <LoadingRows rows={2} />
      </section>
    );
  }
  return <StaffDashboardBody me={me} summary={summary.data} />;
}

function StaffDashboardBody({ me, summary }: { me: Me; summary: DashboardSummary }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const layout = dashboardLayout(me.roles);
  const can = (permission: string) => hasPermission(me, permission);
  const tiles = tilesFor(summary, layout, t, can);
  const charts = chartsFor(summary, layout);
  if (tiles.length === 0 && charts.length === 0) return null;

  return (
    <section className="flex flex-col gap-3.5" aria-labelledby="staff-dashboard-title" data-testid="staff-dashboard" data-layout={layout}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="staff-dashboard-title">
          {t("reports.dash.title")}
          {summary.academicYearName ? (
            <span className="ml-2 text-[13px] font-medium text-ink-3">{summary.academicYearName}</span>
          ) : null}
        </h2>
        {hasAnyPermission(me, REPORT_PERMISSIONS) ? (
          <Link href="/app/reports" className="link inline-flex items-center gap-1 text-[13.5px]">
            {t("reports.dash.allReports")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        ) : null}
      </div>

      {tiles.length > 0 ? (
        <ul className="rep-kpis" data-testid="dashboard-tiles">
          {tiles.map((tile) => (
            <li key={tile.key} className={`rep-kpi${tile.attention ? " is-attention" : ""}`} data-testid={`tile-${tile.key}`}>
              {tile.href ? (
                <Link href={tile.href} className="rep-kpi-label rep-kpi-link">
                  {tile.label}
                </Link>
              ) : (
                <span className="rep-kpi-label">{tile.label}</span>
              )}
              <span className="kpi-value">{tile.value}</span>
              {tile.sub ? <span className="rep-kpi-sub">{tile.sub}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}

      {charts.length > 0 ? (
        <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-2">
          {charts.map((key) => (
            <ChartCard key={key} chart={key} summary={summary} me={me} locale={locale} t={t} />
          ))}
        </div>
      ) : null}
    </section>
  );
}

function ChartCard({
  chart,
  summary,
  me,
  locale,
  t,
}: {
  chart: ChartKey;
  summary: DashboardSummary;
  me: Me;
  locale: string;
  t: Translate;
}) {
  const a = summary.attendance;
  const f = summary.fees;
  const ad = summary.admissions;

  if (chart === "trend" && a) {
    const title = a.wholeSchool ? t("reports.dash.trend") : t("reports.dash.trendMine");
    const marked = a.trend.some((p) => p.presentPercent !== null);
    return (
      <section className="card" data-testid="chart-trend">
        <div className="card-head">
          <h2>{title}</h2>
          {a.month ? (
            <span className="text-[13px] text-ink-3">
              {t("reports.dash.thisMonth", { percent: formatPercent(a.month.presentPercent) })}
            </span>
          ) : null}
        </div>
        {marked ? (
          <LineChart
            points={a.trend.map((p) => ({ key: p.date, label: formatPlainDate(p.date, locale), value: p.presentPercent }))}
            caption={title}
            head={[t("reports.col.date"), t("reports.col.percent")]}
            formatValue={formatPercent}
          />
        ) : (
          <p className="empty">{t("reports.dash.trendEmpty")}</p>
        )}
      </section>
    );
  }

  if (chart === "collections" && f) {
    const title = t("reports.dash.collections");
    const any = f.byDay.some((d) => d.amountPaise > 0);
    return (
      <section className="card" data-testid="chart-collections">
        <div className="card-head">
          <h2>{title}</h2>
          <span className="text-[13px] text-ink-3">
            {t("reports.dash.monthTotal", { amount: formatPaise(f.thisMonth.amountPaise) })}
          </span>
        </div>
        {any ? (
          <BarChart
            bars={f.byDay.map((d) => ({
              key: d.date,
              label: formatPlainDate(d.date, locale),
              tick: dayTick(d.date),
              value: d.amountPaise,
              highlight: d.date === summary.date,
            }))}
            caption={title}
            head={[t("reports.col.date"), t("reports.col.amount")]}
            formatValue={formatPaise}
            formatAxis={compactRupees}
          />
        ) : (
          <p className="empty">{t("reports.dash.collectionsEmpty")}</p>
        )}
      </section>
    );
  }

  if (chart === "funnel" && ad) {
    const title = t("reports.dash.funnel");
    const any = ad.funnel.some((s) => s.reached > 0);
    return (
      <section className="card" data-testid="chart-funnel">
        <div className="card-head">
          <h2>{title}</h2>
          {hasPermission(me, PERMISSIONS.admissionsRead) ? (
            <Link href="/app/reports/admissions" className="link text-[13.5px]">
              {t("reports.dash.funnelReport")}
            </Link>
          ) : null}
        </div>
        {any ? (
          <FunnelBars rows={funnelRows(ad.funnel, t)} caption={title} />
        ) : (
          <p className="empty">{t("reports.dash.funnelEmpty")}</p>
        )}
      </section>
    );
  }

  if (chart === "circulars" && summary.circulars) {
    return (
      <section className="card" data-testid="circulars-waiting">
        <div className="card-head">
          <h2>{t("reports.dash.circulars")}</h2>
          <Pill tone="warn">{summary.circulars.pendingApproval}</Pill>
        </div>
        <ul className="list">
          {summary.circulars.items.map((item) => (
            <li key={item.id} className="li">
              <div className="min-w-0 flex-1">
                <Link href={`/app/notices/${item.id}`} className="link break-words font-semibold">
                  {item.title}
                </Link>
                <p className="text-[13px] text-ink-3">
                  {[item.createdByName, item.submittedAt ? formatDateTime(item.submittedAt, locale) : null]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
              </div>
            </li>
          ))}
        </ul>
      </section>
    );
  }

  if (chart === "followUps" && ad) {
    const fu = ad.followUps;
    return (
      <section className="card" data-testid="follow-ups">
        <div className="card-head">
          <h2>{t("reports.dash.followUps")}</h2>
          <span className="text-[13px] text-ink-3">
            {t("reports.tile.followUpsSub", { overdue: fu.overdue, upcoming: fu.upcoming })}
          </span>
        </div>
        <ul className="list">
          {fu.items.map((item) => {
            const overdue = item.followUpOn < fu.date;
            return (
              <li key={item.id} className="li items-center">
                <div className="min-w-0 flex-1">
                  <Link href={`/app/admissions/${item.id}`} className="link break-words font-semibold">
                    {item.childName}
                  </Link>
                  <p className="text-[13px] text-ink-3">
                    {[item.className, translateOr(t, `admissions.stage.${item.stage}`, item.stage)]
                      .filter(Boolean)
                      .join(" · ")}
                  </p>
                </div>
                <Pill tone={overdue ? "bad" : item.followUpOn === fu.date ? "warn" : "neutral"}>
                  {formatPlainDate(item.followUpOn, locale)}
                </Pill>
              </li>
            );
          })}
        </ul>
      </section>
    );
  }

  return null;
}
