"use client";

import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { auditActionLabel, auditEntityLabel, localeFor, plural, useI18n } from "@/lib/i18n";
import type { AuditEvent } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

const LIMIT = 50;

/** Newest first, regardless of the order the API returned. */
export function sortNewestFirst(events: AuditEvent[]): AuditEvent[] {
  return [...events].sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime());
}

function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id;
}

export function AuditView() {
  const { t, lang } = useI18n();
  const audit = useApiData(`audit:${LIMIT}`, () => api.listAuditEvents(LIMIT));
  const events = sortNewestFirst(audit.data ?? []);
  const locale = localeFor(lang);

  return (
    <>
      <PageHead eyebrow={t("audit.eyebrow")} title={t("audit.title")} />
      <section className="card">
        {audit.error && !audit.data ? (
          <ErrorState error={audit.error} onRetry={audit.reload} />
        ) : audit.loading && !audit.data ? (
          <LoadingRows rows={6} />
        ) : (
          <>
            <p className="mb-3 text-[13px] text-ink-3">{plural(t, "audit.latest", events.length)}</p>
            <div className="table-wrap">
              <table className="table" data-testid="audit-table">
                <thead>
                  <tr>
                    <th scope="col">{t("audit.col.time")}</th>
                    <th scope="col">{t("audit.col.actor")}</th>
                    <th scope="col">{t("audit.col.action")}</th>
                    <th scope="col" className="hidden md:table-cell">
                      {t("audit.col.entity")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {events.map((event) => (
                    <tr key={event.id}>
                      <td className="mono whitespace-nowrap text-ink-3">
                        <time dateTime={event.at}>{formatDateTime(event.at, locale)}</time>
                      </td>
                      <td>{event.actorName ?? <span className="text-ink-3">{t("common.system")}</span>}</td>
                      <td className="text-ink" title={event.action}>
                        {auditActionLabel(t, event.action)}
                      </td>
                      <td className="hidden md:table-cell">
                        {event.entityType ? (
                          <>
                            {auditEntityLabel(t, event.entityType)}
                            {event.entityId ? (
                              <span className="mono text-ink-3" title={event.entityId}>
                                {" "}
                                · {shortId(event.entityId)}
                              </span>
                            ) : null}
                          </>
                        ) : (
                          <span className="text-ink-3">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                  {events.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="empty">
                        {t("audit.empty")}
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>
    </>
  );
}
