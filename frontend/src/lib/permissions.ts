import {
  Activity,
  Award,
  BadgeCheck,
  BarChart3,
  BookOpenCheck,
  BookOpenText,
  Building2,
  BedDouble,
  Bus,
  Calculator,
  CalendarDays,
  CalendarOff,
  CarFront,
  ClipboardList,
  ClipboardCheck,
  DoorOpen,
  GraduationCap,
  Handshake,
  HeartPulse,
  House,
  IdCard,
  KeyRound,
  Landmark,
  LayoutDashboard,
  LibraryBig,
  Lightbulb,
  Megaphone,
  LockKeyhole,
  MessageSquareText,
  MessagesSquare,
  Navigation,
  Newspaper,
  Package,
  NotebookPen,
  ReceiptIndianRupee,
  ReceiptText,
  School,
  ScrollText,
  ShieldCheck,
  Sparkles,
  UserCheck,
  UserLock,
  Users,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import { isFamilyMember } from "./family";
import type { MessageKey } from "./i18n/en";
import type { Me, PlanModule } from "./types";

/** Permission codes from docs/api/phase-0.md, phase-1.md, phase-1-attendance.md, phase-1-staff.md,
 * phase-1-communication.md, phase-1-timetable-homework.md, phase-1-billing.md, phase-2-library.md,
 * phase-2-payroll.md, phase-2-exams.md, phase-2-chat.md, phase-2-lessons.md phase-2-transport.md, phase-2-certificates.md, phase-2-frontoffice.md and phase-3-health.md. */
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
  libraryRead: "library.read",
  libraryManage: "library.manage",
  payrollRead: "payroll.read",
  payrollManage: "payroll.manage",
  payrollApprove: "payroll.approve",
  examsRead: "exams.read",
  examsManage: "exams.manage",
  examsSetup: "exams.setup",
  examsPublish: "exams.publish",
  chatUse: "chat.use",
  chatOversee: "chat.oversee",
  ptmManage: "ptm.manage",
  lessonsRead: "lessons.read",
  lessonsManage: "lessons.manage",
  lessonsApprove: "lessons.approve",
  transportRead: "transport.read",
  transportManage: "transport.manage",
  transportTrip: "transport.trip",
  certificatesRead: "certificates.read",
  certificatesIssue: "certificates.issue",
  certificatesSign: "certificates.sign",
  frontofficeRead: "frontoffice.read",
  frontofficeManage: "frontoffice.manage",
  gatePass: "gate.pass",
  accountsRead: "accounts.read",
  accountsManage: "accounts.manage",
  accountsApprove: "accounts.approve",
  healthRead: "health.read",
  healthManage: "health.manage",
  safetyManage: "safety.manage",
  pocsoCommittee: "pocso.committee",
  hostelRead: "hostel.read",
  hostelManage: "hostel.manage",
  hostelWarden: "hostel.warden",
  inventoryRead: "inventory.read",
  inventoryManage: "inventory.manage",
  purchaseApprove: "purchase.approve",
  govreportRead: "govreport.read",
  govreportManage: "govreport.manage",
  assistUse: "assist.use",
  assistManage: "assist.manage",
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
  /** Shown with any of these permissions too (the reports hub). */
  anyOf?: readonly string[];
  group: NavGroup;
  /** Shown only to parents (child.view without staff access), not to staff who also hold child.view. */
  parentOnly?: boolean;
  /** The plan module it belongs to: locked when the school's plan leaves it out (docs/api/phase-3-subscriptions.md). */
  module?: PlanModule;
  /** Set by the shell on an item the plan locks: it leads to Billing with an "Upgrade" hint. */
  locked?: boolean;
};

