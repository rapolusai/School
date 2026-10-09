"use client";

import { CheckCheck, ChevronLeft, ChevronRight, Megaphone, Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { boardApi } from "@/lib/communication-api";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { BoardItem } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { NoticeMeta, NoticeReader } from "./notice-reader";

export const BOARD_PAGE_SIZE = 20;

/** One notice in a list: title (opens the reader), pills and the first lines of the text. */
export function NoticeRow({ item, onOpen }: { item: BoardItem; onOpen: (item: BoardItem) => void }) {
  const { t } = useI18n();
  return (
    <li className="notice" data-unread={item.read ? undefined : "true"} data-testid="notice-row">
      <div className="min-w-0 flex-1">
        <h3 className="notice-title">
          <button type="button" className="notice-open" onClick={() => onOpen(item)}>
            {!item.read ? <span className="sr-only">{`${t("noticeBoard.unread")}: `}</span> : null}
            {item.title}
          </button>
        </h3>
        <NoticeMeta item={item} />
        <p className="notice-preview">{item.body}</p>
      </div>
      {!item.read ? <span className="notice-dot" aria-hidden="true" /> : null}
    </li>
  );
}

/**
 * The caller's notice board: circulars sent to them, urgent ones from the last week pinned on top, then newest
 * first. Opening a notice marks it read.
 */
export function BoardView({ initialOpenId }: { initialOpenId?: string }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<BoardItem | null>(null);
  const [linkDismissed, setLinkDismissed] = useState(false);
  const [marking, setMarking] = useState(false);
  const [markError, setMarkError] = useState<string | null>(null);
  const canSend = hasPermission(me, PERMISSIONS.noticesSend);

  const board = useApiData(`board:${page}:${unreadOnly}`, () =>
    boardApi.page({ page, size: BOARD_PAGE_SIZE, unreadOnly }),
  );
  // A notice linked from the dashboard (?open=<id>) opens on arrival.
  const linked = useApiData(initialOpenId && !linkDismissed ? `board:item:${initialOpenId}` : null, () =>
    boardApi.item(initialOpenId ?? ""),
  );
  const reading = open ?? (!linkDismissed && linked.data ? linked.data : null);

  const data = board.data;
  const items = data?.items ?? [];
  const first = data && data.total > 0 ? data.page * data.size + 1 : 0;
  const last = data ? Math.min(data.total, data.page * data.size + items.length) : 0;
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  const closeReader = () => {
    setOpen(null);
    setLinkDismissed(true);
  };

  const markAll = async () => {
    setMarking(true);
    setMarkError(null);
    try {
      const result = await boardApi.markAllRead();
      toast(plural(t, "noticeBoard.markedRead", result.marked));
      board.reload();
    } catch (caught) {
      setMarkError(errorMessage(toApiError(caught), t));
    } finally {
      setMarking(false);
    }
  };

  return (
    <>
      <PageHead
        eyebrow={data ? plural(t, "noticeBoard.unreadCount", data.unread) : t("common.loading")}
        title={t("noticeBoard.title")}
        actions={
          canSend ? (
            <>
              <Link href="/app/notices" className="btn">
                <Megaphone size={18} aria-hidden="true" />
                {t("notices.title")}
              </Link>
              <Link href="/app/notices/new" className="btn btn-primary">
                <Plus size={18} aria-hidden="true" />
                {t("notices.new")}
              </Link>
            </>
          ) : undefined
        }
      />

      <section className="card">
        <div className="toolbar">
          <div className="seg" role="radiogroup" aria-label={t("noticeBoard.show")}>
            <label>
              <input
                type="radio"
                name="show"
                value="all"
                checked={!unreadOnly}
                onChange={() => {
                  setUnreadOnly(false);
                  setPage(0);
                }}
              />
              {t("noticeBoard.show.all")}
            </label>
            <label>
              <input
                type="radio"
                name="show"
                value="unread"
                checked={unreadOnly}
                onChange={() => {
                  setUnreadOnly(true);
                  setPage(0);
                }}
              />
              {t("noticeBoard.show.unread")}
            </label>
          </div>
          {data && data.unread > 0 ? (
            <button type="button" className="btn sm:ml-auto" onClick={markAll} disabled={marking}>
              <CheckCheck size={18} aria-hidden="true" />
              {marking ? t("common.working") : t("noticeBoard.markAllRead")}
            </button>
          ) : null}
        </div>
        <FormAlert message={markError} />

        {board.error && !data ? (
          <ErrorState error={board.error} onRetry={board.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : items.length === 0 ? (
          <p className="empty" data-testid="board-empty">
            {unreadOnly ? t("noticeBoard.emptyUnread") : t("noticeBoard.empty")}
          </p>
        ) : (
          <div aria-busy={board.loading}>
            <ul className="notice-list" aria-label={t("noticeBoard.title")} data-testid="board-list">
              {items.map((item) => (
                <NoticeRow key={item.id} item={item} onOpen={setOpen} />
              ))}
            </ul>
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

      <NoticeReader item={reading} onClose={closeReader} onRead={() => board.reload()} />
    </>
  );
}
