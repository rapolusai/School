"use client";

import { Lock } from "lucide-react";
import Link from "next/link";
import type { NavItem } from "@/lib/permissions";

export function isActivePath(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * Sidebar link. In the tablet rail the label is visually hidden and shown as a tooltip. An item the school's plan
 * locks shows a lock and "Upgrade", and leads to Billing.
 */
export function SideNavLink({
  item,
  label,
  active,
  lockedLabel,
  upgradeLabel,
}: {
  item: NavItem;
  label: string;
  active: boolean;
  /** Accessible name of a locked item ("Library: not in your plan..."). */
  lockedLabel?: string;
  upgradeLabel?: string;
}) {
  const Icon = item.locked ? Lock : item.icon;
  return (
    <Link
      href={item.href}
      className="nav-link"
      data-tip={item.locked ? (lockedLabel ?? label) : label}
      aria-current={active && !item.locked ? "page" : undefined}
      aria-label={item.locked ? lockedLabel : undefined}
      data-testid={item.locked ? `nav-locked-${item.key}` : undefined}
    >
      <Icon size={20} strokeWidth={1.8} aria-hidden="true" />
      <span className="lbl">
        {label}
        {item.locked && upgradeLabel ? (
          <span className="nav-upgrade" aria-hidden="true">
            {upgradeLabel}
          </span>
        ) : null}
      </span>
    </Link>
  );
}