/** Each module's report permission: holding any of them opens the reports hub (docs/api/phase-1-reports.md). */
export const REPORT_PERMISSIONS: readonly string[] = [
  PERMISSIONS.attendanceRead,
  PERMISSIONS.feesRead,
  PERMISSIONS.admissionsRead,
  PERMISSIONS.staffRead,
  PERMISSIONS.homeworkManage,
  PERMISSIONS.timetableManage,
  PERMISSIONS.libraryManage,
  PERMISSIONS.payrollRead,
  PERMISSIONS.examsRead,
  PERMISSIONS.ptmManage,
  PERMISSIONS.lessonsRead,
  PERMISSIONS.transportRead,
  PERMISSIONS.certificatesRead,
  PERMISSIONS.frontofficeRead,
  PERMISSIONS.accountsRead,
  PERMISSIONS.healthRead,
  PERMISSIONS.safetyManage,
  PERMISSIONS.hostelRead,
  PERMISSIONS.inventoryRead,
  PERMISSIONS.govreportRead,
  PERMISSIONS.assistUse,
];

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
    key: "exams",
    href: "/app/exams",
    labelKey: "nav.exams",
    shortLabelKey: "nav.exams.short",
    icon: BookOpenCheck,
    permission: PERMISSIONS.examsManage,
    // Teachers enter marks with exams.manage; readers, setup and publishing roles open it too.
    anyOf: [PERMISSIONS.examsRead, PERMISSIONS.examsSetup, PERMISSIONS.examsPublish],
    group: "academics",
  },
  {
    key: "lessons",
    href: "/app/lessons",
    labelKey: "nav.lessons",
    shortLabelKey: "nav.lessons.short",
    icon: BookOpenText,
    permission: PERMISSIONS.lessonsManage,
    // Teachers plan lessons with lessons.manage; readers and approvers open it too (docs/api/phase-2-lessons.md).
    anyOf: [PERMISSIONS.lessonsRead, PERMISSIONS.lessonsApprove],
    group: "academics",
  },
  {
    key: "assist",
    href: "/app/assist",
    labelKey: "nav.assist",
    shortLabelKey: "nav.assist.short",
    icon: Sparkles,
    permission: PERMISSIONS.assistUse,
    // Simulated drafts, the early warning watch list and draft papers (docs/api/phase-3-assist.md).
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
    key: "library",
    href: "/app/library",
    labelKey: "nav.library",
    shortLabelKey: "nav.library.short",
    icon: LibraryBig,
    permission: PERMISSIONS.libraryRead,
    group: "academics",
    module: "library",
  },
  {
    key: "transport",
    href: "/app/transport",
    labelKey: "nav.transport",
    shortLabelKey: "nav.transport.short",
    icon: Bus,
    permission: PERMISSIONS.transportRead,
    group: "academics",
    module: "transport",
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
    key: "payroll",
    href: "/app/payroll",
    labelKey: "nav.payroll",
    shortLabelKey: "nav.payroll.short",
    icon: Landmark,
    permission: PERMISSIONS.payrollRead,
    group: "finance",
  },
  {
    key: "accounts",
    href: "/app/accounts",
    labelKey: "nav.accounts",
    shortLabelKey: "nav.accounts.short",
    icon: Calculator,
    permission: PERMISSIONS.accountsRead,
    // Readers, approvers and managers all open it (docs/api/phase-2-accounts.md).
    anyOf: [PERMISSIONS.accountsManage, PERMISSIONS.accountsApprove],
    group: "finance",
  },
  {
    // Assets, stock, requisitions and purchase orders (docs/api/phase-3-inventory.md): every staff reader raises
    // requisitions; the storekeeper manages; approvers reach the queue.
    key: "inventory",
    href: "/app/inventory",
    labelKey: "nav.inventory",
    shortLabelKey: "nav.inventory.short",
    icon: Package,
    permission: PERMISSIONS.inventoryRead,
    anyOf: [PERMISSIONS.inventoryManage, PERMISSIONS.purchaseApprove],
    group: "finance",
    module: "inventory",
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
    // Drivers and attendants (BUS_ATTENDANT) run today's trips of their routes from a phone, so it comes before
    // Leave and stays in their bottom bar (docs/api/phase-2-transport.md).
    key: "busTrips",
    href: "/app/transport/trip",
    labelKey: "nav.busTrips",
    shortLabelKey: "nav.busTrips.short",
    icon: Navigation,
    permission: PERMISSIONS.transportTrip,
    group: "staff",
    module: "transport",
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
    // Every staff role holds leave.request and parents and students never do: "My payslips" is for staff only
    // (docs/api/phase-2-payroll.md). Someone with no payslip yet sees an empty list.
    key: "myPayslips",
    href: "/app/my-payslips",
    labelKey: "nav.myPayslips",
    shortLabelKey: "nav.myPayslips.short",
    icon: ReceiptText,
    permission: PERMISSIONS.leaveRequest,
    group: "staff",
  },
  {
    // Visitors, early pickup passes, enquiries and the courier register (docs/api/phase-2-frontoffice.md).
    key: "frontoffice",
    href: "/app/frontoffice",
    labelKey: "nav.frontoffice",
    shortLabelKey: "nav.frontoffice.short",
    icon: DoorOpen,
    permission: PERMISSIONS.frontofficeRead,
    anyOf: [PERMISSIONS.frontofficeManage],
    group: "administration",
  },
  {
    // The gate screen for security on a phone: visitors in and out, and children released against the parent's OTP.
    key: "gate",
    href: "/app/frontoffice/gate",
    labelKey: "nav.gate",
    shortLabelKey: "nav.gate.short",
    icon: KeyRound,
    permission: PERMISSIONS.gatePass,
    group: "administration",
  },
  {
    // The infirmary, medical records, safety registers, the POCSO committee and staff checks
    // (docs/api/phase-3-health.md).
    key: "health",
    href: "/app/health",
    labelKey: "nav.health",
    shortLabelKey: "nav.health.short",
    icon: HeartPulse,
    permission: PERMISSIONS.healthRead,
    anyOf: [PERMISSIONS.healthManage, PERMISSIONS.safetyManage],
    group: "administration",
  },
  {
    // Hostels, rooms and beds, allotment, the mess, outing passes and the night roll call (docs/api/phase-3-hostel.md).
    // The office (hostel.read/manage) and wardens (hostel.warden, their own hostels only) share the pages.
    key: "hostel",
    href: "/app/hostel",
    labelKey: "nav.hostel",
    shortLabelKey: "nav.hostel.short",
    icon: BedDouble,
    permission: PERMISSIONS.hostelRead,
    anyOf: [PERMISSIONS.hostelManage, PERMISSIONS.hostelWarden],
    group: "administration",
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
    // Parent-teacher messages (docs/api/phase-2-chat.md); /app/messages is the outgoing SMS and email log.
    key: "chat",
    href: "/app/chat",
    labelKey: "nav.chat",
    shortLabelKey: "nav.chat.short",
    icon: MessagesSquare,
    permission: PERMISSIONS.chatUse,
    anyOf: [PERMISSIONS.chatOversee],
    group: "communication",
  },
  {
    // Leadership sets up meetings with ptm.manage; teachers (chat.use) see their own schedule.
    key: "ptm",
    href: "/app/ptm",
    labelKey: "nav.ptm",
    shortLabelKey: "nav.ptm.short",
    icon: Handshake,
    permission: PERMISSIONS.ptmManage,
    anyOf: [PERMISSIONS.chatUse],
    group: "communication",
  },
  {
    // Certificates and ID cards (docs/api/phase-2-certificates.md): the office, the principal and admins.
    key: "certificates",
    href: "/app/certificates",
    labelKey: "nav.certificates",
    shortLabelKey: "nav.certificates.short",
    icon: BadgeCheck,
    permission: PERMISSIONS.certificatesRead,
    group: "administration",
  },
  {
    // Government reporting (docs/api/phase-3-govreport.md): UDISE+, CBSE OASIS, RTE 25% and schemes.
    key: "govreport",
    href: "/app/govreport",
    labelKey: "nav.govreport",
    shortLabelKey: "nav.govreport.short",
    icon: Landmark,
    permission: PERMISSIONS.govreportRead,
    group: "administration",
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
  {
    key: "reports",
    href: "/app/reports",
    labelKey: "nav.reports",
    shortLabelKey: "nav.reports.short",
    icon: BarChart3,
    permission: PERMISSIONS.attendanceRead,
    anyOf: REPORT_PERMISSIONS,
    group: "overview",
  },
];

