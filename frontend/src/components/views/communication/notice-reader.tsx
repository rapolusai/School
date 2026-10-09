"use client";

import { CalendarDays, Pin } from "lucide-react";
import { useEffect, useEffectEvent } from "react";
import { Dialog } from "@/components/ui/dialog";
import { Pill } from "@/components/ui/pill";
import { boardApi } from "@/lib/communication-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import type { BoardItem } from "@/lib/types";
import { categoryLabel, categoryTone } from "./communication-labels";

/** The pills and the "sent by" line shown with a notice on the board, in the reader and on the dashboard. */
export function NoticeMeta({ item }: { item: BoardItem }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[12.5px] text-ink-3">
      <Pill tone={categoryTone(item.category)}>{categoryLabel(t, item.category)}</Pill>
      {item.pinned ? (
        <Pill tone="bad">
          <Pin size={12} aria-hidden="true" />
          {t("noticeBoard.pinned")}
        </Pill>
      ) : null}
      {item.calendarReminder ? (
        <Pill tone="info">
          <CalendarDays size={12} aria-hidden="true" />
          {t("noticeBoard.reminder")}
        </Pill>
      ) : null}
      <span>
        {item.sentByName
          ? t("noticeBoard.sentBy", { time: formatDateTime(item.sentAt, locale), name: item.sentByName })
          : formatDateTime(item.sentAt, locale)}
      </span>
    </div>
  );
}

/**
 * Reads one notice in a dialog. Opening an unread notice records the read receipt (the first read only) and tells
 * the caller, so lists and counts can refresh.
 */
export function NoticeReader({
  item,
  onClose,
  onRead,
}: {
  item: BoardItem | null;
  onClose: () => void;
  onRead: (item: BoardItem) => void;
}) {
  const { t } = useI18n();
  const notifyRead = useEffectEvent((read: BoardItem) => onRead(read));
  const unreadId = item && !item.read ? item.id : null;

  useEffect(() => {
    if (!unreadId) return;
    let cancelled = false;
    boardApi.markRead(unreadId).then(
      (read) => {
        if (!cancelled) notifyRead(read);
      },
      () => {
        // The receipt is retried the next time the notice is opened.
      },
    );
    return () => {
      cancelled = true;
    };
  }, [unreadId]);

  return (
    <Dialog open={item !== null} onClose={onClose} title={item?.title ?? ""} closeLabel={t("common.close")}>
      {item ? (
        <div className="flex flex-col gap-3" data-testid="notice-reader">
          <NoticeMeta item={item} />
          <p className="msg-body">{item.body}</p>
          <div className="flex justify-end">
            <button type="button" className="btn" onClick={onClose} data-autofocus>
              {t("common.close")}
            </button>
          </div>
        </div>
      ) : null}
    </Dialog>
  );
}
