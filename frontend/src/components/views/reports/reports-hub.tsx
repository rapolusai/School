"use client";

import {
  ArrowRight,
  Banknote,
  BookMarked,
  Bus,
  CalendarCheck2,
  CalendarRange,
  ChartColumnBig,
  ClipboardCheck,
  ClipboardList,
  FileSpreadsheet,
  FileClock,
  Filter,
  Handshake,
  History,
  Landmark,
  LibraryBig,
  ListChecks,
  NotebookPen,
  Receipt,
  ScanBarcode,
  TrendingUp,
  TriangleAlert,
  UserX,
  Wallet,
  type LucideIcon,
  BadgeCheck,
  IdCard,
} from "lucide-react";
import Link from "next/link";
import { AccessDenied } from "@/components/access";
import { PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { hasAnyPermission, hasPermission, PERMISSIONS, REPORT_PERMISSIONS } from "@/lib/permissions";

type PermissionSubject = Parameters<typeof hasPermission>[0];

export const REPORT_GROUPS = ["attendance", "fees", "admissions", "staff", "academics", "library", "transport", "administration"] as const;
export type ReportGroup = (typeof REPORT_GROUPS)[number];

export type ReportLink = {
  key: string;
  group: ReportGroup;
  href: string;
  title: MessageKey;
  description: MessageKey;
  permission: string;
  icon: LucideIcon;
  /** exports to Excel from the report page */
  excel?: boolean;
};

const GROUP_LABELS: Record<ReportGroup, MessageKey> = {
  attendance: "reports.group.attendance",
  fees: "reports.group.fees",
  admissions: "reports.group.admissions",
  staff: "reports.group.staff",
  academics: "reports.group.academics",
  library: "reports.group.library",
  transport: "reports.group.transport",
  administration: "reports.group.administration",
};

/** Every report, new and existing, with the permission that opens it. */
export const REPORT_LINKS: readonly ReportLink[] = [
  {
    key: "absentees",
    group: "attendance",
    href: "/app/reports/absentees",
    title: "reports.absentees.title",
    description: "reports.absentees.description",
    permission: PERMISSIONS.attendanceRead,
    icon: UserX,
    excel: true,
  },
  {
    key: "sections",
    group: "attendance",
    href: "/app/reports/attendance",
    title: "reports.sections.title",
    description: "reports.sections.description",
    permission: PERMISSIONS.attendanceRead,
    icon: CalendarCheck2,
    excel: true,
  },
  {
    key: "register",
    group: "attendance",
    href: "/app/attendance/reports",
    title: "reports.link.register",
    description: "reports.link.register.description",
    permission: PERMISSIONS.attendanceRead,
    icon: ClipboardCheck,
  },
  {
    key: "collection",
    group: "fees",
    href: "/app/fees",
    title: "reports.link.collection",
    description: "reports.link.collection.description",
    permission: PERMISSIONS.feesRead,
    icon: Wallet,
  },
  {
    key: "outstanding",
    group: "fees",
    href: "/app/fees/dues",
    title: "reports.link.outstanding",
    description: "reports.link.outstanding.description",
    permission: PERMISSIONS.feesRead,
    icon: FileSpreadsheet,
  },
  {
    key: "overdue",
    group: "fees",
    href: "/app/fees/dues?view=overdue",
    title: "reports.link.overdue",
    description: "reports.link.overdue.description",
    permission: PERMISSIONS.feesRead,
    icon: TriangleAlert,
  },
  {
    key: "receipts",
    group: "fees",
    href: "/app/fees/receipts",
    title: "reports.link.receipts",
    description: "reports.link.receipts.description",
    permission: PERMISSIONS.feesRead,
    icon: Receipt,
  },
  {
    key: "funnel",
    group: "admissions",
    href: "/app/reports/admissions",
    title: "reports.funnel.title",
    description: "reports.funnel.description",
    permission: PERMISSIONS.admissionsRead,
    icon: Filter,
    excel: true,
  },
  {
    key: "applications",
    group: "admissions",
    href: "/app/admissions",
    title: "reports.link.applications",
    description: "reports.link.applications.description",
    permission: PERMISSIONS.admissionsRead,
    icon: ClipboardList,
  },
  {
    key: "leave",
    group: "staff",
    href: "/app/reports/leave",
    title: "reports.leave.title",
    description: "reports.leave.description",
    permission: PERMISSIONS.staffRead,
    icon: CalendarCheck2,
    excel: true,
  },
  {
    key: "staffAttendance",
    group: "staff",
    href: "/app/staff-attendance?tab=month",
    title: "reports.link.staffAttendance",
    description: "reports.link.staffAttendance.description",
    permission: PERMISSIONS.staffRead,
    icon: ClipboardCheck,
  },
  {
    key: "payrollRegister",
    group: "staff",
    href: "/app/reports/payroll-register",
    title: "payroll.report.register.title",
    description: "payroll.report.register.description",
    permission: PERMISSIONS.payrollRead,
    icon: Banknote,
    excel: true,
  },
  {
    key: "payrollYtd",
    group: "staff",
    href: "/app/reports/payroll-ytd",
    title: "payroll.report.ytd.title",
    description: "payroll.report.ytd.description",
    permission: PERMISSIONS.payrollRead,
    icon: CalendarRange,
    excel: true,
  },
  {
    key: "payrollStatutory",
    group: "staff",
    href: "/app/reports/payroll-statutory",
    title: "payroll.report.statutory.title",
    description: "payroll.report.statutory.description",
    permission: PERMISSIONS.payrollRead,
    icon: Landmark,
    excel: true,
  },
  {
    key: "homework",
    group: "academics",
    href: "/app/reports/homework",
    title: "reports.homework.title",
    description: "reports.homework.description",
    permission: PERMISSIONS.homeworkManage,
    icon: NotebookPen,
    excel: true,
  },
  {
    key: "examAnalysis",
    group: "academics",
    href: "/app/reports/exam-analysis",
    title: "reports.examAnalysis.title",
    description: "reports.examAnalysis.description",
    permission: PERMISSIONS.examsRead,
    icon: ChartColumnBig,
    excel: true,
  },
  {
    key: "syllabusCoverage",
    group: "academics",
    href: "/app/reports/syllabus-coverage",
    title: "reports.syllabusCoverage.title",
    description: "reports.syllabusCoverage.description",
    permission: PERMISSIONS.lessonsRead,
    icon: ListChecks,
    excel: true,
  },
  {
    key: "clashes",
    group: "academics",
    href: "/app/timetable?tab=clashes",
    title: "reports.link.clashes",
    description: "reports.link.clashes.description",
    permission: PERMISSIONS.timetableManage,
    icon: TriangleAlert,
  },
  {
    key: "libraryOverdue",
    group: "library",
    href: "/app/reports/library-overdue",
    title: "library.report.overdue.title",
    description: "library.report.overdue.description",
    permission: PERMISSIONS.libraryManage,
    icon: BookMarked,
    excel: true,
  },
  {
    key: "libraryIssued",
    group: "library",
    href: "/app/reports/library-issued",
    title: "library.report.issued.title",
    description: "library.report.issued.description",
    permission: PERMISSIONS.libraryManage,
    icon: LibraryBig,
    excel: true,
  },
  {
    key: "libraryPopular",
    group: "library",
    href: "/app/reports/library-popular",
    title: "library.report.popular.title",
    description: "library.report.popular.description",
    permission: PERMISSIONS.libraryManage,
    icon: TrendingUp,
    excel: true,
  },
  {
    key: "libraryMember",
    group: "library",
    href: "/app/reports/library-member",
    title: "library.report.member.title",
    description: "library.report.member.description",
    permission: PERMISSIONS.libraryManage,
    icon: History,
    excel: true,
  },
  {
    key: "libraryStock",
    group: "library",
    href: "/app/library/stock",
    title: "library.report.stock.title",
    description: "library.report.stock.description",
    permission: PERMISSIONS.libraryManage,
    icon: ScanBarcode,
    excel: true,
  },
  {
    key: "ptmAttendance",
    group: "academics",
    href: "/app/reports/ptm",
    title: "ptm.report.title",
    description: "ptm.report.description",
    permission: PERMISSIONS.ptmManage,
    icon: Handshake,
  },
  {
    key: "transportRoster",
    group: "transport",
    href: "/app/reports/transport-route-students",
    title: "transport.report.roster.title",
    description: "transport.report.roster.description",
    permission: PERMISSIONS.transportRead,
    icon: Bus,
    excel: true,
  },
  {
    key: "transportDocuments",
    group: "transport",
    href: "/app/reports/transport-documents",
    title: "transport.report.docs.title",
    description: "transport.report.docs.description",
    permission: PERMISSIONS.transportRead,
    icon: FileClock,
    excel: true,
  },
  {
    key: "transportFees",
    group: "transport",
    href: "/app/reports/transport-fees",
    title: "transport.report.fees.title",
    description: "transport.report.fees.description",
    permission: PERMISSIONS.transportRead,
    icon: Wallet,
    excel: true,
  },
  {
    key: "transportBoarding",
    group: "transport",
    href: "/app/reports/transport-boarding",
    title: "transport.report.boarding.title",
    description: "transport.report.boarding.description",
    permission: PERMISSIONS.transportRead,
    icon: ClipboardCheck,
    excel: true,
  },
  {
    key: "certificatesIssued",
    group: "administration",
    href: "/app/reports/certificates-issued",
    title: "certificates.report.issued.title",
    description: "certificates.report.issued.description",
    permission: PERMISSIONS.certificatesRead,
    icon: BadgeCheck,
    excel: true,
  },
  {
    key: "certificatesPending",
    group: "administration",
    href: "/app/reports/certificates-pending",
    title: "certificates.report.pending.title",
    description: "certificates.report.pending.description",
    permission: PERMISSIONS.certificatesRead,
    icon: FileClock,
    excel: true,
  },
  {
    key: "idCardsPending",
    group: "administration",
    href: "/app/reports/id-cards-pending",
    title: "certificates.report.cards.title",
    description: "certificates.report.cards.description",
    permission: PERMISSIONS.certificatesRead,
    icon: IdCard,
    excel: true,
  },
];

/** The reports a person may open, grouped in hub order; empty groups are left out. Exported for tests. */
export function reportGroupsFor(me: PermissionSubject): { group: ReportGroup; links: ReportLink[] }[] {
  return REPORT_GROUPS.map((group) => ({
    group,
    links: REPORT_LINKS.filter((link) => link.group === group && hasPermission(me, link.permission)),
  })).filter((entry) => entry.links.length > 0);
}

/** /app/reports: every report the signed-in person may open, by area. */
export function ReportsHubView() {
  const { t } = useI18n();
  const { me } = useAuth();
  if (!hasAnyPermission(me, REPORT_PERMISSIONS)) return <AccessDenied />;
  const groups = reportGroupsFor(me);
  return (
    <>
      <PageHead eyebrow={me?.tenant?.name} title={t("reports.hub.title")} />
      <p className="-mt-2 max-w-2xl text-ink-2">{t("reports.hub.intro")}</p>
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2" data-testid="reports-hub">
        {groups.map(({ group, links }) => (
          <section key={group} className="card" aria-labelledby={`reports-${group}`} data-testid={`reports-group-${group}`}>
            <div className="card-head">
              <h2 id={`reports-${group}`}>{t(GROUP_LABELS[group])}</h2>
            </div>
            <ul className="rowcards">
              {links.map((link) => {
                const Icon = link.icon;
                return (
                  <li key={link.key}>
                    <Link href={link.href} className="rowcard" data-testid={`report-link-${link.key}`}>
                      <span className="badge-ic">
                        <Icon size={18} aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <b className="block font-semibold">{t(link.title)}</b>
                        <span className="block text-[13px] text-ink-3">{t(link.description)}</span>
                      </span>
                      {link.excel ? (
                        <span className="pill pill-neutral hidden sm:inline-flex">{t("reports.hub.excel")}</span>
                      ) : null}
                      <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
                    </Link>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
      </div>
    </>
  );
}