/**
 * The parent and student app's menu (docs/api/phase-1-portal.md), used instead of NAV_ITEMS for someone whose
 * every role is PARENT or STUDENT. Phones show the first four in the bottom bar; Fees needs child.view, so a
 * student sees Notices there instead.
 */
export const FAMILY_NAV_ITEMS: readonly NavItem[] = [
  {
    key: "dashboard",
    href: "/app/dashboard",
    labelKey: "portal.nav.home",
    shortLabelKey: "portal.nav.home",
    icon: House,
    permission: PERMISSIONS.dashboardView,
    group: "overview",
  },
  {
    key: "family-attendance",
    href: "/app/family/attendance",
    labelKey: "nav.attendance",
    shortLabelKey: "nav.attendance.short",
    icon: ClipboardCheck,
    permission: PERMISSIONS.dashboardView,
    group: "academics",
  },
  {
    key: "family-homework",
    href: "/app/homework",
    labelKey: "nav.homework",
    shortLabelKey: "nav.homework.short",
    icon: NotebookPen,
    permission: PERMISSIONS.dashboardView,
    group: "academics",
  },
  {
    key: "family-fees",
    href: "/app/family/fees",
    labelKey: "nav.fees",
    shortLabelKey: "nav.fees.short",
    icon: Wallet,
    permission: PERMISSIONS.childView,
    group: "finance",
  },
  {
    // Early pickup passes and the routine pickup persons (docs/api/phase-2-frontoffice.md), for parents; after the
    // first four, so the phone bar keeps them.
    key: "family-pickup",
    href: "/app/family/pickup",
    labelKey: "nav.pickup",
    shortLabelKey: "nav.pickup.short",
    icon: CarFront,
    permission: PERMISSIONS.childView,
    group: "academics",
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
    key: "calendar",
    href: "/app/calendar",
    labelKey: "nav.calendar",
    shortLabelKey: "nav.calendar.short",
    icon: CalendarDays,
    permission: PERMISSIONS.noticesRead,
    group: "communication",
  },
  {
    key: "family-leave",
    href: "/app/family/leave",
    labelKey: "nav.leave",
    shortLabelKey: "nav.leave.short",
    icon: CalendarOff,
    permission: PERMISSIONS.dashboardView,
    group: "academics",
  },
  {
    key: "family-library",
    href: "/app/family/library",
    labelKey: "nav.library",
    shortLabelKey: "nav.library.short",
    icon: LibraryBig,
    permission: PERMISSIONS.dashboardView,
    group: "academics",
    module: "library",
  },
  {
    key: "family-results",
    href: "/app/family/results",
    labelKey: "nav.results",
    shortLabelKey: "nav.results.short",
    icon: Award,
    permission: PERMISSIONS.dashboardView,
    group: "academics",
  },
  {
    // Study material and online tests (docs/api/phase-2-lessons.md); after the first four, so the phone bar keeps them.
    key: "family-study",
    href: "/app/family/study",
    labelKey: "nav.study",
    shortLabelKey: "nav.study.short",
    icon: Lightbulb,
    permission: PERMISSIONS.dashboardView,
    group: "academics",
  },
  {
    key: "family-bus",
    href: "/app/family/bus",
    labelKey: "nav.bus",
    shortLabelKey: "nav.bus.short",
    icon: Bus,
    permission: PERMISSIONS.dashboardView,
    group: "academics",
    module: "transport",
  },
  {
    // A boarding child's hostel: the bed, the warden, outing and home-leave passes (docs/api/phase-3-hostel.md).
    // Parents only; the shell hides it unless one of the children boards (useFamilyHostelNav).
    key: "family-hostel",
    href: "/app/family/hostel",
    labelKey: "nav.hostel",
    shortLabelKey: "nav.hostel.short",
    icon: BedDouble,
    permission: PERMISSIONS.childView,
    group: "academics",
  },
  {
    // A child's certificates and ID card are for parents (child.view), not students.
    key: "family-certificates",
    href: "/app/family/certificates",
    labelKey: "nav.certificates",
    shortLabelKey: "nav.certificates.short",
    icon: BadgeCheck,
    permission: PERMISSIONS.childView,
    group: "academics",
  },
  {
    // Allergies, conditions, consent and the infirmary's visits for a parent's own child (docs/api/phase-3-health.md).
    key: "family-health",
    href: "/app/family/health",
    labelKey: "nav.health",
    shortLabelKey: "nav.health.short",
    icon: HeartPulse,
    permission: PERMISSIONS.childView,
    group: "academics",
    parentOnly: true,
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
    // Messages with the children's teachers and meeting bookings are for parents (child.view), not students.
    key: "family-chat",
    href: "/app/family/chat",
    labelKey: "nav.familyChat",
    shortLabelKey: "nav.familyChat.short",
    icon: MessagesSquare,
    permission: PERMISSIONS.childView,
    group: "communication",
  },
  {
    key: "family-ptm",
    href: "/app/family/ptm",
    labelKey: "nav.ptm",
    shortLabelKey: "nav.ptm.short",
    icon: Handshake,
    permission: PERMISSIONS.childView,
    group: "communication",
  },
  {
    // The government details a parent may fill: mother tongue and the APAAR ID (docs/api/phase-3-govreport.md).
    key: "family-govreport",
    href: "/app/family/govreport",
    labelKey: "nav.familyGovreport",
    shortLabelKey: "nav.familyGovreport.short",
    icon: Landmark,
    permission: PERMISSIONS.childView,
    group: "overview",
  },
];

