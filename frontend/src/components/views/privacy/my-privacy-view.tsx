"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { classLabel, formatDate, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { privacyApi } from "@/lib/privacy-api";
import {
  OPTIONAL_PURPOSES,
  REQUEST_DETAILS_MAX,
  REQUEST_TYPES,
  type ChildConsent,
  type OptionalPurpose,
  type ParentPrivacy,
  type PrivacyNotice,
  type RequestType,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { ConsentHistory } from "./consents-view";
import { ConsentForm, usePrivacyGate } from "./privacy-gate";
import {
  ConsentPill,
  DuePill,
  NoticeText,
  OfficerContact,
  purposeLabel,
  requestTypeLabel,
  RequestStatusPill,
} from "./privacy-ui";

type RequestValues = { type: RequestType | ""; about: string; details: string };

/** "SELF" or a child's id: what a new request is about. */
const SELF = "SELF";

/** Client-side checks for a new request, mirroring the API. Pure; exported for tests. */
export function requestProblems(values: RequestValues): Problems {
  const problems: Problems = {};
  if (!values.type) problems.type = "validation.choose";
  if (!values.about) problems.about = "validation.choose";
  else if (values.type === "ERASURE" && values.about === SELF) problems.about = "privacy.new.v.erasureChild";
  const details = values.details.trim();
  if (!details && (values.type === "CORRECTION" || values.type === "GRIEVANCE")) problems.details = "validation.required";
  else if (details.length > REQUEST_DETAILS_MAX) problems.details = "validation.tooLong";
  return problems;
}

/** Whether a purpose is currently agreed for a child. */
export function isGiven(child: ChildConsent, purpose: OptionalPurpose): boolean {
  return child.purposes.find((p) => p.purpose === purpose)?.status === "GIVEN";
}

/** /app/my-privacy: a parent's notice, consent for each child, and their data requests. */
export function MyPrivacyView() {
  const { t } = useI18n();
  const privacy = useApiData("privacy:mine", privacyApi.mine);
  const requests = useApiData("privacy:my-requests", privacyApi.myRequests);
  const [current, setCurrent] = useState<ParentPrivacy | null>(null);
  const [creating, setCreating] = useState(false);
  const gate = usePrivacyGate();
  const data = current ?? privacy.data ?? null;
  const accepted = (next: ParentPrivacy) => {
    setCurrent(next);
    gate?.accepted(next);
  };

  return (
    <>
      <PageHead
        title={t("privacy.my.title")}
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} disabled={!data}>
            <Plus size={18} aria-hidden="true" />
            {t("privacy.my.newRequest")}
          </button>
        }
      />
      {privacy.error && !data ? (
        <section className="card">
          <ErrorState error={privacy.error} onRetry={privacy.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      ) : (
        <>
          {data.needsConsent && data.notice ? (
            <ConsentForm
              key={data.notice.version}
              privacy={{ ...data, notice: data.notice }}
              onAccepted={accepted}
              onStale={() => {
                setCurrent(null);
                privacy.reload();
              }}
              embedded
            />
          ) : (
            <NoticeCard notice={data.notice} />
          )}
          <section className="flex flex-col gap-3" aria-labelledby="children-heading">
            <h2 id="children-heading">{t("privacy.my.children")}</h2>
            {data.children.length === 0 ? (
              <p className="card empty">{t("privacy.my.noChildren")}</p>
            ) : (
              data.children.map((child) => (
                <ChildCard key={child.studentId} child={child} noticePublished={Boolean(data.notice)} onChange={setCurrent} />
              ))
            )}
          </section>
        </>
      )}

      <section className="card" aria-labelledby="requests-heading">
        <h2 id="requests-heading">{t("privacy.my.requests")}</h2>
        {requests.error && !requests.data ? (
          <ErrorState error={requests.error} onRetry={requests.reload} />
        ) : !requests.data ? (
          <LoadingRows rows={2} />
        ) : requests.data.length === 0 ? (
          <p className="empty" data-testid="my-requests-empty">
            {t("privacy.my.noRequests")}
          </p>
        ) : (
          <ul className="rowcards" data-testid="my-requests">
            {requests.data.map((r) => (
              <li key={r.id}>
                <Link href={`/app/my-privacy/requests/${r.id}`} className="rowcard">
                  <span className="min-w-0 flex-1">
                    <b className="block truncate font-semibold">{requestTypeLabel(t, r.type)}</b>
                    <span className="block truncate text-[12.5px] text-ink-3">
                      {r.subject === "SELF" ? t("privacy.new.aboutSelf") : r.studentName}
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
        )}
      </section>

      {creating && data ? (
        <NewRequestDialog family={data.children} onClose={() => setCreating(false)} onCreated={requests.reload} />
      ) : null}
    </>
  );
}

function NoticeCard({ notice }: { notice: PrivacyNotice | null }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [open, setOpen] = useState(false);
  if (!notice) {
    return (
      <section className="card">
        <p className="text-ink-2">{t("privacy.my.noNotice")}</p>
      </section>
    );
  }
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="notice-heading">
      <div>
        <h2 id="notice-heading">{t("privacy.my.notice")}</h2>
        <p className="text-[13px] text-ink-3">
          {t("privacy.my.noticeVersion", { version: notice.version, date: formatDate(notice.publishedAt, locale) })}
        </p>
      </div>
      <button type="button" className="btn btn-sm self-start" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {open ? t("privacy.my.hideNotice") : t("privacy.my.readNotice")}
      </button>
      {open ? (
        <div className="max-h-[480px] overflow-y-auto rounded-lg border border-line p-4" tabIndex={0}>
          <NoticeText text={lang === "hi" ? notice.bodyHi : notice.bodyEn} lang={lang} />
        </div>
      ) : null}
      <h3 className="text-[14px] font-semibold">{t("privacy.my.officer")}</h3>
      <OfficerContact officer={notice.grievanceOfficer} />
    </section>
  );
}

function ChildCard({
  child,
  noticePublished,
  onChange,
}: {
  child: ChildConsent;
  noticePublished: boolean;
  onChange: (next: ParentPrivacy) => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [busy, setBusy] = useState<OptionalPurpose | null>(null);
  const [error, setError] = useState<string | null>(null);
  const essential = child.purposes.find((p) => p.purpose === "ESSENTIAL");

  const toggle = async (purpose: OptionalPurpose, given: boolean) => {
    setBusy(purpose);
    setError(null);
    try {
      onChange(await privacyApi.change(child.studentId, purpose, given));
      toast(given ? t("privacy.my.given") : t("privacy.my.withdrawn"));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="card flex flex-col gap-3" data-testid={`child-consent-${child.studentId}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-semibold">{child.fullName}</h3>
          <p className="text-[13px] text-ink-3">{classLabel(child.className, child.sectionName)}</p>
        </div>
        {essential ? <ConsentPill state={essential} /> : null}
      </div>
      <p className="text-[13.5px] text-ink-2">
        <b className="font-semibold">{purposeLabel(t, "ESSENTIAL")}.</b> {t("privacy.purposeHint.ESSENTIAL")}
      </p>
      <FormAlert message={error} />
      {OPTIONAL_PURPOSES.map((purpose) => (
        <label key={purpose} className="check">
          <input
            type="checkbox"
            name={`${purpose.toLowerCase()}-${child.studentId}`}
            checked={isGiven(child, purpose)}
            disabled={!noticePublished || busy !== null || child.needsConsent}
            onChange={(e) => toggle(purpose, e.target.checked)}
          />
          <span>
            <b className="block font-semibold">{purposeLabel(t, purpose)}</b>
            <span className="block text-[13px] text-ink-3">
              {translateOr(t, `privacy.purposeHint.${purpose}`, "")}
            </span>
          </span>
        </label>
      ))}
      {child.history.length ? (
        <details>
          <summary className="cursor-pointer text-[13.5px]">{t("privacy.my.history")}</summary>
          <ConsentHistory entries={child.history} />
        </details>
      ) : null}
    </section>
  );
}

function NewRequestDialog({
  family,
  onClose,
  onCreated,
}: {
  family: ChildConsent[];
  onClose: () => void;
  onCreated: () => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const router = useRouter();
  const form = useForm<RequestValues>({ type: "", about: family.length === 1 ? family[0].studentId : "", details: "" });
  const v = form.values;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(requestProblems(v), t, event.currentTarget)) return;
    let createdId = "";
    let dueOn = "";
    const ok = await form.submit(t, async () => {
      const created = await privacyApi.submit({
        type: v.type as RequestType,
        subject: v.about === SELF ? "SELF" : "CHILD",
        studentId: v.about === SELF ? null : v.about,
        details: v.details.trim() || null,
      });
      createdId = created.id;
      dueOn = created.dueOn;
    });
    if (ok) {
      toast(t("privacy.new.done", { date: formatPlainDate(dueOn, locale) }));
      onCreated();
      onClose();
      router.push(`/app/my-privacy/requests/${createdId}`);
    }
  };

  const typeOptions = [
    { value: "", label: t("common.choose") },
    ...REQUEST_TYPES.map((type) => ({ value: type, label: translateOr(t, `privacy.typeLong.${type}`, type) })),
  ];
  const aboutOptions = [
    { value: "", label: t("common.choose") },
    ...family.map((c) => ({ value: c.studentId, label: c.fullName })),
    ...(v.type === "ERASURE" ? [] : [{ value: SELF, label: t("privacy.new.aboutSelf") }]),
  ];

  return (
    <Dialog open onClose={onClose} title={t("privacy.new.title")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <SelectField
          label={t("privacy.new.type")}
          name="type"
          value={v.type}
          onChange={(e) => form.set("type", e.target.value as RequestType | "")}
          options={typeOptions}
          hint={v.type ? translateOr(t, `privacy.typeHint.${v.type}`, "") : undefined}
          error={form.errors.type}
        />
        <SelectField
          label={t("privacy.new.about")}
          name="about"
          value={v.about}
          onChange={(e) => form.set("about", e.target.value)}
          options={aboutOptions}
          error={form.errors.about ?? form.errors.studentId}
        />
        <TextAreaField
          label={t("privacy.new.details")}
          hint={t("privacy.new.detailsHint")}
          name="details"
          rows={4}
          maxLength={REQUEST_DETAILS_MAX}
          value={v.details}
          onChange={(e) => form.set("details", e.target.value)}
          error={form.errors.details}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("privacy.new.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
