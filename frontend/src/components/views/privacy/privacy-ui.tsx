"use client";

import { History } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useId, useState } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { formatDateTime } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import type {
  ConsentStatus,
  DataRequestDetail,
  DataRequestRow,
  GrievanceOfficer,
  PrivacyNotice,
  PrivacyPurpose,
  PurposeState,
  RequestEvent,
  RequestStatus,
  RequestType,
} from "@/lib/types";

/* ------------------------------------------------------------------ the notice text */

export type NoticeBlock =
  | { kind: "heading"; text: string }
  | { kind: "list"; items: string[] }
  | { kind: "paragraph"; text: string };

/**
 * Splits a notice into blocks: a line starting with "## " is a heading, consecutive lines
 * starting with "- " form a list, and other lines are paragraphs (blank lines separate them).
 * Plain text only: nothing is ever rendered as HTML. Pure; exported for tests.
 */
export function parseNotice(text: string): NoticeBlock[] {
  const blocks: NoticeBlock[] = [];
  let paragraph: string[] = [];
  const flush = () => {
    if (paragraph.length) blocks.push({ kind: "paragraph", text: paragraph.join(" ") });
    paragraph = [];
  };
  for (const raw of text.replace(/\r\n?/g, "\n").split("\n")) {
    const line = raw.trim();
    if (!line) {
      flush();
    } else if (line.startsWith("## ")) {
      flush();
      blocks.push({ kind: "heading", text: line.slice(3).trim() });
    } else if (line.startsWith("- ")) {
      flush();
      const last = blocks[blocks.length - 1];
      if (last && last.kind === "list") last.items.push(line.slice(2).trim());
      else blocks.push({ kind: "list", items: [line.slice(2).trim()] });
    } else {
      paragraph.push(line);
    }
  }
  flush();
  return blocks;
}

/** A notice rendered from its plain text. */
export function NoticeText({ text, lang }: { text: string; lang?: string }) {
  return (
    <div className="flex flex-col gap-2 text-[14.5px] leading-relaxed" lang={lang} data-testid="notice-text">
      {parseNotice(text).map((block, index) =>
        block.kind === "heading" ? (
          <h3 key={index} className="mt-2 text-[15.5px] font-semibold">
            {block.text}
          </h3>
        ) : block.kind === "list" ? (
          <ul key={index} className="ml-5 list-disc space-y-1">
            {block.items.map((item, i) => (
              <li key={i}>{item}</li>
            ))}
          </ul>
        ) : (
          <p key={index}>{block.text}</p>
        ),
      )}
    </div>
  );
}

/** The grievance officer's contact details, with mail and phone links. */
export function OfficerContact({ officer }: { officer: GrievanceOfficer }) {
  const { t } = useI18n();
  return (
    <dl className="kv" data-testid="grievance-officer">
      <dt>{t("privacy.officer.name")}</dt>
      <dd>{officer.name}</dd>
      <dt>{t("privacy.officer.email")}</dt>
      <dd>
        <a className="link" href={`mailto:${officer.email}`}>
          {officer.email}
        </a>
      </dd>
      <dt>{t("privacy.officer.phone")}</dt>
      <dd>
        <a className="link" href={`tel:${officer.phone.replace(/[^\d+]/g, "")}`}>
          {officer.phone}
        </a>
      </dd>
    </dl>
  );
}

/** English or Hindi for a notice, whatever the app's own language. */
function NoticeLanguage({ value, onChange }: { value: "en" | "hi"; onChange: (lang: "en" | "hi") => void }) {
  const { t } = useI18n();
  const name = useId();
  return (
    <div className="seg self-start" role="radiogroup" aria-label={t("privacy.notice.lang")}>
      {(["en", "hi"] as const).map((code) => (
        <label key={code}>
          <input type="radio" name={name} value={code} checked={value === code} onChange={() => onChange(code)} />
          {t(code === "en" ? "privacy.lang.en" : "privacy.lang.hi")}
        </label>
      ))}
    </div>
  );
}

/**
 * One notice version in either language, with its grievance officer. `scroll` keeps a long notice
 * inside a box (in cards and dialogs); the public page lets it run the full page.
 */
export function NoticeBody({
  notice,
  scroll = true,
}: {
  notice: Pick<PrivacyNotice, "bodyEn" | "bodyHi"> & { grievanceOfficer: GrievanceOfficer };
  scroll?: boolean;
}) {
  const { t, lang } = useI18n();
  const [shown, setShown] = useState<"en" | "hi">(lang === "hi" ? "hi" : "en");
  return (
    <div className="flex flex-col gap-3">
      <NoticeLanguage value={shown} onChange={setShown} />
      <div
        className={scroll ? "max-h-[480px] overflow-y-auto rounded-lg border border-line p-4" : undefined}
        tabIndex={scroll ? 0 : undefined}
      >
        <NoticeText text={shown === "hi" ? notice.bodyHi : notice.bodyEn} lang={shown} />
      </div>
      <h3 className="text-[14px] font-semibold">{t("privacy.officer.title")}</h3>
      <OfficerContact officer={notice.grievanceOfficer} />
    </div>
  );
}

/* ------------------------------------------------------------------ labels and pills */

export function requestTypeLabel(t: Translate, type: RequestType): string {
  return translateOr(t, `privacy.type.${type}`, type);
}

