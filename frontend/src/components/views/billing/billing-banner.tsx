"use client";

import { ArrowRight, CalendarClock, CircleAlert } from "lucide-react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { billingApi } from "@/lib/billing-api";
import { localeFor, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { BillingNotice } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { noticeText } from "./billing-ui";

/**
 * Whether the shell shows the banner on this page. The Billing page shows the notice itself, and the dashboard
 * already has its own trial banner, so only an overdue payment is repeated there.
 */
export function showBannerOn(pathname: string, notice: BillingNotice | undefined): boolean {
  if (!notice?.kind) return false;
  if (pathname === "/app/billing" || pathname.startsWith("/app/billing/")) return false;
  if (pathname === "/app/dashboard" && notice.kind !== "PAYMENT_OVERDUE") return false;
  return true;
}

/**
 * For a school's admins (billing.read): the trial is ending or has ended, or a payment to Akshara is overdue. Loaded
 * once per session; never shown to the Super Admin or to other staff.
 */
export function BillingBanner({ pathname }: { pathname: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const allowed = Boolean(me?.tenant) && hasPermission(me, PERMISSIONS.billingRead);
  const notice = useApiData(allowed ? "billing:notice" : null, billingApi.notice);
  if (!allowed || !showBannerOn(pathname, notice.data)) return null;
  const data = notice.data as BillingNotice;
  const text = noticeText(data, t, localeFor(lang));
  if (!text) return null;
  const overdue = data.kind === "PAYMENT_OVERDUE";
  const Icon = overdue ? CircleAlert : CalendarClock;
  return (
    <div
      className={`alert ${overdue ? "alert-bad" : "alert-warn"} no-print flex-wrap items-center`}
      role="status"
      data-testid="billing-banner"
    >
      <Icon size={18} aria-hidden="true" className="flex-none" />
      <span className="min-w-0 flex-1">{text}</span>
      <Link href="/app/billing" className="link inline-flex items-center gap-1 whitespace-nowrap">
        {t("billing.banner.link")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </div>
  );
}
