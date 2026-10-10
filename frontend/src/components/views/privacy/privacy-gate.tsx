"use client";

import { ShieldCheck } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { createContext, useContext, useMemo, useState } from "react";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { classLabel, formatDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { isParentUser } from "@/lib/permissions";
import { privacyApi } from "@/lib/privacy-api";
import { OPTIONAL_PURPOSES, type OptionalPurpose, type ParentPrivacy, type PrivacyNotice } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { NoticeText, OfficerContact, purposeLabel } from "./privacy-ui";

/** Lets a page that accepts the notice itself (the parent's privacy page) open the gate. */
const GateContext = createContext<{ accepted: (next: ParentPrivacy) => void } | null>(null);

export function usePrivacyGate() {
  return useContext(GateContext);
}

/** Pages a parent can open before accepting: their privacy page and requests (to ask or complain instead). */
export function gateExempt(pathname: string): boolean {
  return pathname === "/app/my-privacy" || pathname.startsWith("/app/my-privacy/");
}

/**
 * For parents only: until they accept the school's current privacy notice for every child at
 * school, the app shows the notice and the consent choices instead of the page. Staff, students
 * and parents who have accepted see the page. If the check itself fails the page is shown: the
 * gate is a screen, not access control.
 */
export function PrivacyGate({ children }: { children: React.ReactNode }) {
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const parent = isParentUser(me);
  const privacy = useApiData(parent && me ? `privacy:gate:${me.id}` : null, privacyApi.mine);
  const [accepted, setAccepted] = useState<ParentPrivacy | null>(null);
  const value = useMemo(() => ({ accepted: (next: ParentPrivacy) => setAccepted(next) }), []);
  const data = accepted ?? privacy.data;

  let content: React.ReactNode = children;
  if (parent && !gateExempt(pathname)) {
    if (!data && !privacy.error) {
      content = (
        <section className="card" aria-busy="true">
          <LoadingRows rows={4} />
        </section>
      );
    } else if (data?.needsConsent && data.notice) {
      content = (
        <ConsentForm
          key={data.notice.version}
          privacy={{ ...data, notice: data.notice }}
          onAccepted={setAccepted}
          onStale={() => {
            setAccepted(null);
            privacy.reload();
          }}
        />
      );
    }
  }
  return <GateContext.Provider value={value}>{content}</GateContext.Provider>;
}

type Choices = Record<string, Record<OptionalPurpose, boolean>>;

/**
 * The notice with an "I agree" for essential school records and, for each child who needs it,
 * the optional purposes. Optional choices start unticked: agreeing must be the parent's own act.
 */
export function ConsentForm({
  privacy,
  onAccepted,
  onStale,
  embedded = false,
}: {
  privacy: ParentPrivacy & { notice: PrivacyNotice };
  onAccepted: (next: ParentPrivacy) => void;
  onStale?: () => void;
  /** Inside the parent's privacy page: a section heading, and no link to that page. */
  embedded?: boolean;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const notice = privacy.notice;
  const pending = privacy.children.filter((c) => c.needsConsent);
  const [essential, setEssential] = useState(false);
  const [choices, setChoices] = useState<Choices>(() =>
    Object.fromEntries(pending.map((c) => [c.studentId, { PHOTOS: false, WHATSAPP: false }])),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [essentialError, setEssentialError] = useState<string | null>(null);
  const renewing = pending.some((c) => c.history.length > 0);
  const Heading = embedded ? "h2" : "h1";

  const pick = (studentId: string, purpose: OptionalPurpose, given: boolean) =>
    setChoices((prev) => ({ ...prev, [studentId]: { ...prev[studentId], [purpose]: given } }));

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    if (!essential) {
      setEssentialError(t("privacy.gate.v.essential"));
      event.currentTarget.querySelector<HTMLInputElement>("input[name=essential]")?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    setEssentialError(null);
    try {
      const next = await privacyApi.accept({
        noticeVersion: notice.version,
        acceptEssential: true,
        choices: pending.map((c) => ({
          studentId: c.studentId,
          photos: choices[c.studentId]?.PHOTOS ?? false,
          whatsapp: choices[c.studentId]?.WHATSAPP ?? false,
        })),
      });
      onAccepted(next);
    } catch (caught) {
      const apiError = toApiError(caught);
      if (apiError.status === 409 && onStale) {
        setError(t("privacy.gate.stale"));
        onStale();
      } else {
        setError(errorMessage(apiError, t));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card flex max-w-3xl flex-col gap-4" data-testid="privacy-gate" aria-labelledby="gate-title">
      <header className="flex items-start gap-3">
        <span className="badge-ic" aria-hidden="true">
          <ShieldCheck size={20} />
        </span>
        <div className="min-w-0">
          <Heading id="gate-title">{renewing ? t("privacy.gate.titleNew") : t("privacy.gate.title")}</Heading>
          <p className="text-[13.5px] text-ink-2">
            {t("privacy.gate.sub", { version: notice.version, date: formatDate(notice.publishedAt, locale) })}
          </p>
        </div>
      </header>
      {renewing && notice.changeSummary ? (
        <p className="alert alert-info">{t("privacy.notice.changed", { summary: notice.changeSummary })}</p>
      ) : null}
      <div className="max-h-[50vh] overflow-y-auto rounded-lg border border-line p-4" tabIndex={0}>
        <NoticeText text={lang === "hi" ? notice.bodyHi : notice.bodyEn} lang={lang} />
      </div>
      <details>
        <summary className="cursor-pointer text-[13.5px] font-semibold">{t("privacy.my.officer")}</summary>
        <div className="mt-2">
          <OfficerContact officer={notice.grievanceOfficer} />
        </div>
      </details>

      <form method="post" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <FormAlert message={error} />
        <div className="flex flex-col gap-1">
          <label className="check">
            <input
              type="checkbox"
              name="essential"
              checked={essential}
              onChange={(e) => {
                setEssential(e.target.checked);
                if (e.target.checked) setEssentialError(null);
              }}
              aria-invalid={essentialError ? true : undefined}
              aria-describedby={essentialError ? "essential-error" : undefined}
            />
            <span>
              <b className="block font-semibold">{t("privacy.gate.essential")}</b>
              <span className="block text-[13px] text-ink-3">{t("privacy.purposeHint.ESSENTIAL")}</span>
            </span>
          </label>
          {essentialError ? (
            <p id="essential-error" className="field-error" role="alert">
              {essentialError}
            </p>
          ) : null}
        </div>

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 font-semibold">{t("privacy.gate.optional")}</legend>
          <p className="text-[13px] text-ink-3">{t("privacy.gate.optionalHint")}</p>
          {pending.map((child) => (
            <div key={child.studentId} className="rounded-lg border border-line p-3" data-testid={`gate-child-${child.studentId}`}>
              <p className="font-semibold">{child.fullName}</p>
              <p className="mb-2 text-[12.5px] text-ink-3">{classLabel(child.className, child.sectionName)}</p>
              <div className="flex flex-col gap-2">
                {OPTIONAL_PURPOSES.map((purpose) => (
                  <label key={purpose} className="check">
                    <input
                      type="checkbox"
                      name={`${purpose.toLowerCase()}-${child.studentId}`}
                      checked={choices[child.studentId]?.[purpose] ?? false}
                      onChange={(e) => pick(child.studentId, purpose, e.target.checked)}
                    />
                    <span>
                      <b className="block font-semibold">{purposeLabel(t, purpose)}</b>
                      <span className="block text-[13px] text-ink-3">
                        {translateOr(t, `privacy.purposeHint.${purpose}`, "")}
                      </span>
                    </span>
                  </label>
                ))}
              </div>
            </div>
          ))}
        </fieldset>

        <div className="flex flex-wrap items-center justify-between gap-3">
          {embedded ? (
            <span />
          ) : (
            <Link href="/app/my-privacy" className="link text-[13.5px]">
              {t("privacy.gate.instead")}
            </Link>
          )}
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? t("common.working") : t("privacy.gate.submit")}
          </button>
        </div>
      </form>
    </section>
  );
}