export function purposeLabel(t: Translate, purpose: PrivacyPurpose): string {
  return translateOr(t, `privacy.purpose.${purpose}`, purpose);
}

const STATUS_TONES: Record<RequestStatus, PillTone> = { SUBMITTED: "info", IN_PROGRESS: "warn", CLOSED: "neutral" };

export function RequestStatusPill({ request }: { request: Pick<DataRequestRow, "status" | "resolution"> }) {
  const { t } = useI18n();
  if (request.status === "CLOSED" && request.resolution) {
    return (
      <Pill tone={request.resolution === "COMPLETED" ? "good" : "bad"} dot>
        {translateOr(t, `privacy.resolution.${request.resolution}`, request.resolution)}
      </Pill>
    );
  }
  return (
    <Pill tone={STATUS_TONES[request.status] ?? "neutral"} dot>
      {translateOr(t, `privacy.status.${request.status}`, request.status)}
    </Pill>
  );
}

/** "12 days left", "Due today" or "3 days overdue" for an open request; nothing once closed. */
export function dueLabel(t: Translate, request: Pick<DataRequestRow, "status" | "daysLeft">): string {
  if (request.status === "CLOSED") return "";
  if (request.daysLeft === 0) return t("privacy.due.today");
  if (request.daysLeft < 0) return plural(t, "privacy.due.overdue", -request.daysLeft);
  return plural(t, "privacy.due.left", request.daysLeft);
}

export function DuePill({ request }: { request: Pick<DataRequestRow, "status" | "daysLeft" | "overdue"> }) {
  const { t } = useI18n();
  if (request.status === "CLOSED") return null;
  const tone: PillTone = request.overdue ? "bad" : request.daysLeft <= 5 ? "warn" : "neutral";
  return <Pill tone={tone}>{dueLabel(t, request)}</Pill>;
}

const CONSENT_TONES: Record<ConsentStatus, PillTone> = {
  GIVEN: "good",
  DECLINED: "neutral",
  WITHDRAWN: "warn",
  NONE: "neutral",
};

/** A purpose's state; an essential consent from an older notice version is shown as needing renewal. */
export function ConsentPill({ state }: { state: PurposeState }) {
  const { t } = useI18n();
  const stale = state.purpose === "ESSENTIAL" && state.status === "GIVEN" && !state.current;
  return (
    <Pill tone={stale ? "warn" : CONSENT_TONES[state.status]} dot={state.status === "GIVEN"}>
      {stale ? t("privacy.consent.renew") : translateOr(t, `privacy.consent.${state.status}`, state.status)}
    </Pill>
  );
}

/** "About" for a request: the child (with admission number) or the parent themselves. */
export function aboutLabel(t: Translate, request: Pick<DataRequestRow, "subject" | "studentName">): string {
  return request.subject === "SELF" ? t("privacy.about.self") : (request.studentName ?? "");
}

/* ------------------------------------------------------------------ the request timeline */

const EVENT_KEYS: Record<RequestEvent["kind"], MessageKey> = {
  SUBMITTED: "privacy.event.SUBMITTED",
  ASSIGNED: "privacy.event.ASSIGNED",
  STAFF_REPLY: "privacy.event.STAFF_REPLY",
  PARENT_REPLY: "privacy.event.PARENT_REPLY",
  EXPORT_READY: "privacy.event.EXPORT_READY",
  EXPORT_DOWNLOADED: "privacy.event.EXPORT_DOWNLOADED",
  ERASED: "privacy.event.ERASED",
  CLOSED: "privacy.event.CLOSED",
};

export function RequestTimeline({ request }: { request: Pick<DataRequestDetail, "events"> }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  return (
    <ol className="timeline" data-testid="request-timeline">
      {[...request.events].reverse().map((event) => (
        <li key={event.id} className="timeline-item">
          <span className="timeline-dot" aria-hidden="true">
            <History size={14} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="font-semibold">{t(EVENT_KEYS[event.kind])}</p>
            {event.body ? <p className="mt-0.5 whitespace-pre-line break-words text-[14px]">{event.body}</p> : null}
            <p className="mt-0.5 text-[12.5px] text-ink-3">
              {event.actorName ?? t("common.system")} ·{" "}
              <time dateTime={event.at}>{formatDateTime(event.at, locale)}</time>
            </p>
          </div>
        </li>
      ))}
    </ol>
  );
}

/* ------------------------------------------------------------------ staff section navigation */

type PrivacyLink = { href: string; labelKey: MessageKey; exact?: boolean };

const PRIVACY_LINKS: PrivacyLink[] = [
  { href: "/app/privacy", labelKey: "privacy.nav.requests", exact: true },
  { href: "/app/privacy/notice", labelKey: "privacy.nav.notice" },
  { href: "/app/privacy/consents", labelKey: "privacy.nav.consents" },
];

/** The data protection section's own navigation, under each staff page's heading. */
export function PrivacyNav() {
  const { t } = useI18n();
  const pathname = usePathname() ?? "";
  return (
    <nav className="subnav" aria-label={t("privacy.nav.label")}>
      {PRIVACY_LINKS.map((link) => {
        const active = link.exact
          ? pathname === link.href || pathname.startsWith("/app/privacy/requests")
          : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

/** Kilobytes, at least 1, for an export's size. */
export function sizeInKb(bytes: number): number {
  return Math.max(1, Math.round(bytes / 1024));
}
