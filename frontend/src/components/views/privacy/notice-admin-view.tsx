"use client";

import { ExternalLink } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { useAuth } from "@/lib/auth";
import { formatDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { privacyApi } from "@/lib/privacy-api";
import {
  CHANGE_SUMMARY_MAX,
  NOTICE_MAX,
  type GrievanceOfficer,
  type NoticeAdmin,
  type NoticeVersion,
  type PrivacyNotice,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isValidEmail, isValidSchoolPhone } from "@/lib/validation";
import { NoticeBody, PrivacyNav } from "./privacy-ui";

type OfficerValues = { name: string; email: string; phone: string };
type PublishValues = { bodyEn: string; bodyHi: string; changeSummary: string };

/** Client-side checks for the grievance officer, mirroring the API. Pure; exported for tests. */
export function officerProblems(values: OfficerValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "validation.required";
  else if (values.name.trim().length > 200) problems.name = "validation.tooLong";
  if (!values.email.trim()) problems.email = "validation.required";
  else if (!isValidEmail(values.email.trim()) || values.email.trim().length > 254) problems.email = "validation.email";
  if (!values.phone.trim()) problems.phone = "validation.required";
  else if (!isValidSchoolPhone(values.phone.trim())) problems.phone = "privacy.officer.v.phone";
  return problems;
}

/** Client-side checks for a new notice version, mirroring the API. Pure; exported for tests. */
export function publishProblems(values: PublishValues, firstVersion: boolean): Problems {
  const problems: Problems = {};
  if (!values.bodyEn.trim()) problems.bodyEn = "validation.required";
  else if (values.bodyEn.trim().length > NOTICE_MAX) problems.bodyEn = "validation.tooLong";
  if (!values.bodyHi.trim()) problems.bodyHi = "validation.required";
  else if (values.bodyHi.trim().length > NOTICE_MAX) problems.bodyHi = "validation.tooLong";
  if (!firstVersion && !values.changeSummary.trim()) problems.changeSummary = "privacy.publish.v.summary";
  else if (values.changeSummary.trim().length > CHANGE_SUMMARY_MAX) problems.changeSummary = "validation.tooLong";
  return problems;
}

/** /app/privacy/notice: the grievance officer, the current notice, a new version and the history. */
export function NoticeAdminView() {
  const { t } = useI18n();
  const notice = useApiData("privacy:notice", privacyApi.noticeAdmin);
  const data = notice.data;

  return (
    <>
      <PageHead eyebrow={t("privacy.eyebrow")} title={t("privacy.notice.title")} />
      <PrivacyNav />
      {notice.error && !data ? (
        <section className="card">
          <ErrorState error={notice.error} onRetry={notice.reload} />
        </section>
      ) : !data ? (
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,380px)]">
          <div className="flex min-w-0 flex-col gap-4">
            <CurrentNotice notice={data.current} />
            <PublishForm key={data.current?.version ?? 0} admin={data} onPublished={notice.reload} />
          </div>
          <div className="flex min-w-0 flex-col gap-4">
            <OfficerForm officer={data.officer} onSaved={notice.reload} />
            <Versions versions={data.versions} currentVersion={data.current?.version ?? null} />
          </div>
        </div>
      )}
    </>
  );
}

function CurrentNotice({ notice }: { notice: PrivacyNotice | null }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  const [open, setOpen] = useState(false);
  const schoolCode = me?.tenant?.code;

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="current-notice" data-testid="current-notice">
      <div className="card-head">
        <h2 id="current-notice">{t("privacy.notice.current")}</h2>
        {notice && schoolCode ? (
          <Link
            href={`/privacy/${schoolCode}`}
            className="link inline-flex items-center gap-1 text-[13.5px]"
            target="_blank"
            rel="noopener"
          >
            {t("privacy.notice.publicLink")}
            <ExternalLink size={14} aria-hidden="true" />
          </Link>
        ) : null}
      </div>
      {notice ? (
        <>
          <p>
            <b className="font-semibold">{t("privacy.notice.version", { version: notice.version })}</b>
            <span className="block text-[13px] text-ink-3">
              {t("privacy.notice.publishedOn", {
                date: formatDate(notice.publishedAt, locale),
                name: notice.publishedByName ?? t("common.system"),
              })}
            </span>
          </p>
          {notice.changeSummary ? (
            <p className="text-[14px]">{t("privacy.notice.changed", { summary: notice.changeSummary })}</p>
          ) : null}
          <button type="button" className="btn btn-sm self-start" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
            {open ? t("privacy.notice.hide") : t("privacy.notice.read")}
          </button>
          {open ? <NoticeBody notice={notice} /> : null}
        </>
      ) : (
        <p className="text-ink-2">{t("privacy.notice.none")}</p>
      )}
    </section>
  );
}

