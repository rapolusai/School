"use client";

import { Download } from "lucide-react";
import { useState, type ReactNode } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { auditApi, type AuditFilters } from "@/lib/audit-api";
import { downloadCsv } from "@/lib/fees-api";
import { formatDateTime } from "@/lib/format";
import { auditActionLabel, auditEntityLabel, localeFor, plural, useI18n, type Translate } from "@/lib/i18n";
import type { AuditEvent } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

export const AUDIT_PAGE_SIZE = 50;

/** Newest first, regardless of the order the API returned. */
export function sortNewestFirst<T extends Pick<AuditEvent, "at">>(events: T[]): T[] {
  return [...events].sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime());
}

function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id;
}

/** True when the day range runs backwards (the API refuses it). */
export function badRange(filters: AuditFilters): boolean {
  return Boolean(filters.from && filters.to && filters.to < filters.from);
}

/** Whether any filter is set (the empty message then says "nothing matches"). */
export function isFiltered(filters: AuditFilters): boolean {
  return Boolean(filters.action || filters.actorId || filters.schoolId || filters.from || filters.to);
}

/**
 * A page of an audit trail and "load more": the first page follows the filters; each "load more" asks for the entries
 * older than the last one shown. Changing a filter starts again from the newest.
 */
export function useAuditPages<T extends Pick<AuditEvent, "at" | "id">>(
  keyPrefix: string,
  filters: AuditFilters,
  load: (filters: AuditFilters, limit: number) => Promise<T[]>,
) {
  const key = badRange(filters) ? null : `${keyPrefix}:${JSON.stringify(filters)}`;
  const first = useApiData(key, () => load(filters, AUDIT_PAGE_SIZE));
  const [more, setMore] = useState<{ key: string | null; rows: T[]; done: boolean }>({
    key: null,
    rows: [],
    done: false,
  });
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<unknown>(null);
  const older = more.key === key ? more : { key, rows: [] as T[], done: false };
  const rows = [...(first.data ?? []), ...older.rows];
  const hasMore =
    !older.done && (older.rows.length > 0 ? true : (first.data?.length ?? 0) >= AUDIT_PAGE_SIZE);

  const loadMore = async () => {
    const last = rows[rows.length - 1];
    if (!last) return;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await load({ ...filters, before: last.at, beforeId: last.id }, AUDIT_PAGE_SIZE);
      setMore({ key, rows: [...older.rows, ...page], done: page.length < AUDIT_PAGE_SIZE });
    } catch (error) {
      setMoreError(error);
    } finally {
      setLoadingMore(false);
    }
  };

  return { first, rows, hasMore, loadMore, loadingMore, moreError };
}

/** The filter fields shared by the school's audit page and the Super Admin's Activity page. */
export function AuditFilterBar({
  filters,
  onChange,
  actions,
  children,
}: {
  filters: AuditFilters;
  onChange: (next: AuditFilters) => void;
  actions: string[];
  children?: ReactNode;
}) {
  const { t } = useI18n();
  const set = (key: keyof AuditFilters, value: string) => onChange({ ...filters, [key]: value || undefined });
  return (
    <div className="toolbar" data-testid="audit-filters">
      <label className="min-w-[160px] flex-1 sm:flex-none">
        <span className="sr-only">{t("audit.filter.action")}</span>
        <select
          className="input"
          name="action"
          value={filters.action ?? ""}
          onChange={(e) => set("action", e.target.value)}
          aria-label={t("audit.filter.action")}
        >
          <option value="">{t("audit.filter.allActions")}</option>
          {[...actions]
            .sort((a, b) => auditActionLabel(t, a).localeCompare(auditActionLabel(t, b)))
            .map((action) => (
              <option key={action} value={action}>
                {auditActionLabel(t, action)}
              </option>
            ))}
        </select>
      </label>
      {children}
      <label className="w-[calc(50%-4px)] sm:w-auto">
        <span className="sr-only">{t("audit.filter.from")}</span>
        <input
          type="date"
          className="input"
          name="from"
          value={filters.from ?? ""}
          onChange={(e) => set("from", e.target.value)}
          aria-label={t("audit.filter.from")}
          title={t("audit.filter.from")}
        />
      </label>
      <label className="w-[calc(50%-4px)] sm:w-auto">
        <span className="sr-only">{t("audit.filter.to")}</span>
        <input
          type="date"
          className="input"
          name="to"
          value={filters.to ?? ""}
          onChange={(e) => set("to", e.target.value)}
          aria-label={t("audit.filter.to")}
          title={t("audit.filter.to")}
        />
      </label>
    </div>
  );
}

