"use client";

import { ArrowLeft, Check, CheckCheck, Flag, Paperclip, Plus, Send } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { AttachmentList, FilePicker } from "@/components/views/files/attachments";
import { toApiError, type ApiError } from "@/lib/api";
import type { ChatClient } from "@/lib/chat-api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, initials } from "@/lib/format";
import { localeFor, plural, useI18n, type Translate } from "@/lib/i18n";
import type { ChatContact, ChatMessage, ChatSide, ConversationDetail, ConversationRow, ReadOnlyReason } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import {
  availabilityNotice,
  MAX_ATTACHMENTS,
  MAX_MESSAGE_LENGTH,
  MAX_REASON_LENGTH,
  mergeMessages,
  messageTime,
  POLL_MS,
  ReadOnlyPill,
  readOnlyText,
  usePolling,
} from "./chat-shared";

/* The conversation screen for staff (/app/chat) and parents (/app/family/chat): the list and the open conversation. */

/** Newest conversation first; ones without messages yet last. Pure, exported for tests. */
export function sortConversations(rows: readonly ConversationRow[]): ConversationRow[] {
  return [...rows].sort((a, b) => (b.lastMessageAt ?? "").localeCompare(a.lastMessageAt ?? ""));
}

/** The list line under the name: "You: See you…", "Message deleted", "1 attachment". Pure, exported for tests. */
export function previewText(t: Translate, row: ConversationRow): string {
  const last = row.last;
  if (!last) return t("chat.list.noMessages");
  let text: string;
  if (last.deleted) text = t("chat.message.deleted");
  else if (last.text) text = last.text;
  else if (last.attachments > 0) text = plural(t, "chat.list.attachments", last.attachments);
  else text = t("chat.message.erased");
  return last.mine ? t("chat.list.you", { text }) : text;
}

