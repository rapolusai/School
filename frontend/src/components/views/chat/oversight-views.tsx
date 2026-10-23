"use client";

import { ArrowLeft, ChevronLeft, ChevronRight, Eye, Lock, LockOpen, Search } from "lucide-react";
import Link from "next/link";
import { useId, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadFailure, LoadingRows, PageHead, pendingTitle } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { AttachmentList } from "@/components/views/files/attachments";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { chatApi } from "@/lib/chat-api";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { ChatReport, OversightDetail, OversightFilter, OversightRow } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ChatNav, MAX_REASON_LENGTH, ReadOnlyPill, readOnlyText } from "./chat-shared";

/* Oversight for the Principal and School Admin (chat.oversee): every conversation, reports, close and reopen. */

const FILTERS: OversightFilter[] = ["all", "reported", "closed"];
const PAGE_SIZE = 20;

function StatusPills({ row }: { row: Pick<OversightRow, "closed" | "readOnly" | "readOnlyReason" | "openReports"> }) {
  const { t } = useI18n();
  return (
    <span className="flex flex-wrap gap-1.5">
      {row.readOnly ? <ReadOnlyPill reason={row.readOnlyReason} /> : <Pill tone="good">{t("chat.oversight.open")}</Pill>}
      {row.openReports > 0 ? <Pill tone="bad">{plural(t, "chat.oversight.reports", row.openReports)}</Pill> : null}
    </span>
  );
}

/** /app/chat/oversight: conversations with filters and search, and the reports waiting for review. */
export function OversightView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  const radio = useId();
  const [filter, setFilter] = useState<OversightFilter>("all");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const list = useApiData(`chat:oversight:${filter}:${q}:${page}`, () => chatApi.oversight(filter, q, page, PAGE_SIZE));
  const reports = useApiData("chat:oversight:reports", () => chatApi.reports("open"));
  const data = list.data;
  const rows = data?.items ?? [];
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  return (
    <>
      <PageHead eyebrow={me?.tenant?.name} title={t("chat.oversight.title")} />
      <ChatNav />
      <div className="alert alert-info">
        <Eye size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        <span>{t("chat.oversight.audited")}</span>
      </div>

      <ReportsCard reports={reports.data} error={reports.error} onRetry={reports.reload} onReviewed={() => {
        reports.reload();
        list.reload();
      }} />

      <section className="card flex flex-col gap-3" aria-labelledby="oversight-list">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2 id="oversight-list">{t("chat.oversight.conversations")}</h2>
        </div>
        <div className="toolbar">
          <div className="seg" role="radiogroup" aria-label={t("chat.oversight.filter")}>
            {FILTERS.map((f) => (
              <label key={f}>
                <input
                  type="radio"
                  name={radio}
                  value={f}
                  checked={filter === f}
                  onChange={() => {
                    setFilter(f);
                    setPage(0);
                  }}
                />
                {t(`chat.oversight.filter.${f}`)}
              </label>
            ))}
          </div>
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("chat.oversight.search")}</span>
            <input
              type="search"
              name="q"
              value={q}
              maxLength={100}
              placeholder={t("chat.oversight.search")}
              onChange={(e) => {
                setQ(e.target.value);
                setPage(0);
              }}
            />
          </label>
        </div>
        {list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="oversight-empty">
            {t("chat.oversight.empty")}
          </p>
        ) : (
          <>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="oversight-table">
                <thead>
                  <tr>
                    <th scope="col">{t("chat.col.student")}</th>
                    <th scope="col">{t("chat.col.parent")}</th>
                    <th scope="col">{t("chat.col.staff")}</th>
                    <th scope="col" className="r">
                      {t("chat.col.messages")}
                    </th>
                    <th scope="col">{t("chat.col.last")}</th>
                    <th scope="col">{t("chat.col.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td>
                        <Link className="link" href={`/app/chat/oversight/${encodeURIComponent(row.id)}`}>
                          {row.student.name ?? "—"}
                        </Link>
                        <span className="block text-[12.5px] text-ink-3">{row.student.classLabel}</span>
                      </td>
                      <td>{row.parentName ?? "—"}</td>
                      <td>{row.staffName ?? "—"}</td>
                      <td className="r num">{row.messageCount}</td>
                      <td>{row.lastMessageAt ? formatDateTime(row.lastMessageAt, locale) : "—"}</td>
                      <td>
                        <StatusPills row={row} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden">
              {rows.map((row) => (
                <li key={row.id}>
                  <Link className="rowcard items-start" href={`/app/chat/oversight/${encodeURIComponent(row.id)}`}>
                    <span className="min-w-0 flex-1">
                      <b className="block">{row.student.name ?? "—"}</b>
                      <span className="block text-[13px]">
                        {row.parentName ?? "—"} · {row.staffName ?? "—"}
                      </span>
                      <span className="block text-[12.5px] text-ink-3">
                        {plural(t, "chat.oversight.messages", row.messageCount)}
                        {row.lastMessageAt ? ` · ${formatDateTime(row.lastMessageAt, locale)}` : ""}
                      </span>
                    </span>
                    <StatusPills row={row} />
                  </Link>
                </li>
              ))}
            </ul>
            <div className="pager">
              <span>{t("common.showing", { shown: rows.length, total: data.total })}</span>
              {data.total > data.size ? (
                <div className="flex gap-2">
                  <button type="button" className="btn btn-sm" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
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
          </>
        )}
      </section>
    </>
  );
}

function ReportsCard({
  reports,
  error,
  onRetry,
  onReviewed,
}: {
  reports: ChatReport[] | undefined;
  error: ReturnType<typeof toApiError> | undefined;
  onRetry: () => void;
  onReviewed: () => void;
}) {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="oversight-reports" data-testid="oversight-reports">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="oversight-reports">{t("chat.oversight.reportsTitle")}</h2>
        {reports && reports.length > 0 ? <Pill tone="bad">{reports.length}</Pill> : null}
      </div>
      {error && !reports ? (
        <ErrorState error={error} onRetry={onRetry} />
      ) : !reports ? (
        <LoadingRows rows={2} />
      ) : reports.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("chat.oversight.noReports")}</p>
      ) : (
        <ReportList reports={reports} onReviewed={onReviewed} linkToConversation />
      )}
    </section>
  );
}