/** The "Download for Excel" button of an audit trail: the file follows the filters and the export is audited. */
export function AuditExportButton({ path, fileName, disabled }: { path: string; fileName: string; disabled?: boolean }) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const run = async () => {
    setBusy(true);
    setFailed(false);
    try {
      await downloadCsv(path, fileName);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <span className="flex items-center gap-2">
      <button type="button" className="btn btn-sm" onClick={run} disabled={busy || disabled} data-testid="audit-export">
        <Download size={16} aria-hidden="true" />
        {busy ? t("common.loading") : t("audit.export")}
      </button>
      {failed ? (
        <span role="alert" className="text-[13px] text-bad">
          {t("audit.exportFailed")}
        </span>
      ) : null}
    </span>
  );
}

/** "Load more" under a trail, or nothing when everything is shown. */
export function LoadMore({
  hasMore,
  loading,
  error,
  onClick,
  shown,
  t,
}: {
  hasMore: boolean;
  loading: boolean;
  error: unknown;
  onClick: () => void;
  shown: number;
  t: Translate;
}) {
  return (
    <div className="pager">
      <span>{plural(t, "audit.shown", shown)}</span>
      {error ? (
        <span role="alert" className="text-[13px] text-bad">
          {t("audit.loadMoreFailed")}
        </span>
      ) : null}
      {hasMore ? (
        <button type="button" className="btn btn-sm" onClick={onClick} disabled={loading} data-testid="audit-more">
          {loading ? t("common.loading") : t("audit.loadMore")}
        </button>
      ) : null}
    </div>
  );
}

/** The school's audit trail (audit.read): filter by action, person and days, load older entries, export to Excel. */
export function AuditView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [filters, setFilters] = useState<AuditFilters>({});
  const facets = useApiData("audit:facets", auditApi.facets);
  const pages = useAuditPages("audit", filters, auditApi.list);
  const events = sortNewestFirst(pages.rows);
  const filtered = isFiltered(filters);

  return (
    <>
      <PageHead eyebrow={t("audit.eyebrow")} title={t("audit.title")} />
      <section className="card">
        <AuditFilterBar filters={filters} onChange={setFilters} actions={facets.data?.actions ?? []}>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("audit.filter.person")}</span>
            <select
              className="input"
              name="actorId"
              value={filters.actorId ?? ""}
              onChange={(e) => setFilters({ ...filters, actorId: e.target.value || undefined })}
              aria-label={t("audit.filter.person")}
            >
              <option value="">{t("audit.filter.everyone")}</option>
              {(facets.data?.people ?? []).map((person) => (
                <option key={person.id} value={person.id}>
                  {person.name ?? person.id}
                </option>
              ))}
            </select>
          </label>
          <AuditExportButton
            path={auditApi.exportPath(filters)}
            fileName="audit-trail.csv"
            disabled={badRange(filters)}
          />
        </AuditFilterBar>
        {badRange(filters) ? (
          <p className="empty">{t("audit.badRange")}</p>
        ) : pages.first.error && !pages.first.data ? (
          <ErrorState error={pages.first.error} onRetry={pages.first.reload} />
        ) : pages.first.loading && !pages.first.data ? (
          <LoadingRows rows={6} />
        ) : (
          <>
            <div className="table-wrap" aria-busy={pages.first.loading}>
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
                        {filtered ? t("audit.noMatch") : t("audit.empty")}
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
            {events.length > 0 ? (
              <LoadMore
                hasMore={pages.hasMore}
                loading={pages.loadingMore}
                error={pages.moreError}
                onClick={pages.loadMore}
                shown={events.length}
                t={t}
              />
            ) : null}
          </>
        )}
      </section>
    </>
  );
}
