import type { MessageKey } from "./i18n/en";
import type { PlanModule } from "./types";

/*
 * Plan modules without client-only code, so server pages (the Billing page reading ?upgrade=) can use them too.
 */

/** How each plan module is named on the Billing page's "not in your plan" notice. */
export const PLAN_MODULE_LABELS: Record<PlanModule, MessageKey> = {
  transport: "nav.transport",
  library: "nav.library",
  inventory: "nav.inventory",
  report_cards: "billing.feature.report_cards",
};

/** The plan module a Billing link names (`?upgrade=library`), or null. */
export function planModule(value: unknown): PlanModule | null {
  return typeof value === "string" && Object.hasOwn(PLAN_MODULE_LABELS, value) ? (value as PlanModule) : null;
}