type PermissionSubject = (Pick<Me, "permissions" | "platformAdmin"> & Partial<Pick<Me, "roles">>) | null | undefined;
/** Navigation also looks at roles, to tell parents from staff. */
export type NavSubject = PermissionSubject;

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

/**
 * The navigation items this user may see, in display order: the family menu for someone whose every role is
 * PARENT or STUDENT, otherwise the staff menu. Pure.
 */
export function navFor(me: NavSubject): NavItem[] {
  const items = isFamilyMember(me) ? FAMILY_NAV_ITEMS : NAV_ITEMS;
  return items.filter(
    (item) =>
      (hasPermission(me, item.permission) || (item.anyOf ?? []).some((p) => hasPermission(me, p))) &&
      (!item.parentOnly || isParentUser(me)),
  );
}

/** True when the user holds at least one of the permissions. */
export function hasAnyPermission(me: PermissionSubject, permissions: readonly string[]): boolean {
  return permissions.some((p) => hasPermission(me, p));
}

/** Where a user lands after sign-in (or when opening /app). */
export function landingPath(me: PermissionSubject): string {
  if (!me) return "/login";
  if (hasPermission(me, PERMISSIONS.platformAdmin)) return "/app/platform/schools";
  return "/app/dashboard";
}

/** The nav item that owns a pathname (longest matching href), if any. */
export function navItemForPath(pathname: string): NavItem | undefined {
  return [...NAV_ITEMS, ...FAMILY_NAV_ITEMS]
    .sort((a, b) => b.href.length - a.href.length)
    .find((item) => pathname === item.href || pathname.startsWith(`${item.href}/`));
}

/** Only allow post-login redirects back into the app (prevents open redirects). */
export function safeNextPath(next: string | null | undefined): string | null {
  if (!next) return null;
  if (!/^\/app(?:[/?#]|$)/.test(next) || next.includes("\\")) return null;
  return next;
}
