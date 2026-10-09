import {
  Building2,
  ClipboardCheck,
  GraduationCap,
  LayoutDashboard,
  MessageSquareText,
  School,
  ScrollText,
  ShieldCheck,
  Users,
  type LucideIcon,
} from "lucide-react";
import type { MessageKey } from "./i18n/en";
import type { Me } from "./types";

/** Permission codes from docs/api/phase-0.md, phase-1.md and phase-1-attendance.md. */
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
  attendanceRead: "attendance.read",
  attendanceMark: "attendance.mark",
  attendanceManage: "attendance.manage",
  messagesRead: "messages.read",
} as const;

export type NavGroup = "overview" | "academics" | "administration" | "platform";

export type NavItem = {
  key: string;
  href: string;
  labelKey: MessageKey;
  /** Short label for the phone bottom bar. */
  shortLabelKey: MessageKey;
  icon: LucideIcon;
  permission: string;
  group: NavGroup;
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
    key: "students",
    href: "/app/students",
    labelKey: "nav.students",
    shortLabelKey: "nav.students.short",
    icon: GraduationCap,
    permission: PERMISSIONS.studentsRead,
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
    key: "schools",
    href: "/app/platform/schools",
    labelKey: "nav.schools",
    shortLabelKey: "nav.schools.short",
    icon: Building2,
    permission: PERMISSIONS.platformAdmin,
    group: "platform",
  },
];

type PermissionSubject = Pick<Me, "permissions" | "platformAdmin"> | null | undefined;

export function hasPermission(me: PermissionSubject, permission: string): boolean {
  if (!me) return false;
  if (permission === PERMISSIONS.platformAdmin && me.platformAdmin) return true;
  return me.permissions.includes(permission);
}

/** The navigation items this user may see, in display order. Pure. */
export function navFor(me: PermissionSubject): NavItem[] {
  return NAV_ITEMS.filter((item) => hasPermission(me, item.permission));
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
