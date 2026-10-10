import {
  Activity,
  Building2,
  CalendarDays,
  CalendarOff,
  ClipboardList,
  ClipboardCheck,
  GraduationCap,
  IdCard,
  LayoutDashboard,
  Megaphone,
  LockKeyhole,
  MessageSquareText,
  Newspaper,
  NotebookPen,
  ReceiptIndianRupee,
  School,
  ScrollText,
  ShieldCheck,
  UserCheck,
  UserLock,
  Users,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import type { MessageKey } from "./i18n/en";
import type { Me } from "./types";

/** Permission codes from docs/api/phase-0.md, phase-1.md, phase-1-attendance.md, phase-1-staff.md,
 * phase-1-communication.md, phase-1-timetable-homework.md and phase-1-billing.md. */
export const PERMISSIONS = {
  dashboardView: "dashboard.view",
  usersRead: "users.read",
  usersManage: "users.manage",
  rolesRead: "roles.read",
  auditRead: "audit.read",
  settingsManage: "settings.manage",
  academicsRead: "academics.read",
  studentsRead: "students.read",
  studentsManage: "students.manage",
  childView: "child.view",
  platformAdmin: "platform.admin",
  admissionsRead: "admissions.read",
  admissionsManage: "admissions.manage",
  attendanceRead: "attendance.read",
  attendanceMark: "attendance.mark",
  attendanceManage: "attendance.manage",
  messagesRead: "messages.read",
  feesRead: "fees.read",
  feesCollect: "fees.collect",
  feesManage: "fees.manage",
  staffRead: "staff.read",
  staffManage: "staff.manage",
  leaveRequest: "leave.request",
  leaveApprove: "leave.approve",
  staffAttendanceManage: "staff_attendance.manage",
  noticesSend: "notices.send",
  noticesRead: "notices.read",
  noticesApprove: "notices.approve",
  calendarManage: "calendar.manage",
  timetableRead: "timetable.read",
  timetableManage: "timetable.manage",
  homeworkManage: "homework.manage",
  billingRead: "billing.read",
  privacyManage: "privacy.manage",
} as const;

export type NavGroup = "overview" | "academics" | "finance" | "staff" | "communication" | "administration" | "platform";

export type NavItem = {
  key: string;
  href: string;
  labelKey: MessageKey;
  /** Short label for the phone bottom bar. */
  shortLabelKey: MessageKey;
  icon: LucideIcon;
  permission: string;
  group: NavGroup;
  /** Shown only to parents (child.view without staff access), not to staff who also hold child.view. */
  parentOnly?: boolean;
};

/** Every navigation item, in display order. */
export const NAV_ITEMS: readonly NavItem[] = [
  {
    key: "dashboard",
    href: "/app/dashboard",
    labelKey: "nav.dashboard",
    shortLabelKey: "nav.dashboard.short",
    icon: LayoutDashboard,
    permission: PERMISSIONS.dashboardView,
    group: "overview",
  },
  {
    key: "attendance",
    href: "/app/attendance",
    labelKey: "nav.attendance",
    shortLabelKey: "nav.attendance.short",
    icon: ClipboardCheck,
    permission: PERMISSIONS.attendanceRead,
    group: "academics",
  },
  {
    key: "timetable",
    href: "/app/timetable",
    labelKey: "nav.timetable",
    shortLabelKey: "nav.timetable.short",
    icon: CalendarDays,
    permission: PERMISSIONS.timetableRead,
    group: "academics",
  },
  {
    key: "homework",
    href: "/app/homework",
    labelKey: "nav.homework",
    shortLabelKey: "nav.homework.short",
    icon: NotebookPen,
    permission: PERMISSIONS.homeworkManage,
    group: "academics",
  },
  {
    key: "students",
    href: "/app/students",
    labelKey: "nav.students",
    shortLabelKey: "nav.students.short",
    icon: GraduationCap,
    permission: PERMISSIONS.studentsRead,
    group: "academics",
  },
  {
    key: "admissions",
    href: "/app/admissions",
    labelKey: "nav.admissions",
    shortLabelKey: "nav.admissions.short",
    icon: ClipboardList,
    permission: PERMISSIONS.admissionsRead,
    group: "academics",
  },
  {
    key: "setup",
    href: "/app/setup",
    labelKey: "nav.setup",
    shortLabelKey: "nav.setup.short",
    icon: School,
    permission: PERMISSIONS.academicsRead,
    group: "academics",
  },
  {
    key: "fees",
    href: "/app/fees",
    labelKey: "nav.fees",
    shortLabelKey: "nav.fees.short",
    icon: Wallet,
    permission: PERMISSIONS.feesRead,
    group: "finance",
  },
  {
    key: "billing",
    href: "/app/billing",
    labelKey: "nav.billing",
    shortLabelKey: "nav.billing.short",
    icon: ReceiptIndianRupee,
    permission: PERMISSIONS.billingRead,
    group: "finance",
  },
  {
    key: "staff",
    href: "/app/staff",
    labelKey: "nav.staff",
    shortLabelKey: "nav.staff.short",
    icon: IdCard,
    permission: PERMISSIONS.staffRead,
    group: "staff",
  },
  {
    key: "staff-attendance",
    href: "/app/staff-attendance",
    labelKey: "nav.staffAttendance",
    shortLabelKey: "nav.staffAttendance.short",
    icon: UserCheck,
    permission: PERMISSIONS.staffRead,
    group: "staff",
  },
  {
    key: "leave",
    href: "/app/leave",
    labelKey: "nav.leave",
    shortLabelKey: "nav.leave.short",
    icon: CalendarOff,
    permission: PERMISSIONS.leaveRequest,
    group: "staff",
  },
  {
    key: "users",
    href: "/app/users",
    labelKey: "nav.users",
    shortLabelKey: "nav.users.short",
    icon: Users,
    permission: PERMISSIONS.usersRead,
    group: "administration",
  },
  {
    key: "roles",
    href: "/app/roles",
    labelKey: "nav.roles",
    shortLabelKey: "nav.roles.short",
    icon: ShieldCheck,
    permission: PERMISSIONS.rolesRead,
    group: "administration",
  },
  {
    key: "audit",
    href: "/app/audit",
    labelKey: "nav.audit",
    shortLabelKey: "nav.audit.short",
    icon: ScrollText,
    permission: PERMISSIONS.auditRead,
    group: "administration",
  },
  {
    key: "messages",
    href: "/app/messages",
    labelKey: "nav.messages",
    shortLabelKey: "nav.messages.short",
    icon: MessageSquareText,
    permission: PERMISSIONS.messagesRead,
    group: "administration",
  },
  {
    key: "board",
    href: "/app/board",
    labelKey: "nav.board",
    shortLabelKey: "nav.board.short",
    icon: Newspaper,
    permission: PERMISSIONS.noticesRead,
    group: "communication",
  },
  {
    key: "notices",
    href: "/app/notices",
    labelKey: "nav.notices",
    shortLabelKey: "nav.notices.short",
    icon: Megaphone,
    permission: PERMISSIONS.noticesSend,
    group: "communication",
  },
  {
    key: "calendar",
    href: "/app/calendar",
    labelKey: "nav.calendar",
    shortLabelKey: "nav.calendar.short",
    icon: CalendarDays,
    permission: PERMISSIONS.noticesRead,
    group: "communication",
  },
  {
    key: "privacy",
    href: "/app/privacy",
    labelKey: "nav.privacy",
    shortLabelKey: "nav.privacy.short",
    icon: LockKeyhole,
    permission: PERMISSIONS.privacyManage,
    group: "administration",
  },
  {
    key: "myPrivacy",
    href: "/app/my-privacy",
    labelKey: "nav.myPrivacy",
    shortLabelKey: "nav.myPrivacy.short",
    icon: UserLock,
    permission: PERMISSIONS.childView,
    group: "overview",
    parentOnly: true,
  },
  {
    key: "schools",
    href: "/app/platform/schools",
    labelKey: "nav.schools",
    shortLabelKey: "nav.schools.short",
    icon: Building2,
    permission: PERMISSIONS.platformAdmin,
    group: "platform",
  },
  {
    key: "platformBilling",
    href: "/app/platform/billing",
    labelKey: "nav.platformBilling",
    shortLabelKey: "nav.platformBilling.short",
    icon: ReceiptIndianRupee,
    permission: PERMISSIONS.platformAdmin,
    group: "platform",
  },
  {
    key: "platformHealth",
    href: "/app/platform/health",
    labelKey: "nav.platformHealth",
    shortLabelKey: "nav.platformHealth.short",
    icon: Activity,
    permission: PERMISSIONS.platformAdmin,
    group: "platform",
  },
];

type PermissionSubject = Pick<Me, "permissions" | "platformAdmin"> | null | undefined;
/** Navigation also looks at roles, to tell parents from staff. */
type NavSubject = (Pick<Me, "permissions" | "platformAdmin"> & { roles?: string[] }) | null | undefined;

export function hasPermission(me: PermissionSubject, permission: string): boolean {
  if (!me) return false;
  if (permission === PERMISSIONS.platformAdmin && me.platformAdmin) return true;
  return me.permissions.includes(permission);
}

/**
 * A parent's view of the app: child.view, and either the PARENT role or no staff access to
 * students (a School Admin also holds child.view). Same rule as the dashboard.
 */
export function isParentUser(me: NavSubject): boolean {
  if (!me) return false;
  return (
    hasPermission(me, PERMISSIONS.childView) &&
    (Boolean(me.roles?.includes("PARENT")) || !hasPermission(me, PERMISSIONS.studentsRead))
  );
}

/** The navigation items this user may see, in display order. Pure. */
export function navFor(me: NavSubject): NavItem[] {
  return NAV_ITEMS.filter((item) => hasPermission(me, item.permission) && (!item.parentOnly || isParentUser(me)));
}

/** Where a user lands after sign-in (or when opening /app). */
export function landingPath(me: PermissionSubject): string {
  if (!me) return "/login";
  if (hasPermission(me, PERMISSIONS.platformAdmin)) return "/app/platform/schools";
  return "/app/dashboard";
}

/** The nav item that owns a pathname (longest matching href), if any. */
export function navItemForPath(pathname: string): NavItem | undefined {
  return [...NAV_ITEMS]
    .sort((a, b) => b.href.length - a.href.length)
    .find((item) => pathname === item.href || pathname.startsWith(`${item.href}/`));
}

/** Only allow post-login redirects back into the app (prevents open redirects). */
export function safeNextPath(next: string | null | undefined): string | null {
  if (!next) return null;
  if (!/^\/app(?:[/?#]|$)/.test(next) || next.includes("\\")) return null;
  return next;
}