/** The two-pane inbox: conversations on the left (or alone on a phone), the open one on the right. */
export function ChatInbox({
  client,
  side,
  initialId,
  cacheKey,
}: {
  client: ChatClient;
  side: ChatSide;
  initialId?: string;
  /** Distinguishes the staff and family lists in the data cache. */
  cacheKey: string;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const list = useApiData(`${cacheKey}:list`, client.list);
  const [selected, setSelected] = useState<string | null>(initialId ?? null);
  const [creating, setCreating] = useState(false);
  const [now, setNow] = useState(() => new Date());

  usePolling(
    () => {
      setNow(new Date());
      list.reload();
    },
    POLL_MS,
    true,
  );

  const open = (conversationId: string | null) => {
    setSelected(conversationId);
    try {
      const url = new URL(window.location.href);
      if (conversationId) url.searchParams.set("c", conversationId);
      else url.searchParams.delete("c");
      window.history.replaceState(window.history.state, "", url.toString());
    } catch {
      // The address only remembers the open conversation; the screen works without it.
    }
  };

  const rows = sortConversations(list.data ?? []);

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-2xl text-[13.5px] text-ink-2">
          {side === "PARENT" ? t("chat.family.intro") : t("chat.staff.intro")}
        </p>
        <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
          <Plus size={18} aria-hidden="true" />
          {t("chat.new")}
        </button>
      </div>
      <section className="card chatbox" data-open={selected ? "true" : "false"} data-testid="chatbox">
        <nav className="chat-list" aria-label={t("chat.list.label")}>
          {list.error && !list.data ? (
            <div className="p-4">
              <ErrorState error={list.error} onRetry={list.reload} />
            </div>
          ) : !list.data ? (
            <div className="p-4">
              <LoadingRows rows={4} />
            </div>
          ) : rows.length === 0 ? (
            <p className="empty p-4" data-testid="chat-empty">
              {side === "PARENT" ? t("chat.family.empty") : t("chat.staff.empty")}
            </p>
          ) : (
            <ul>
              {rows.map((row) => (
                <li key={row.id}>
                  <button
                    type="button"
                    className="chat-conv"
                    aria-current={row.id === selected ? "true" : undefined}
                    onClick={() => open(row.id)}
                    data-testid="chat-conversation"
                  >
                    <span className="avatar h-9 w-9" aria-hidden="true">
                      {initials(row.withName ?? "?")}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <b className="min-w-0 flex-1 truncate text-[14px]">{row.withName ?? "—"}</b>
                        {row.lastMessageAt ? (
                          <time className="flex-none text-[11.5px] text-ink-3" dateTime={row.lastMessageAt}>
                            {messageTime(row.lastMessageAt, locale, now)}
                          </time>
                        ) : null}
                      </span>
                      <span className="block truncate text-[12.5px] text-ink-3">
                        {side === "PARENT"
                          ? [row.withDetail, row.student.name].filter(Boolean).join(" · ")
                          : [row.student.name, row.student.classLabel].filter(Boolean).join(" · ")}
                      </span>
                      <span className="flex items-center gap-2">
                        <span
                          className={`min-w-0 flex-1 truncate text-[13px] ${row.unread > 0 ? "font-semibold text-ink" : "text-ink-2"}`}
                        >
                          {previewText(t, row)}
                        </span>
                        {row.readOnly ? <ReadOnlyPill reason={row.readOnlyReason} /> : null}
                        {row.away && !row.readOnly ? <Pill tone="warn">{t("chat.away")}</Pill> : null}
                        {row.unread > 0 ? (
                          <span className="chat-badge" aria-label={plural(t, "chat.unread", row.unread)}>
                            {row.unread}
                          </span>
                        ) : null}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </nav>
        <div className="chat-pane">
          {selected ? (
            <ConversationThread
              key={selected}
              client={client}
              id={selected}
              side={side}
              onBack={() => open(null)}
              onChanged={list.reload}
            />
          ) : (
            <p className="empty m-auto p-6 text-center">{t("chat.pick")}</p>
          )}
        </div>
      </section>
      <p className="text-[12.5px] text-ink-3">{t("chat.privacy")}</p>
      <NewConversationDialog
        open={creating}
        client={client}
        side={side}
        cacheKey={cacheKey}
        onClose={() => setCreating(false)}
        onOpen={(conversationId) => {
          setCreating(false);
          list.reload();
          open(conversationId);
        }}
      />
    </>
  );
}

/* ------------------------------------------------------------------ one conversation */

type ReadOnlyState = { readOnly: boolean; reason: ReadOnlyReason | null };

/**
 * An open conversation: its messages (opening it marks them read), new ones every 15 seconds while the tab is
 * visible, read receipts, deleting one's own message within 10 minutes, reporting, and the box to write.
 */
export function ConversationThread({
  client,
  id,
  side,
  onBack,
  onChanged,
}: {
  client: ChatClient;
  id: string;
  side: ChatSide;
  onBack: () => void;
  /** Something changed that the list shows (read, sent, deleted). */
  onChanged: () => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const detail = useApiData(`chat:${side}:conversation:${id}`, async () => {
    const loaded = await client.get(id);
    // Opening the conversation marked it read: the list's unread count changes.
    onChanged();
    return loaded;
  });
  const [updates, setUpdates] = useState<ChatMessage[]>([]);
  const [older, setOlder] = useState<{ messages: ChatMessage[]; hasOlder: boolean } | null>(null);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [since, setSince] = useState<string | null>(null);
  const [state, setState] = useState<ReadOnlyState | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [now, setNow] = useState(() => new Date());
  const [deleting, setDeleting] = useState<ChatMessage | null>(null);
  const [reporting, setReporting] = useState(false);
  const [reported, setReported] = useState(false);
  const [pollError, setPollError] = useState<ApiError | null>(null);
  const polling = useRef(false);
  const threadRef = useRef<HTMLOListElement>(null);

  const data = detail.data;
  const messages = data ? mergeMessages(mergeMessages(older?.messages ?? [], data.messages), updates) : [];
  const readOnly = state ?? (data ? { readOnly: data.readOnly, reason: data.readOnlyReason } : null);
  const hasOlder = older ? older.hasOlder : (data?.hasOlder ?? false);
  const lastId = messages.length ? messages[messages.length - 1].id : null;

  useEffect(() => {
    const thread = threadRef.current;
    if (thread) thread.scrollTop = thread.scrollHeight;
  }, [lastId]);

  usePolling(
    async () => {
      if (polling.current || !data) return;
      polling.current = true;
      try {
        const changes = await client.changes(id, since ?? data.serverTime);
        setNow(new Date());
        setSince(changes.serverTime);
        setState({ readOnly: changes.readOnly, reason: changes.readOnlyReason });
        setPollError(null);
        if (changes.messages.length) {
          setUpdates((prev) => mergeMessages(prev, changes.messages));
          onChanged();
        }
      } catch (caught) {
        setPollError(toApiError(caught));
      } finally {
        polling.current = false;
      }
    },
    POLL_MS,
    Boolean(data),
  );

  const loadOlder = async () => {
    const oldest = messages[0];
    if (!oldest || loadingOlder) return;
    setLoadingOlder(true);
    try {
      const page = await client.get(id, oldest.createdAt);
      setOlder((prev) => ({ messages: mergeMessages(prev?.messages ?? [], page.messages), hasOlder: page.hasOlder }));
    } catch (caught) {
      setPollError(toApiError(caught));
    } finally {
      setLoadingOlder(false);
    }
  };

  if (detail.error && !data) {
    return (
      <div className="p-4">
        <BackButton onBack={onBack} />
        <ErrorState error={detail.error} onRetry={detail.reload} />
      </div>
    );
  }
  if (!data) {
    return (
      <div className="p-4">
        <LoadingRows rows={5} />
      </div>
    );
  }

  const otherName = side === "PARENT" ? data.staffName : data.parentName;
  const sub =
    side === "PARENT"
      ? [data.staffDetail, data.student.name].filter(Boolean).join(" · ")
      : [data.student.name, data.student.classLabel].filter(Boolean).join(" · ");
  const hours = side === "PARENT" ? availabilityNotice(t, data.staffAvailability, locale) : null;
  const alreadyReported = data.reportedByMe || reported;

  return (
    <>
      <div className="chat-pane-head">
        <BackButton onBack={onBack} />
        <span className="avatar h-9 w-9" aria-hidden="true">
          {initials(otherName ?? "?")}
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-[15px]" data-testid="chat-with">
            {otherName ?? "—"}
          </h2>
          <p className="truncate text-[12.5px] text-ink-3">{sub}</p>
        </div>
        {alreadyReported ? (
          <Pill tone="warn">{t("chat.reported")}</Pill>
        ) : (
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => setReporting(true)}>
            <Flag size={16} aria-hidden="true" />
            <span className="max-sm:sr-only">{t("chat.report")}</span>
          </button>
        )}
      </div>
      {hours && !readOnly?.readOnly ? (
        <div className="alert alert-info mx-3 mt-3" data-testid="chat-hours">
          <span>{hours}</span>
        </div>
      ) : null}
      <ol className="chat-thread" ref={threadRef} aria-label={t("chat.thread.label")} aria-live="polite">
        {hasOlder ? (
          <li className="self-center">
            <button type="button" className="btn btn-sm" onClick={loadOlder} disabled={loadingOlder}>
              {loadingOlder ? t("common.loading") : t("chat.older")}
            </button>
          </li>
        ) : null}
        {messages.length === 0 ? <li className="empty m-auto">{t("chat.thread.empty")}</li> : null}
        {messages.map((message) => (
          <MessageBubble
            key={message.id}
            message={message}
            now={now}
            locale={locale}
            onDelete={readOnly?.readOnly ? undefined : () => setDeleting(message)}
          />
        ))}
      </ol>
      {pollError ? (
        <p className="px-4 pb-2 text-[12.5px] text-ink-3" role="status">
          {t("chat.offline")}
        </p>
      ) : null}
      {readOnly?.readOnly ? (
        <div className="chat-composer" data-testid="chat-read-only">
          <div className="alert alert-warn">
            <span>
              {readOnlyText(t, readOnly.reason)}
              {readOnly.reason === "CLOSED" && data.closeReason ? ` ${t("chat.closedReason", { reason: data.closeReason })}` : ""}
            </span>
          </div>
        </div>
      ) : (
        <Composer
          client={client}
          id={id}
          notice={notice}
          onSent={(sent, availabilityText) => {
            setUpdates((prev) => mergeMessages(prev, [sent]));
            setNotice(availabilityText);
            onChanged();
          }}
          onReadOnly={() => detail.reload()}
        />
      )}
      <ConfirmDialog
        open={deleting !== null}
        title={t("chat.delete.title")}
        body={t("chat.delete.body")}
        confirmLabel={t("chat.delete.confirm")}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          const removed = await client.remove(id, deleting.id);
          setUpdates((prev) => mergeMessages(prev, [removed]));
          setDeleting(null);
          onChanged();
        }}
      />
      <ReportDialog
        open={reporting}
        onClose={() => setReporting(false)}
        onReport={async (reason) => {
          await client.report(id, reason);
          setReported(true);
          setReporting(false);
        }}
      />
    </>
  );
}

function BackButton({ onBack }: { onBack: () => void }) {
  const { t } = useI18n();
  return (
    <button type="button" className="iconbtn md:hidden" onClick={onBack} aria-label={t("chat.back")}>
      <ArrowLeft size={20} aria-hidden="true" />
    </button>
  );
}

function MessageBubble({
  message,
  now,
  locale,
  onDelete,
}: {
  message: ChatMessage;
  now: Date;
  locale: string;
  onDelete?: () => void;
}) {
  const { t } = useI18n();
  const gone = message.deleted || message.erased;
  const deletable =
    onDelete !== undefined &&
    message.mine &&
    message.canDelete &&
    !gone &&
    message.deletableUntil !== null &&
    new Date(message.deletableUntil).getTime() > now.getTime();
  return (
    <li
      className={`chat-bubble${message.mine ? " mine" : ""}${gone ? " gone" : ""}`}
      data-testid="chat-message"
      data-mine={message.mine ? "true" : "false"}
    >
      {message.deleted ? t("chat.message.deleted") : message.erased ? t("chat.message.erased") : message.body}
      {!gone && message.attachments.length > 0 ? <AttachmentList files={message.attachments} /> : null}
      <span className="chat-meta">
        <time dateTime={message.createdAt}>{messageTime(message.createdAt, locale, now)}</time>
        {message.mine && !gone ? (
          message.readAt ? (
            <span className="inline-flex items-center gap-0.5" data-testid="chat-seen">
              <CheckCheck size={14} aria-hidden="true" />
              {t("chat.seen")}
            </span>
          ) : (
            <span className="inline-flex items-center gap-0.5">
              <Check size={14} aria-hidden="true" />
              {t("chat.sent")}
            </span>
          )
        ) : null}
        {deletable ? (
          <button type="button" className="chat-delete" onClick={onDelete}>
            {t("chat.delete")}
          </button>
        ) : null}
      </span>
    </li>
  );
}

/** What is wrong with a message before it is sent, as a message key; null when it may go. Pure, exported for tests. */
export function messageProblem(body: string, files: readonly File[]): "chat.v.empty" | "chat.v.long" | null {
  if (!body.trim() && files.length === 0) return "chat.v.empty";
  if (body.length > MAX_MESSAGE_LENGTH) return "chat.v.long";
  return null;
}

function Composer({
  client,
  id,
  notice,
  onSent,
  onReadOnly,
}: {
  client: ChatClient;
  id: string;
  notice: string | null;
  onSent: (message: ChatMessage, availabilityText: string | null) => void;
  onReadOnly: () => void;
}) {
  const { t, lang } = useI18n();
  const [body, setBody] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [attach, setAttach] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  const send = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problem = messageProblem(body, files);
    if (problem) {
      setError(t(problem, { max: MAX_MESSAGE_LENGTH }));
      return;
    }
    if (sending) return;
    setSending(true);
    setError(null);
    try {
      const sent = await client.send(id, body, files);
      setBody("");
      setFiles([]);
      setAttach(false);
      onSent(sent.message, availabilityNotice(t, sent.staffAvailability, localeFor(lang)));
    } catch (caught) {
      const failure = toApiError(caught);
      if (failure.status === 429) setError(t("chat.v.rate"));
      else if (failure.errors?.body) setError(failure.errors.body);
      else if (failure.errors?.files) setError(failure.errors.files);
      else setError(errorMessage(failure, t));
      if (failure.status === 409) onReadOnly();
    } finally {
      setSending(false);
    }
  };

  return (
    <form method="post" className="chat-composer" onSubmit={send} aria-label={t("chat.compose.label")} noValidate>
      {notice ? (
        <div className="alert alert-info" data-testid="chat-notice">
          <span>{notice}</span>
        </div>
      ) : null}
      <FormAlert message={error} />
      {attach ? (
        <FilePicker files={files} onChange={setFiles} max={MAX_ATTACHMENTS} label={t("chat.attachments")} />
      ) : null}
      <div className="flex items-end gap-2">
        <button
          type="button"
          className="iconbtn"
          aria-label={t("chat.attach")}
          aria-pressed={attach}
          onClick={() => setAttach((a) => !a)}
        >
          <Paperclip size={20} aria-hidden="true" />
        </button>
        <textarea
          className="input min-w-0 flex-1"
          name="body"
          rows={2}
          value={body}
          maxLength={MAX_MESSAGE_LENGTH}
          placeholder={t("chat.compose.placeholder")}
          aria-label={t("chat.compose.label")}
          aria-invalid={error ? true : undefined}
          onChange={(e) => {
            setBody(e.target.value);
            if (error) setError(null);
          }}
        />
        <button type="submit" className="btn btn-primary" disabled={sending}>
          <Send size={18} aria-hidden="true" />
          <span className="max-sm:sr-only">{t("chat.send")}</span>
        </button>
      </div>
    </form>
  );
}

function ReportDialog({
  open,
  onClose,
  onReport,
}: {
  open: boolean;
  onClose: () => void;
  onReport: (reason: string) => Promise<void>;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!reason.trim()) return setError(t("chat.v.reason"));
    if (reason.length > MAX_REASON_LENGTH) return setError(t("chat.v.reasonLong", { max: MAX_REASON_LENGTH }));
    setBusy(true);
    setError(null);
    try {
      await onReport(reason.trim());
      setReason("");
      toast(t("chat.report.done"));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t("chat.report.title")}
      description={t("chat.report.body")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate>
        <TextAreaField
          label={t("chat.report.reason")}
          name="reason"
          rows={3}
          value={reason}
          maxLength={MAX_REASON_LENGTH}
          error={error ?? undefined}
          onChange={(e) => setReason(e.target.value)}
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.working") : t("chat.report.send")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ starting a conversation */

function NewConversationDialog({
  open,
  client,
  side,
  cacheKey,
  onClose,
  onOpen,
}: {
  open: boolean;
  client: ChatClient;
  side: ChatSide;
  cacheKey: string;
  onClose: () => void;
  onOpen: (conversationId: string) => void;
}) {
  const { t } = useI18n();
  const [sectionId, setSectionId] = useState<string | null>(null);
  const contacts = useApiData(open ? `${cacheKey}:contacts:${sectionId ?? ""}` : null, () => client.contacts(sectionId));
  const [starting, setStarting] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const data = contacts.data;

  const start = async (contact: ChatContact) => {
    if (contact.conversationId) return onOpen(contact.conversationId);
    const key = `${contact.studentId}:${contact.personId}`;
    setStarting(key);
    setError(null);
    try {
      const conversation: ConversationDetail = await client.start(contact.studentId, contact.personId);
      onOpen(conversation.id);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setStarting(null);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t("chat.new.title")}
      description={side === "PARENT" ? t("chat.new.family") : t("chat.new.staff")}
      closeLabel={t("common.close")}
    >
      <div className="flex flex-col gap-3" data-testid="chat-contacts">
        {side === "STAFF" && data && data.sections.length > 0 ? (
          <SelectField
            label={t("chat.new.section")}
            name="sectionId"
            value={data.sectionId ?? ""}
            options={data.sections.map((s) => ({ value: s.id, label: s.label }))}
            onChange={(e) => setSectionId(e.target.value)}
          />
        ) : null}
        <FormAlert message={error} />
        {contacts.error && !data ? (
          <ErrorState error={contacts.error} onRetry={contacts.reload} />
        ) : !data || contacts.loading ? (
          <LoadingRows rows={3} />
        ) : data.contacts.length === 0 ? (
          <p className="empty">
            {side === "PARENT"
              ? t("chat.new.noTeachers")
              : data.sections.length === 0
                ? t("chat.new.noSections")
                : t("chat.new.noParents")}
          </p>
        ) : (
          <ContactList contacts={data.contacts} side={side} starting={starting} onStart={start} />
        )}
      </div>
    </Dialog>
  );
}

function ContactList({
  contacts,
  side,
  starting,
  onStart,
}: {
  contacts: ChatContact[];
  side: ChatSide;
  starting: string | null;
  onStart: (contact: ChatContact) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <ul className="rowcards max-h-[55vh] overflow-y-auto">
      {contacts.map((contact) => {
        const key = `${contact.studentId}:${contact.personId}`;
        return (
          <li key={key} className="rowcard" data-testid="chat-contact">
            <span className="avatar h-9 w-9" aria-hidden="true">
              {initials(contact.personName ?? "?")}
            </span>
            <span className="min-w-0 flex-1">
              <b className="block truncate">{contact.personName ?? "—"}</b>
              <span className="block truncate text-[12.5px] text-ink-3">
                {side === "PARENT"
                  ? [contact.detail, contact.studentName].filter(Boolean).join(" · ")
                  : t("chat.new.parentOf", { name: contact.studentName ?? "—" })}
              </span>
              {contact.awayUntil ? (
                <span className="block text-[12.5px] text-warn">
                  {t("chat.notice.away", { date: formatPlainDate(contact.awayUntil, locale) })}
                </span>
              ) : null}
            </span>
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => onStart(contact)}
              disabled={starting !== null}
              aria-label={t("chat.new.messageTo", { name: contact.personName ?? "", student: contact.studentName ?? "" })}
            >
              {starting === key ? t("common.working") : contact.conversationId ? t("chat.new.open") : t("chat.new.message")}
            </button>
          </li>
        );
      })}
    </ul>
  );
}