function PublishForm({ admin, onPublished }: { admin: NoticeAdmin; onPublished: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const first = admin.current === null;
  const next = (admin.current?.version ?? 0) + 1;
  const form = useForm<PublishValues>({ bodyEn: admin.draftEn, bodyHi: admin.draftHi, changeSummary: "" });
  const v = form.values;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!admin.officer) {
      form.setFormError(t("privacy.publish.needOfficer"));
      return;
    }
    if (!form.check(publishProblems(v, first), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await privacyApi.publishNotice({
        bodyEn: v.bodyEn.trim(),
        bodyHi: v.bodyHi.trim(),
        changeSummary: v.changeSummary.trim() || null,
      });
    });
    if (ok) {
      toast(t("privacy.publish.done", { version: next }));
      onPublished();
    }
  };

  return (
    <section className="card" aria-labelledby="publish-heading">
      <h2 id="publish-heading">{t("privacy.publish.title")}</h2>
      <p className="mt-1 text-[13.5px] text-ink-2">{t("privacy.publish.sub")}</p>
      <form method="post" className="mt-3 flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        {admin.draftIsTemplate ? (
          <p className="alert alert-info" data-testid="template-hint">
            {t("privacy.publish.template")}
          </p>
        ) : null}
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("privacy.publish.en")}
          hint={t("privacy.publish.format")}
          name="bodyEn"
          rows={12}
          lang="en"
          maxLength={NOTICE_MAX}
          value={v.bodyEn}
          onChange={(e) => form.set("bodyEn", e.target.value)}
          error={form.errors.bodyEn}
        />
        <TextAreaField
          label={t("privacy.publish.hi")}
          name="bodyHi"
          rows={12}
          lang="hi"
          maxLength={NOTICE_MAX}
          value={v.bodyHi}
          onChange={(e) => form.set("bodyHi", e.target.value)}
          error={form.errors.bodyHi}
        />
        <TextField
          label={t("privacy.publish.summary")}
          hint={t(first ? "privacy.publish.summaryFirst" : "privacy.publish.summaryHint")}
          name="changeSummary"
          maxLength={CHANGE_SUMMARY_MAX}
          value={v.changeSummary}
          onChange={(e) => form.set("changeSummary", e.target.value)}
          error={form.errors.changeSummary}
        />
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("privacy.publish.submit", { version: next })}
          </button>
        </div>
      </form>
    </section>
  );
}

function OfficerForm({ officer, onSaved }: { officer: GrievanceOfficer | null; onSaved: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<OfficerValues>({
    name: officer?.name ?? "",
    email: officer?.email ?? "",
    phone: officer?.phone ?? "",
  });
  const v = form.values;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(officerProblems(v), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await privacyApi.updateOfficer({ name: v.name.trim(), email: v.email.trim(), phone: v.phone.trim() });
    });
    if (ok) {
      toast(t("privacy.officer.saved"));
      onSaved();
    }
  };

  return (
    <section className="card" aria-labelledby="officer-heading">
      <h2 id="officer-heading">{t("privacy.officer.title")}</h2>
      <p className="mt-1 text-[13.5px] text-ink-2">{t("privacy.officer.sub")}</p>
      <form method="post" className="mt-3 flex flex-col gap-3" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("privacy.officer.name")}
          name="name"
          autoComplete="name"
          maxLength={200}
          value={v.name}
          onChange={(e) => form.set("name", e.target.value)}
          error={form.errors.name}
        />
        <TextField
          label={t("privacy.officer.email")}
          name="email"
          type="email"
          autoComplete="email"
          maxLength={254}
          value={v.email}
          onChange={(e) => form.set("email", e.target.value)}
          error={form.errors.email}
        />
        <TextField
          label={t("privacy.officer.phone")}
          name="phone"
          type="tel"
          autoComplete="tel"
          maxLength={20}
          value={v.phone}
          onChange={(e) => form.set("phone", e.target.value)}
          error={form.errors.phone}
        />
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("privacy.officer.save")}
          </button>
        </div>
      </form>
    </section>
  );
}

function Versions({ versions, currentVersion }: { versions: NoticeVersion[]; currentVersion: number | null }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [reading, setReading] = useState<number | null>(null);
  const version = useApiData(reading === null ? null : `privacy:notice:${reading}`, () =>
    privacyApi.noticeVersion(reading as number),
  );

  return (
    <section className="card" aria-labelledby="versions-heading">
      <h2 id="versions-heading">{t("privacy.notice.versions")}</h2>
      {versions.length === 0 ? (
        <p className="empty">{t("privacy.notice.noVersions")}</p>
      ) : (
        <ul className="list" data-testid="notice-versions">
          {versions.map((v) => (
            <li key={v.version} className="li">
              <span className="min-w-0 flex-1">
                <b className="block font-semibold">
                  {t("privacy.notice.version", { version: v.version })}
                  {v.version === currentVersion ? ` · ${t("privacy.notice.currentShort")}` : ""}
                </b>
                <span className="block text-[12.5px] text-ink-3">
                  {t("privacy.notice.publishedOn", {
                    date: formatDate(v.publishedAt, locale),
                    name: v.publishedByName ?? t("common.system"),
                  })}
                </span>
                {v.changeSummary ? <span className="block text-[13px] text-ink-2">{v.changeSummary}</span> : null}
              </span>
              <button type="button" className="btn btn-sm" onClick={() => setReading(v.version)}>
                {t("privacy.notice.read")}
              </button>
            </li>
          ))}
        </ul>
      )}
      <Dialog
        open={reading !== null}
        onClose={() => setReading(null)}
        title={reading === null ? "" : t("privacy.notice.version", { version: reading })}
        closeLabel={t("common.close")}
      >
        {version.error && !version.data ? (
          <ErrorState error={version.error} onRetry={version.reload} />
        ) : !version.data || version.loading ? (
          <LoadingRows rows={4} />
        ) : (
          <NoticeBody notice={version.data} />
        )}
      </Dialog>
    </section>
  );
}
