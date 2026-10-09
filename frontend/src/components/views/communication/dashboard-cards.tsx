"use client";

import { ArrowRight, CalendarDays, Newspaper } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { boardApi, calendarApi } from "@/lib/communication-api";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { BoardItem } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { entryAudience, entryWhen, kindLabel } from "./communication-labels";
import { NoticeMeta, NoticeReader } from "./notice-reader";

export const DASHBOARD_NOTICES = 3;
export const DASHBOARD_UPCOMING = 5;

/** Every dashboard: the latest unread notices, opened in place. */
export function NoticesCard() {
  const { t } = useI18n();
  const [open, setOpen] = useState<BoardItem | null>(null);
  const board = useApiData("board:dashboard", () => boardApi.page({ size: DASHBOARD_NOTICES, unreadOnly: true }));
  const data = board.data;

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="notices-card-heading" data-testid="notices-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="notices-card-heading" className="flex items-center gap-2">
          <Newspaper size={18} className="text-ink-3" aria-hidden="true" />
          {t("noticeBoard.card.title")}
        </h2>
        {data && data.unread > 0 ? (
          <Pill tone="accent">{plural(t, "noticeBoard.unreadCount", data.unread)}</Pill>
        ) : null}
      </div>
      {board.error && !data ? (
        <ErrorState error={board.error} onRetry={board.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-ink-2">{t("noticeBoard.card.empty")}</p>
      ) : (
        <ul className="list">
          {data.items.map((item) => (
            <li key={item.id} className="li flex-col gap-1.5">
              <button type="button" className="notice-open text-left font-semibold" onClick={() => setOpen(item)}>
                {item.title}
              </button>
              <NoticeMeta item={item} />
            </li>
          ))}
        </ul>
      )}
      <Link href="/app/board" className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
        {t("noticeBoard.card.link")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
      <NoticeReader item={open} onClose={() => setOpen(null)} onRead={() => board.reload()} />
    </section>
  );
}

/** Every dashboard: the next calendar entries meant for the viewer. */
export function UpcomingCard() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const upcoming = useApiData("calendar:upcoming", () => calendarApi.upcoming(DASHBOARD_UPCOMING));
  const entries = upcoming.data;
  const month = new Intl.DateTimeFormat(locale, { month: "short", timeZone: "UTC" });

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="upcoming-card-heading" data-testid="upcoming-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="upcoming-card-heading" className="flex items-center gap-2">
          <CalendarDays size={18} className="text-ink-3" aria-hidden="true" />
          {t("calendar.upcoming.title")}
        </h2>
      </div>
      {upcoming.error && !entries ? (
        <ErrorState error={upcoming.error} onRetry={upcoming.reload} />
      ) : !entries ? (
        <LoadingRows rows={3} />
      ) : entries.length === 0 ? (
        <p className="text-sm text-ink-2">{t("calendar.upcoming.empty")}</p>
      ) : (
        <ul className="list">
          {entries.map((entry) => (
            <li key={entry.id} className="li items-center">
              <span className={`cal-badge kind-${entry.kind}`} aria-hidden="true">
                <b className="num">{Number(entry.startsOn.slice(8))}</b>
                <span>{month.format(new Date(`${entry.startsOn}T00:00:00Z`))}</span>
              </span>
              <div className="min-w-0 flex-1">
                <p className="font-semibold leading-tight text-ink">{entry.title}</p>
                <p className="text-[13px] text-ink-3">
                  {[kindLabel(t, entry.kind), entryWhen(entry, t, locale), entryAudience(entry, t)].join(" · ")}
                </p>
              </div>
            </li>
          ))}
        </ul>
      )}
      <Link href="/app/calendar" className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
        {t("calendar.upcoming.link")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </section>
  );
}
