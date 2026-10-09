"use client";

import { Newspaper, Plus, Settings2 } from "lucide-react";
import Link from "next/link";
import { useId, useRef, useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { noticesApi } from "@/lib/communication-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { CIRCULAR_STATUSES, type CircularSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { CommunicationSettingsDialog } from "./communication-settings-dialog";
import { categoryLabel, categoryTone, statusLabel, statusTone } from "./communication-labels";

export const NOTICE_TABS = ["ALL", ...CIRCULAR_STATUSES] as const;
export type NoticeTab = (typeof NOTICE_TABS)[number];

const TAB_LABELS: Record<NoticeTab, MessageKey> = {
  ALL: "notices.tab.ALL",
  DRAFT: "notices.tab.DRAFT",
  PENDING_APPROVAL: "notices.tab.PENDING_APPROVAL",
  SCHEDULED: "notices.tab.SCHEDULED",
  SENT: "notices.tab.SENT",
  WITHDRAWN: "notices.tab.WITHDRAWN",
};

/** The line under a circular's status: when it was sent, is due, or waits since. Exported for tests. */
export function statusNote(c: CircularSummary, t: Translate, locale: string): string {
  switch (c.status) {
    case "SENT":
    case "WITHDRAWN":
      return c.sentAt ? t("notices.note.sent", { time: formatDateTime(c.sentAt, locale) }) : "";
    case "SCHEDULED":
      return c.scheduledAt ? t("notices.note.scheduled", { time: formatDateTime(c.scheduledAt, locale) }) : "";
    case "PENDING_APPROVAL":
      return c.submittedAt ? t("notices.note.submitted", { time: formatDateTime(c.submittedAt, locale) }) : "";
    default:
      return c.reviewOutcome === "REJECTED"
        ? t("notices.note.sentBack")
        : t("notices.note.edited", { time: formatDateTime(c.updatedAt, locale) });
  }
}

function readLine(c: CircularSummary, t: Translate): string {
  if (c.status !== "SENT" && c.status !== "WITHDRAWN") return "—";
  return t("notices.readOf", { read: c.read, total: c.inAppRecipients });
}

/** Circulars: drafts, waiting for approval, scheduled, sent and withdrawn, with counts per status. */
export function NoticesView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  const baseId = useId();
  const [tab, setTab] = useState<NoticeTab>("ALL");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const tabRefs = useRef<Partial<Record<NoticeTab, HTMLButtonElement | null>>>({});
  const canSettings = hasPermission(me, PERMISSIONS.settingsManage);

  const list = useApiData(`notices:list:${tab}`, () => noticesApi.list(tab === "ALL" ? undefined : tab));
  const data = list.data;
  const counts = data?.counts;
  const total = counts ? CIRCULAR_STATUSES.reduce((sum, s) => sum + (counts[s] ?? 0), 0) : 0;
  const pending = counts?.PENDING_APPROVAL ?? 0;
  const items = data?.items ?? [];

  const choose = (next: NoticeTab) => {
    setTab(next);
    tabRefs.current[next]?.focus();
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const index = NOTICE_TABS.indexOf(tab);
    let next: NoticeTab | null = null;
    if (event.key === "ArrowRight") next = NOTICE_TABS[(index + 1) % NOTICE_TABS.length];
    else if (event.key === "ArrowLeft") next = NOTICE_TABS[(index - 1 + NOTICE_TABS.length) % NOTICE_TABS.length];
    if (!next) return;
    event.preventDefault();
    choose(next);
  };

  const countOf = (key: NoticeTab) => (counts ? (key === "ALL" ? total : (counts[key] ?? 0)) : null);

  return (
    <>
      <PageHead
        eyebrow={data ? plural(t, "notices.eyebrow", total) : t("common.loading")}
        title={t("notices.title")}
        actions={
          <>
            <Link href="/app/board" className="btn">
              <Newspaper size={18} aria-hidden="true" />
              {t("noticeBoard.title")}
            </Link>
            {canSettings ? (
              <button type="button" className="btn" onClick={() => setSettingsOpen(true)}>
                <Settings2 size={18} aria-hidden="true" />
                {t("notices.settings")}
              </button>
            ) : null}
            <Link href="/app/notices/new" className="btn btn-primary">
              <Plus size={18} aria-hidden="true" />
              {t("notices.new")}
            </Link>
          </>
        }
      />

      {data?.canApprove && pending > 0 && tab !== "PENDING_APPROVAL" ? (
        <div className="alert alert-info items-center" data-testid="pending-alert">
          <span className="flex-1">{plural(t, "notices.pendingAlert", pending)}</span>
          <button type="button" className="btn btn-sm" onClick={() => choose("PENDING_APPROVAL")}>
            {t("notices.pendingAlert.open")}
          </button>
        </div>
      ) : null}

      <div role="tablist" aria-label={t("notices.title")} className="tabs" onKeyDown={onKeyDown}>
        {NOTICE_TABS.map((key) => {
          const count = countOf(key);
          return (
            <button
              key={key}
              ref={(el) => {
                tabRefs.current[key] = el;
              }}
              type="button"
              role="tab"
              id={`${baseId}-tab-${key}`}
              aria-selected={tab === key}
              aria-controls={`${baseId}-panel`}
              tabIndex={tab === key ? 0 : -1}
              onClick={() => setTab(key)}
            >
              {t(TAB_LABELS[key])}
              {count !== null ? <span className="tab-count num">{count}</span> : null}
            </button>
          );
        })}
      </div>

      <section className="card" role="tabpanel" id={`${baseId}-panel`} aria-labelledby={`${baseId}-tab-${tab}`}>
        {list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : items.length === 0 ? (
          <div className="empty flex flex-col items-center gap-3" data-testid="notices-empty">
            <p>{tab === "ALL" ? t("notices.empty") : t("notices.emptyTab")}</p>
            {tab === "ALL" ? (
              <Link href="/app/notices/new" className="btn btn-primary">
                <Plus size={18} aria-hidden="true" />
                {t("notices.new")}
              </Link>
            ) : null}
          </div>
        ) : (
          <div aria-busy={list.loading}>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="notices-table">
                <thead>
                  <tr>
                    <th scope="col">{t("notices.col.circular")}</th>
                    <th scope="col">{t("notices.col.audience")}</th>
                    <th scope="col">{t("notices.col.status")}</th>
                    <th scope="col" className="r">
                      {t("notices.col.read")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((c) => (
                    <tr key={c.id}>
                      <td className="max-w-[360px]">
                        <Link href={`/app/notices/${c.id}`} className="row-title block">
                          <b className="block truncate font-semibold">{c.title}</b>
                        </Link>
                        <span className="mt-1 flex flex-wrap items-center gap-1.5 text-[12.5px] text-ink-3">
                          <Pill tone={categoryTone(c.category)}>{categoryLabel(t, c.category)}</Pill>
                          {c.source === "CALENDAR" ? t("notices.source.CALENDAR") : (c.createdByName ?? "")}
                        </span>
                      </td>
                      <td className="max-w-[260px] text-[13.5px] text-ink-2">{c.audience.label}</td>
                      <td>
                        <Pill tone={statusTone(c.status)} dot>
                          {statusLabel(t, c.status)}
                        </Pill>
                        <span className="mt-1 block text-[12px] text-ink-3">{statusNote(c, t, locale)}</span>
                      </td>
                      <td className="r num whitespace-nowrap text-[13.5px]">{readLine(c, t)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" data-testid="notices-cards">
              {items.map((c) => (
                <li key={c.id}>
                  <Link href={`/app/notices/${c.id}`} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">{c.title}</b>
                      <span className="block truncate text-[13px] text-ink-3">{c.audience.label}</span>
                      <span className="block truncate text-[12.5px] text-ink-3">{statusNote(c, t, locale)}</span>
                    </span>
                    <Pill tone={statusTone(c.status)} dot>
                      {statusLabel(t, c.status)}
                    </Pill>
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      {canSettings ? (
        <CommunicationSettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
      ) : null}
    </>
  );
}
