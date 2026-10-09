"use client";

import Link from "next/link";
import type { NavItem } from "@/lib/permissions";

export function isActivePath(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/** Sidebar link. In the tablet rail the label is visually hidden and shown as a tooltip. */
export function SideNavLink({
  item,
  label,
  active,
}: {
  item: NavItem;
  label: string;
  active: boolean;
}) {
  const Icon = item.icon;
  return (
    <Link
      href={item.href}
      className="nav-link"
      data-tip={label}
      aria-current={active ? "page" : undefined}
    >
      <Icon size={20} strokeWidth={1.8} aria-hidden="true" />
      <span className="lbl">{label}</span>
    </Link>
  );
}
