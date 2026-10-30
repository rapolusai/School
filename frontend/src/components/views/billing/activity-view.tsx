"use client";

import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import {
  AuditExportButton,
  AuditFilterBar,
  LoadMore,
  badRange,
  isFiltered,
  sortNewestFirst,
  useAuditPages,
} from "@/components/views/audit-view";
import { platformAuditApi, type AuditFilters, type PlatformAuditEvent } from "@/lib/audit-api";
import { formatDateTime } from "@/lib/format";
import { auditActionLabel, localeFor, useI18n, type Translate } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";

/** A short, readable note of what an entry's details say (reason, invoice, plan, amount), or null. */
export function activityNote(t: Translate, event: Pick<PlatformAuditEvent, "details">): string | null {
  const d = event.details ?? {};
  const parts: string[] = [];
  const text = (key: string) => (typeof d[key] === "string" && (d[key] as string).trim() ? (d[key] as string) : null);
  const invoiceNo = text("invoiceNo");
  if (invoiceNo) parts.push(invoiceNo);
  const plan = text("plan");
  const previous = text("previousPlan");
  if (plan && previous) parts.push(`${previous} → ${plan}`);
  else if (plan) parts.push(plan);
  const status = text("status");
  if (status && !plan) parts.push(status);
  const reason = text("reason");
  if (reason) parts.push(reason);
  const email = text("email");
  if (email) parts.push(email);
  if (d.automatic === true) parts.push(t("activity.automatic"));
  if (d.onBehalf === true) parts.push(t("activity.onBehalf"));
  return parts.length ? parts.join(" · ") : null;
}

/**
 * /app/platform/activity: the Super Admin's own trail across schools. Super Admin sign-ins (and failed ones),
 * everything a Super Admin changed in a school, and every subscription, invoice and status change made by a school,
 * the renewal job or the payment gateway. Filter by school, action and days; download it for Excel.
 */
export function ActivityView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [filters, setFilters] = useState<AuditFilters>({});
  const facets = useApiData("platform:activity:facets", platformAuditApi.facets);
  const pages = useAuditPages("platform:activity", filters, platformAuditApi.list);
  const events = sortNewestFirst(pages.rows);

  return (
    <>
      <PageHead eyebrow={t("activity.eyebrow")} title={t("activity.title")} />
      <section className="card">
        <AuditFilterBar filters={filters} onChange={setFilters} actions={facets.data?.actions ?? []}>
          <label className="min-w-[160px] flex-1 sm:flex-none">
            <span className="sr-only">{t("activity.filter.school")}</span>
            <select
              className="input"
              name="schoolId"
              value={filters.schoolId ?? ""}
              onChange={(e) => setFilters({ ...filters, schoolId: e.target.value || undefined })}
              aria-label={t("activity.filter.school")}
            >
              <option value="">{t("activity.filter.allSchools")}</option>
              {[...(facets.data?.schools ?? [])]
                .sort((a, b) => a.name.localeCompare(b.name))
                .map((school) => (
                  <option key={school.id} value={school.id}>
                    {school.name} ({school.code})
                  </option>
                ))}
            </select>
          </label>
          <AuditExportButton
            path={platformAuditApi.exportPath(filters)}
            fileName="platform-activity.csv"
            disabled={badRange(filters)}
          />
        </AuditFilterBar>
        {badRange(filters) ? (
          <p className="empty">{t("audit.badRange")}</p>
        ) : pages.first.error && !pages.first.data ? (
          <ErrorState error={pages.first.error} onRetry={pages.first.reload} />
        ) : pages.first.loading && !pages.first.data ? (
          <LoadingRows rows={6} />
        ) : events.length === 0 ? (
          <p className="empty" data-testid="activity-empty">
            {isFiltered(filters) ? t("audit.noMatch") : t("activity.empty")}
          </p>
        ) : (
          <>
            <div className="table-wrap" aria-busy={pages.first.loading}>
              <table className="table" data-testid="activity-table">
                <thead>
                  <tr>
                    <th scope="col">{t("audit.col.time")}</th>
                    <th scope="col">{t("activity.col.school")}</th>
                    <th scope="col">{t("audit.col.action")}</th>
                    <th scope="col" className="hidden md:table-cell">
                      {t("audit.col.actor")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {events.map((event) => {
                    const note = activityNote(t, event);
                    return (
                      <tr key={event.id}>
                        <td className="mono whitespace-nowrap text-ink-3">
                          <time dateTime={event.at}>{formatDateTime(event.at, locale)}</time>
                        </td>
                        <td className="max-w-0 w-full sm:w-auto sm:max-w-none">
                          {event.schoolId ? (
                            <Link
                              href={`/app/platform/schools/${encodeURIComponent(event.schoolId)}`}
                              className="link block truncate"
                            >
                              {event.schoolName ?? event.schoolId}
                            </Link>
                          ) : (
                            <span className="text-ink-3">{t("activity.platform")}</span>
                          )}
                          {event.schoolCode ? (
                            <span className="mono block text-[12.5px] text-ink-3">{event.schoolCode}</span>
                          ) : null}
                        </td>
                        <td>
                          <span className="text-ink" title={event.action}>
                            {auditActionLabel(t, event.action)}
                          </span>
                          {note ? <span className="block text-[12.5px] text-ink-3">{note}</span> : null}
                          <span className="block pt-1 text-[12.5px] text-ink-3 md:hidden">
                            {event.actorName ?? t("common.system")}
                          </span>
                        </td>
                        <td className="hidden md:table-cell">
                          {event.actorName ?? <span className="text-ink-3">{t("common.system")}</span>}
                          {event.platformAdmin ? (
                            <span className="block pt-1">
                              <Pill tone="accent">{t("activity.superAdmin")}</Pill>
                            </span>
                          ) : null}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <LoadMore
              hasMore={pages.hasMore}
              loading={pages.loadingMore}
              error={pages.moreError}
              onClick={pages.loadMore}
              shown={events.length}
              t={t}
            />
          </>
        )}
      </section>
    </>
  );
}
