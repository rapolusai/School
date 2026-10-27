"use client";

import { useEffect, useState } from "react";
import { billingApi } from "./billing-api";
import { hasPermission, PERMISSIONS, type NavItem, type NavSubject } from "./permissions";
import type { PlanModule } from "./types";

/*
 * The school's plan in the menu (docs/api/phase-3-subscriptions.md, "Plan limits"). Which modules a plan leaves out
 * is decided by the API (backend PlanFeatures, the one mapping); the menu only knows each item's module.
 */

export { PLAN_MODULE_LABELS, planModule } from "./plan-modules";

/** Window event that asks the shell to fetch the plan again (after the plan changes). */
export const PLAN_CHANGED_EVENT = "akshara:plan-changed";

/** Tells the shell that the school's plan changed, so locked menu items are checked again. */
export function notifyPlanChanged(): void {
  if (typeof window !== "undefined") window.dispatchEvent(new Event(PLAN_CHANGED_EVENT));
}

/**
 * Menu items with the plan applied: an item whose module the plan locks leads to Billing with an "Upgrade" hint for
 * someone who can see Billing (a School Admin), and is hidden from everyone else.
 */
export function applyPlanLocks(
  items: readonly NavItem[],
  locked: ReadonlySet<PlanModule>,
  me: NavSubject,
): NavItem[] {
  if (locked.size === 0) return [...items];
  const canUpgrade = hasPermission(me, PERMISSIONS.billingRead);
  return items.flatMap((item) => {
    if (!item.module || !locked.has(item.module)) return [item];
    return canUpgrade ? [{ ...item, href: `/app/billing?upgrade=${item.module}`, locked: true }] : [];
  });
}

const NONE: ReadonlySet<PlanModule> = new Set();

/**
 * The modules the signed-in school's plan locks now, fetched once per sign-in and again on {@link PLAN_CHANGED_EVENT}.
 * Nothing is locked for the platform console, during the trial, or when the fetch fails (the API still refuses).
 */
export function usePlanLocks(me: (NavSubject & { id?: string; tenant?: unknown }) | null): ReadonlySet<PlanModule> {
  const school = me !== null && !me.platformAdmin && Boolean(me.tenant);
  const userId = me?.id ?? "";
  const [state, setState] = useState<{ userId: string; locked: ReadonlySet<PlanModule> } | null>(null);
  useEffect(() => {
    if (!school) return;
    let cancelled = false;
    const load = () => {
      Promise.resolve()
        .then(() => billingApi.planAccess())
        .then((access) => {
          if (!cancelled) setState({ userId, locked: new Set(access.enforced ? access.lockedModules : []) });
        })
        .catch(() => {
          if (!cancelled) setState({ userId, locked: NONE });
        });
    };
    load();
    window.addEventListener(PLAN_CHANGED_EVENT, load);
    return () => {
      cancelled = true;
      window.removeEventListener(PLAN_CHANGED_EVENT, load);
    };
  }, [school, userId]);
  return school && state?.userId === userId ? state.locked : NONE;
}
