"use client";

import { ArrowLeft, Download } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { TextAreaField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDate, formatDateTime, formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { downloadExport, privacyApi, privacyDownloads } from "@/lib/privacy-api";
import { REPLY_MAX, type DataRequestDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { DuePill, RequestStatusPill, RequestTimeline, requestTypeLabel } from "./privacy-ui";

/** /app/my-privacy/requests/[id]: one of the parent's own requests, its answer and the data file. */
export function MyRequestView({ id }: { id: string }) {
  const { t } = useI18n();
  const request = useApiData(`privacy:my-request:${id}`, () => privacyApi.myRequest(id));
  const [current, setCurrent] = useState<DataRequestDetail | null>(null);
  const r = current ?? request.data ?? null;

  const back = (
    <Link href="/app/my-privacy" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("privacy.myRequest.back")}
    </Link>
  );

  if (request.error && !r) {
    return (
      <>
        {back}
        <section className="card">
          {request.error.status === 404 ? (
            <p className="empty">{t("privacy.request.notFound")}</p>
          ) : (
            <ErrorState error={request.error} onRetry={request.reload} />
          )}
        </section>
      </>
    );
  }
  if (!r) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={5} />
        </section>
      </>
    );
  }
  return <MyRequest request={r} onChange={setCurrent} back={back} />;
}

function MyRequest({
  request: r,
  onChange,
  back,
}: {
  request: DataRequestDetail;
  onChange: (next: DataRequestDetail) => void;
  back: React.ReactNode;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const open = r.status !== "CLOSED";
  const lastExport = r.exports[0];

  return (
    <>
      {back}
      <PageHead
        eyebrow={r.subject === "SELF" ? t("privacy.new.aboutSelf") : r.studentName}
        title={requestTypeLabel(t, r.type)}
        actions={
          <span className="flex items-center gap-2">
            <RequestStatusPill request={r} />
            <DuePill request={r} />
          </span>
        }
      />
      <section className="card flex flex-col gap-3">
        <dl className="kv">
          <dt>{t("privacy.request.raisedOn")}</dt>
          <dd>{formatDate(r.createdAt, locale)}</dd>
          <dt>{t("privacy.request.dueOn")}</dt>
          <dd>{formatPlainDate(r.dueOn, locale)}</dd>
        </dl>
        {r.details ? <p className="whitespace-pre-line break-words text-[14.5px]">{r.details}</p> : null}
        {r.closingNote ? (
          <div className="alert alert-info">
            <span>
              <b className="block font-semibold">{t("privacy.request.closingNote")}</b>
              <span className="whitespace-pre-line">{r.closingNote}</span>
            </span>
          </div>
        ) : null}
      </section>

      {r.export ? (
        <ExportDownload request={r} />
      ) : lastExport && r.type === "ACCESS" ? (
        <p className="card text-ink-2">{t("privacy.myRequest.expired")}</p>
      ) : null}

      {open ? <ParentReply requestId={r.id} onChange={onChange} /> : null}

      <section className="card" aria-labelledby="my-timeline">
        <h2 id="my-timeline" className="mb-3">
          {t("privacy.timeline.title")}
        </h2>
        <RequestTimeline request={r} />
      </section>
    </>
  );
}

function ExportDownload({ request }: { request: DataRequestDetail }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const file = request.export;
  if (!file) return null;

  const download = async () => {
    setBusy(true);
    setError(null);
    try {
      await downloadExport(privacyDownloads.mine(request.id), file.fileName);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card flex flex-col gap-3" data-testid="my-export">
      <p>{t("privacy.myRequest.ready", { date: formatDateTime(file.expiresAt, locale) })}</p>
      <FormAlert message={error} />
      <button type="button" className="btn btn-primary self-start" onClick={download} disabled={busy}>
        <Download size={18} aria-hidden="true" />
        {busy ? t("common.working") : t("privacy.myRequest.download")}
      </button>
    </section>
  );
}

function ParentReply({ requestId, onChange }: { requestId: string; onChange: (next: DataRequestDetail) => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm({ body: "" });

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const body = form.values.body.trim();
    const problems: Problems = {};
    if (!body) problems.body = "validation.required";
    else if (body.length > REPLY_MAX) problems.body = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      onChange(await privacyApi.myReply(requestId, body));
    });
    if (ok) {
      form.reset({ body: "" });
      toast(t("privacy.reply.sent"));
    }
  };

  return (
    <section className="card">
      <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("privacy.reply.parentLabel")}
          name="body"
          rows={3}
          maxLength={REPLY_MAX}
          value={form.values.body}
          onChange={(e) => form.set("body", e.target.value)}
          error={form.errors.body}
        />
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("privacy.reply.submit")}
          </button>
        </div>
      </form>
    </section>
  );
}