function ReportList({
  reports,
  onReviewed,
  linkToConversation,
}: {
  reports: ChatReport[];
  onReviewed: () => void;
  linkToConversation?: boolean;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [reviewing, setReviewing] = useState<ChatReport | null>(null);
  return (
    <>
      <ul className="rowcards">
        {reports.map((report) => (
          <li key={report.id} className="rowcard items-start" data-testid="oversight-report">
            <span className="min-w-0 flex-1">
              <b className="block">
                {t("chat.oversight.reportBy", {
                  name: report.reportedByName ?? "—",
                  side: t(report.reporterSide === "PARENT" ? "chat.side.PARENT" : "chat.side.STAFF"),
                })}
              </b>
              <span className="block whitespace-pre-wrap text-[13.5px]">{report.reason}</span>
              <span className="block text-[12.5px] text-ink-3">
                {[report.student.name, `${report.parentName ?? "—"} · ${report.staffName ?? "—"}`, formatDateTime(report.createdAt, locale)]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
              {report.status === "REVIEWED" ? (
                <span className="block text-[12.5px] text-ink-3">
                  {t("chat.oversight.reviewedBy", {
                    name: report.reviewedByName ?? "—",
                    date: formatDateTime(report.reviewedAt, locale),
                  })}
                  {report.reviewNote ? ` · ${report.reviewNote}` : ""}
                </span>
              ) : null}
            </span>
            <span className="flex flex-col items-end gap-1.5">
              {report.status === "OPEN" ? (
                <>
                  {linkToConversation ? (
                    <Link className="btn btn-sm" href={`/app/chat/oversight/${encodeURIComponent(report.conversationId)}`}>
                      {t("chat.oversight.read")}
                    </Link>
                  ) : null}
                  <button type="button" className="btn btn-sm" onClick={() => setReviewing(report)}>
                    {t("chat.oversight.review")}
                  </button>
                </>
              ) : (
                <Pill tone="good">{t("chat.oversight.reviewed")}</Pill>
              )}
            </span>
          </li>
        ))}
      </ul>
      <ReasonDialog
        open={reviewing !== null}
        title={t("chat.oversight.reviewTitle")}
        description={t("chat.oversight.reviewBody")}
        label={t("chat.oversight.reviewNote")}
        submitLabel={t("chat.oversight.review")}
        required={false}
        onClose={() => setReviewing(null)}
        onSubmit={async (note) => {
          if (!reviewing) return;
          await chatApi.review(reviewing.id, note || null);
          setReviewing(null);
          onReviewed();
        }}
      />
    </>
  );
}

/** A dialog asking for a reason or a note (closing a conversation, reviewing a report). */
function ReasonDialog({
  open,
  title,
  description,
  label,
  submitLabel,
  required,
  onClose,
  onSubmit,
}: {
  open: boolean;
  title: string;
  description: string;
  label: string;
  submitLabel: string;
  required: boolean;
  onClose: () => void;
  onSubmit: (text: string) => Promise<void>;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (required && !text.trim()) return setError(t("chat.v.reason"));
    if (text.length > MAX_REASON_LENGTH) return setError(t("chat.v.reasonLong", { max: MAX_REASON_LENGTH }));
    setBusy(true);
    setError(null);
    setFormError(null);
    try {
      await onSubmit(text.trim());
      setText("");
      toast(t("common.done"));
    } catch (caught) {
      setFormError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onClose={onClose} title={title} description={description} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate>
        <FormAlert message={formError} />
        <TextAreaField
          label={label}
          name="reason"
          rows={3}
          value={text}
          maxLength={MAX_REASON_LENGTH}
          error={error ?? undefined}
          onChange={(e) => setText(e.target.value)}
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.working") : submitLabel}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** /app/chat/oversight/[id]: the whole conversation (deleted messages too), its reports, close and reopen. */
export function OversightDetailView({ id }: { id: string }) {
  const { t } = useI18n();
  // Each load is one audited read; closing and reopening answer with the conversation without another read.
  const loaded = useApiData(`chat:oversight:read:${id}`, () => chatApi.oversightRead(id));
  const [current, setCurrent] = useState<OversightDetail | null>(null);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const data = current ?? loaded.data;

  const reopen = async () => {
    setError(null);
    try {
      setCurrent(await chatApi.reopen(id));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    }
  };

  return (
    <>
      <PageHead
        eyebrow={t("chat.oversight.title")}
        title={data ? `${data.student.name ?? "—"} · ${data.parentName ?? "—"} · ${data.staffName ?? "—"}` : pendingTitle(t, loaded.error)}
        actions={
          <>
            <Link href="/app/chat/oversight" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("chat.oversight.backToList")}
            </Link>
            {data && !data.closed && data.readOnlyReason !== "ERASED" ? (
              <button type="button" className="btn btn-danger" onClick={() => setClosing(true)}>
                <Lock size={18} aria-hidden="true" />
                {t("chat.oversight.close")}
              </button>
            ) : null}
            {data?.closed ? (
              <button type="button" className="btn" onClick={reopen}>
                <LockOpen size={18} aria-hidden="true" />
                {t("chat.oversight.reopen")}
              </button>
            ) : null}
          </>
        }
      />
      <FormAlert message={error} />
      {loaded.error && !data ? (
        <LoadFailure error={loaded.error} onRetry={loaded.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <OversightBody detail={data} onReviewed={() => {
          setCurrent(null);
          loaded.reload();
        }} />
      )}
      <ReasonDialog
        open={closing}
        title={t("chat.oversight.closeTitle")}
        description={t("chat.oversight.closeBody")}
        label={t("chat.oversight.closeReason")}
        submitLabel={t("chat.oversight.close")}
        required
        onClose={() => setClosing(false)}
        onSubmit={async (reason) => {
          setCurrent(await chatApi.close(id, reason));
          setClosing(false);
        }}
      />
    </>
  );
}

function OversightBody({ detail, onReviewed }: { detail: OversightDetail; onReviewed: () => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
      <section className="card flex flex-col gap-3" aria-labelledby="oversight-messages" data-testid="oversight-detail">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2 id="oversight-messages">{t("chat.oversight.messagesTitle")}</h2>
          <StatusPills row={{ ...detail, openReports: detail.reports.filter((r) => r.status === "OPEN").length }} />
        </div>
        {detail.readOnly ? (
          <div className="alert alert-warn">
            <span>
              {readOnlyText(t, detail.readOnlyReason)}
              {detail.closed && detail.closedByName
                ? ` ${t("chat.oversight.closedBy", { name: detail.closedByName, date: formatDateTime(detail.closedAt, locale) })}`
                : ""}
              {detail.closed && detail.closeReason ? ` ${t("chat.closedReason", { reason: detail.closeReason })}` : ""}
            </span>
          </div>
        ) : null}
        {detail.messages.length === 0 ? (
          <p className="empty">{t("chat.thread.empty")}</p>
        ) : (
          <ol className="flex flex-col gap-2.5">
            {detail.messages.map((m) => (
              <li
                key={m.id}
                className={`chat-bubble${m.side === "STAFF" ? " mine" : ""}${m.deleted || m.erased ? " gone" : ""}`}
                data-testid="oversight-message"
              >
                <b className="block text-[12.5px] not-italic">{m.senderName ?? "—"}</b>
                {m.erased ? t("chat.message.erased") : m.body}
                {m.attachments.length > 0 ? <AttachmentList files={m.attachments} /> : null}
                <span className="chat-meta">
                  <time dateTime={m.createdAt}>{formatDateTime(m.createdAt, locale)}</time>
                  {m.deleted ? (
                    <span>{t("chat.oversight.deletedAt", { date: formatDateTime(m.deletedAt, locale) })}</span>
                  ) : null}
                  {m.readAt ? <span>{t("chat.seen")}</span> : null}
                </span>
              </li>
            ))}
          </ol>
        )}
      </section>
      <section className="card flex flex-col gap-3" aria-labelledby="oversight-conv-reports">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2 id="oversight-conv-reports">{t("chat.oversight.reportsTitle")}</h2>
        </div>
        <dl className="kv text-[13.5px]">
          <dt>{t("chat.col.student")}</dt>
          <dd>{[detail.student.name, detail.student.classLabel].filter(Boolean).join(" · ")}</dd>
          <dt>{t("chat.col.parent")}</dt>
          <dd>{detail.parentName ?? "—"}</dd>
          <dt>{t("chat.col.staff")}</dt>
          <dd>{detail.staffName ?? "—"}</dd>
          <dt>{t("chat.oversight.started")}</dt>
          <dd>{formatDateTime(detail.createdAt, locale)}</dd>
        </dl>
        {detail.reports.length === 0 ? (
          <p className="text-[13.5px] text-ink-3">{t("chat.oversight.noReportsHere")}</p>
        ) : (
          <ReportList reports={detail.reports} onReviewed={onReviewed} />
        )}
      </section>
    </div>
  );
}
