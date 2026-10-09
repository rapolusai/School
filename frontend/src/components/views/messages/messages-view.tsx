"use client";

import { ChevronLeft, ChevronRight, Info, Search } from "lucide-react";
import { useRef, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import { notificationsApi } from "@/lib/notifications-api";
import {
  MESSAGE_CHANNELS,
  MESSAGE_STATUSES,
  type MessageChannel,
  type MessageQuery,
  type MessageRow,
  type MessageStatus,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

export const MESSAGES_PAGE_SIZE = 25;

const STATUS_TONES: Record<MessageStatus, PillTone> = {
  QUEUED: "warn",
  SENT: "good",
  SIMULATED: "info",
  FAILED: "bad",
  SKIPPED: "neutral",
};

export function messageStatusTone(status: MessageStatus): PillTone {
  return STATUS_TONES[status] ?? "neutral";
}

type Filters = { from: string; to: string; channel: "" | MessageChannel; status: "" | MessageStatus };

const NO_FILTERS: Filters = { from: "", to: "", channel: "", status: "" };

/** The API query for the current filters. Exported for tests. */
export function toMessageQuery(filters: Filters, search: string, page: number): MessageQuery {
  return {
    from: filters.from || undefined,
    to: filters.to || undefined,
    channel: filters.channel || undefined,
    status: filters.status || undefined,
    q: search.trim() || undefined,
    page,
    size: MESSAGES_PAGE_SIZE,
  };
}

/** The school's message log (messages.read): every alert, its status and the masked recipient. */
export function MessagesView() {
  const { t } = useI18n();
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<string | null>(null);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const badRange = Boolean(filters.from && filters.to && filters.to < filters.from);
  const query = toMessageQuery(filters, search, page);
  const messages = useApiData(badRange ? null : `messages:${JSON.stringify(query)}`, () =>
    notificationsApi.listMessages(query),
  );
  const data = messages.data;
  const rows = data?.items ?? [];
  const filtered = Boolean(search.trim() || filters.from || filters.to || filters.channel || filters.status);
  const first = data && data.total > 0 ? data.page * data.size + 1 : 0;
  const last = data ? Math.min(data.total, data.page * data.size + rows.length) : 0;
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  const setFilter = <K extends keyof Filters>(key: K, value: Filters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
    setPage(0);
  };

  const onSearch = (value: string) => {
    setSearchText(value);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  return (
    <>
      <PageHead
        eyebrow={data ? plural(t, "messages.eyebrow", data.total) : t("common.loading")}
        title={t("messages.title")}
      />

      <div className="alert alert-info">
        <Info size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        <span>{t("messages.simulatedNote")}</span>
      </div>

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("messages.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(event) => onSearch(event.target.value)}
              placeholder={t("messages.search")}
              maxLength={100}
            />
          </label>
          <label className="w-[calc(50%-4px)] sm:w-auto">
            <span className="sr-only">{t("messages.filter.from")}</span>
            <input
              type="date"
              className="input"
              name="from"
              value={filters.from}
              onChange={(e) => setFilter("from", e.target.value)}
              aria-label={t("messages.filter.from")}
              title={t("messages.filter.from")}
            />
          </label>
          <label className="w-[calc(50%-4px)] sm:w-auto">
            <span className="sr-only">{t("messages.filter.to")}</span>
            <input
              type="date"
              className="input"
              name="to"
              value={filters.to}
              onChange={(e) => setFilter("to", e.target.value)}
              aria-label={t("messages.filter.to")}
              title={t("messages.filter.to")}
            />
          </label>
          <label className="min-w-[120px] flex-1 sm:flex-none">
            <span className="sr-only">{t("messages.filter.channel")}</span>
            <select
              className="input"
              value={filters.channel}
              onChange={(e) => setFilter("channel", e.target.value as Filters["channel"])}
              aria-label={t("messages.filter.channel")}
            >
              <option value="">{t("messages.filter.allChannels")}</option>
              {MESSAGE_CHANNELS.map((c) => (
                <option key={c} value={c}>
                  {translateOr(t, `messages.channel.${c}`, c)}
                </option>
              ))}
            </select>
          </label>
          <label className="min-w-[120px] flex-1 sm:flex-none">
            <span className="sr-only">{t("messages.filter.status")}</span>
            <select
              className="input"
              value={filters.status}
              onChange={(e) => setFilter("status", e.target.value as Filters["status"])}
              aria-label={t("messages.filter.status")}
            >
              <option value="">{t("messages.filter.allStatuses")}</option>
              {MESSAGE_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `messages.status.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
        </div>

        {badRange ? (
          <p className="empty">{t("messages.v.range")}</p>
        ) : messages.error && !data ? (
          <ErrorState error={messages.error} onRetry={messages.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="messages-empty">
            {filtered ? t("messages.noMatch") : t("messages.empty")}
          </p>
        ) : (
          <div aria-busy={messages.loading}>
            <MessageTable rows={rows} onOpen={setOpenId} />
            <MessageCards rows={rows} onOpen={setOpenId} />
            <div className="pager">
              <span>{t("students.showing", { first, last, total: data.total })}</span>
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

      <MessageDialog id={openId} onClose={() => setOpenId(null)} />
    </>
  );
}

function StatusPill({ status }: { status: MessageStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={messageStatusTone(status)} dot>
      {translateOr(t, `messages.status.${status}`, status)}
    </Pill>
  );
}

function statusNote(row: MessageRow, t: ReturnType<typeof useI18n>["t"], locale: string): string | null {
  if (row.status === "QUEUED" && row.nextAttemptAt) {
    return t("messages.nextAttempt", { time: formatDateTime(row.nextAttemptAt, locale) });
  }
  if ((row.status === "FAILED" || row.status === "SKIPPED" || row.status === "QUEUED") && row.lastError) {
    return row.lastError;
  }
  if (row.sentAt) return formatDateTime(row.sentAt, locale);
  return null;
}

function MessageTable({ rows, onOpen }: { rows: MessageRow[]; onOpen: (id: string) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <div className="table-wrap hidden md:block">
      <table className="table" data-testid="messages-table">
        <thead>
          <tr>
            <th scope="col">{t("messages.col.queued")}</th>
            <th scope="col">{t("messages.col.recipient")}</th>
            <th scope="col">{t("messages.col.about")}</th>
            <th scope="col">{t("messages.col.channel")}</th>
            <th scope="col">{t("messages.col.status")}</th>
            <th scope="col">
              <span className="sr-only">{t("common.actions")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((m) => {
            const note = statusNote(m, t, locale);
            return (
              <tr key={m.id}>
                <td className="whitespace-nowrap text-[13px]">{formatDateTime(m.createdAt, locale)}</td>
                <td>
                  <span className="block leading-tight">
                    {m.recipientName ?? "—"}
                    <span className="block text-[12.5px] text-ink-3 num">{m.recipient}</span>
                  </span>
                </td>
                <td>
                  <span className="block leading-tight">
                    {m.relatedLabel ?? "—"}
                    <span className="block text-[12.5px] text-ink-3">
                      {translateOr(t, `messages.template.${m.templateKey}`, m.templateKey)}
                    </span>
                  </span>
                </td>
                <td className="whitespace-nowrap">{translateOr(t, `messages.channel.${m.channel}`, m.channel)}</td>
                <td>
                  <StatusPill status={m.status} />
                  {note ? <span className="mt-1 block text-[12px] text-ink-3">{note}</span> : null}
                </td>
                <td className="r">
                  <button
                    type="button"
                    className="btn btn-sm"
                    aria-label={`${t("messages.view")} ${m.relatedLabel ?? m.recipient}`}
                    onClick={() => onOpen(m.id)}
                  >
                    {t("messages.view")}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function MessageCards({ rows, onOpen }: { rows: MessageRow[]; onOpen: (id: string) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <ul className="rowcards md:hidden" data-testid="messages-cards">
      {rows.map((m) => (
        <li key={m.id}>
          <button type="button" className="rowcard w-full text-left" onClick={() => onOpen(m.id)}>
            <span className="min-w-0 flex-1">
              <b className="block truncate font-semibold">{m.relatedLabel ?? m.recipientName ?? m.recipient}</b>
              <span className="block truncate text-[13px] text-ink-3">
                {[m.recipientName, m.recipient].filter(Boolean).join(" · ")}
              </span>
              <span className="block truncate text-[12.5px] text-ink-3">
                {[translateOr(t, `messages.channel.${m.channel}`, m.channel), formatDateTime(m.createdAt, locale)].join(
                  " · ",
                )}
              </span>
            </span>
            <StatusPill status={m.status} />
          </button>
        </li>
      ))}
    </ul>
  );
}

function MessageDialog({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const detail = useApiData(id ? `messages:detail:${id}` : null, () => notificationsApi.getMessage(id ?? ""));
  const data = detail.data?.id === id ? detail.data : undefined;
  return (
    <Dialog open={id !== null} onClose={onClose} title={t("messages.detail.title")} closeLabel={t("common.close")}>
      {detail.error && !data ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : (
        <div className="flex flex-col gap-3" data-testid="message-detail">
          <p className="msg-body" lang={data.language}>
            {data.body}
          </p>
          <dl className="kv text-[13.5px]">
            <dt>{t("messages.col.recipient")}</dt>
            <dd>
              {[data.recipientName, data.recipient].filter(Boolean).join(" · ")}
            </dd>
            <dt>{t("messages.col.about")}</dt>
            <dd>{data.relatedLabel ?? "—"}</dd>
            <dt>{t("messages.col.channel")}</dt>
            <dd>
              {translateOr(t, `messages.channel.${data.channel}`, data.channel)}
              {data.fallbackChannel
                ? ` · ${t("messages.fallback", { channel: translateOr(t, `messages.channel.${data.fallbackChannel}`, data.fallbackChannel) })}`
                : ""}
            </dd>
            <dt>{t("messages.col.status")}</dt>
            <dd>
              <StatusPill status={data.status} />
            </dd>
            <dt>{t("messages.col.queued")}</dt>
            <dd>{formatDateTime(data.createdAt, locale)}</dd>
            {data.sentAt ? (
              <>
                <dt>{t("messages.sentAt")}</dt>
                <dd>{formatDateTime(data.sentAt, locale)}</dd>
              </>
            ) : null}
            <dt>{t("messages.attempts")}</dt>
            <dd className="num">{data.attempts}</dd>
            {data.lastError ? (
              <>
                <dt>{t("messages.lastError")}</dt>
                <dd>{data.lastError}</dd>
              </>
            ) : null}
          </dl>
        </div>
      )}
    </Dialog>
  );
}
