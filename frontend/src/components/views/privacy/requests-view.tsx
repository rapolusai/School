"use client";

import { ChevronLeft, ChevronRight, Search } from "lucide-react";
import Link from "next/link";
import { useRef, useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatDate, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { privacyApi } from "@/lib/privacy-api";
import {
  REQUEST_DUE_DAYS,
  REQUEST_STATUSES,
  REQUEST_TYPES,
  type DataRequestQuery,
  type RequestStatus,
  type RequestType,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { aboutLabel, DuePill, PrivacyNav, requestTypeLabel, RequestStatusPill } from "./privacy-ui";

export const REQUESTS_PAGE_SIZE = 25;

type StatusFilter = "OPEN" | "ALL" | RequestStatus;

/** The API query for the queue's filters. Exported for tests. */
export function toRequestQuery(status: StatusFilter, type: "" | RequestType, search: string, page: number) {
  const query: DataRequestQuery = {
    status: status === "ALL" ? undefined : status,
    type: type || undefined,
    q: search.trim() || undefined,
    page,
    size: REQUESTS_PAGE_SIZE,
  };
  return query;
}

/** /app/privacy: parents' data requests, open ones first by due date, with counts. */
export function RequestsView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [status, setStatus] = useState<StatusFilter>("OPEN");
  const [type, setType] = useState<"" | RequestType>("");
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const query = toRequestQuery(status, type, search, page);
  const requests = useApiData(`privacy:requests:${JSON.stringify(query)}`, () => privacyApi.requests(query));
  const data = requests.data;
  const rows = data?.items ?? [];
  const filtered = Boolean(search.trim() || type || status !== "OPEN");

  const onSearch = (value: string) => {
    setSearchText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  const first = data && data.total > 0 ? data.page * data.size + 1 : 0;
  const last = data ? Math.min(data.total, data.page * data.size + rows.length) : 0;
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  return (
    <>
      <PageHead eyebrow={t("privacy.eyebrow")} title={t("privacy.requests.title")} />
      <PrivacyNav />

      <div className="grid grid-cols-3 gap-3" data-testid="request-counts">
        <div className="card">
          <p className="text-[13px] text-ink-3">{t("privacy.requests.kpi.open")}</p>
          <p className="kpi-value">{data ? data.counts.open : "–"}</p>
        </div>
        <div className="card">
          <p className="text-[13px] text-ink-3">{t("privacy.requests.kpi.overdue")}</p>
          <p className={`kpi-value${data && data.counts.overdue > 0 ? " text-bad" : ""}`}>
            {data ? data.counts.overdue : "–"}
          </p>
        </div>
        <div className="card">
          <p className="text-[13px] text-ink-3">{t("privacy.requests.kpi.closed")}</p>
          <p className="kpi-value">{data ? data.counts.closed : "–"}</p>
        </div>
      </div>
      <p className="text-[13.5px] text-ink-2">{t("privacy.requests.policy", { days: REQUEST_DUE_DAYS })}</p>

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("privacy.requests.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(e) => onSearch(e.target.value)}
              placeholder={t("privacy.requests.search")}
              maxLength={100}
            />
          </label>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("privacy.requests.filter.status")}</span>
            <select
              className="input"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as StatusFilter);
                setPage(0);
              }}
              aria-label={t("privacy.requests.filter.status")}
            >
              <option value="OPEN">{t("privacy.requests.filter.OPEN")}</option>
              {REQUEST_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `privacy.status.${s}`, s)}
                </option>
              ))}
              <option value="ALL">{t("privacy.requests.filter.ALL")}</option>
            </select>
          </label>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("privacy.requests.filter.type")}</span>
            <select
              className="input"
              value={type}
              onChange={(e) => {
                setType(e.target.value as "" | RequestType);
                setPage(0);
              }}
              aria-label={t("privacy.requests.filter.type")}
            >
              <option value="">{t("privacy.requests.filter.allTypes")}</option>
              {REQUEST_TYPES.map((value) => (
                <option key={value} value={value}>
                  {requestTypeLabel(t, value)}
                </option>
              ))}
            </select>
          </label>
        </div>

        {requests.error && !data ? (
          <ErrorState error={requests.error} onRetry={requests.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="requests-empty">
            {filtered ? t("privacy.requests.noMatch") : t("privacy.requests.empty")}
          </p>
        ) : (
          <div aria-busy={requests.loading}>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="requests-table">
                <thead>
                  <tr>
                    <th scope="col">{t("privacy.requests.col.requester")}</th>
                    <th scope="col">{t("privacy.requests.col.type")}</th>
                    <th scope="col">{t("privacy.requests.col.about")}</th>
                    <th scope="col">{t("privacy.requests.col.raised")}</th>
                    <th scope="col">{t("privacy.requests.col.due")}</th>
                    <th scope="col">{t("privacy.requests.col.assigned")}</th>
                    <th scope="col">{t("privacy.requests.col.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <Link href={`/app/privacy/requests/${r.id}`} className="link font-semibold">
                          {r.requesterName}
                        </Link>
                      </td>
                      <td>{requestTypeLabel(t, r.type)}</td>
                      <td>
                        <span className="block leading-tight">
                          {aboutLabel(t, r)}
                          {r.admissionNo ? (
                            <span className="mono block text-[12.5px] text-ink-3">{r.admissionNo}</span>
                          ) : null}
                        </span>
                      </td>
                      <td className="whitespace-nowrap">{formatDate(r.createdAt, locale)}</td>
                      <td className="whitespace-nowrap">
                        <span className="flex flex-col items-start gap-1">
                          {formatPlainDate(r.dueOn, locale)}
                          <DuePill request={r} />
                        </span>
                      </td>
                      <td>{r.assignedTo?.name ?? <span className="text-ink-3">{t("privacy.unassigned")}</span>}</td>
                      <td>
                        <RequestStatusPill request={r} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" data-testid="requests-cards">
              {rows.map((r) => (
                <li key={r.id}>
                  <Link href={`/app/privacy/requests/${r.id}`} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">{r.requesterName}</b>
                      <span className="block truncate text-[12.5px] text-ink-3">
                        {requestTypeLabel(t, r.type)} · {aboutLabel(t, r)}
                      </span>
                      <span className="block truncate text-[12.5px] text-ink-3">
                        {t("privacy.requests.col.due")}: {formatPlainDate(r.dueOn, locale)}
                      </span>
                    </span>
                    <span className="flex flex-col items-end gap-1">
                      <RequestStatusPill request={r} />
                      <DuePill request={r} />
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
            <div className="pager">
              <span>{t("privacy.requests.showing", { first, last, total: data.total })}</span>
              {data.total > data.size ? (
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                  >
                    <ChevronLeft size={16} aria-hidden="true" />
                    {t("common.previous")}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.min(lastPage, p + 1))}
                    disabled={page >= lastPage}
                  >
                    {t("common.next")}
                    <ChevronRight size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </div>
          </div>
        )}
      </section>
    </>
  );
}
