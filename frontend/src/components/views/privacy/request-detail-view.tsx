"use client";

import { ArrowLeft, Download, FileArchive, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatDate, formatDateTime, formatPlainDate } from "@/lib/format";
import { localeFor, plural, translateOr, useI18n, type Translate } from "@/lib/i18n";
import { downloadExport, privacyApi, privacyDownloads } from "@/lib/privacy-api";
import {
  EXPORT_KEEP_DAYS,
  REPLY_MAX,
  type DataExport,
  type DataRequestDetail,
  type RequestResolution,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  aboutLabel,
  DuePill,
  PrivacyNav,
  RequestStatusPill,
  RequestTimeline,
  requestTypeLabel,
  sizeInKb,
} from "./privacy-ui";

/** The admission number typed to confirm an erasure matches (spaces and case ignored). Pure. */
export function erasureConfirmed(typed: string, admissionNo: string | null): boolean {
  if (!admissionNo) return false;
  return typed.trim().toUpperCase() === admissionNo.trim().toUpperCase();
}

/** Client-side checks for closing a request, mirroring the API. Pure; exported for tests. */
export function closeProblems(values: { resolution: RequestResolution | ""; note: string }): Problems {
  const problems: Problems = {};
  if (!values.resolution) problems.resolution = "validation.choose";
  if (values.resolution === "DECLINED" && !values.note.trim()) problems.note = "privacy.close.v.note";
  if (values.note.trim().length > REPLY_MAX) problems.note = "validation.tooLong";
  return problems;
}

/** /app/privacy/requests/[id]: one request with its timeline, for staff with privacy.manage. */
export function RequestDetailView({ id }: { id: string }) {
  const { t } = useI18n();
  const request = useApiData(`privacy:request:${id}`, () => privacyApi.request(id));
  const [current, setCurrent] = useState<DataRequestDetail | null>(null);
  const r = current ?? request.data ?? null;

  const back = (
    <Link href="/app/privacy" className="link inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("privacy.request.back")}
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
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }
  return <RequestDetail request={r} onChange={setCurrent} back={back} />;
}

function RequestDetail({
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

  return (
    <>
      {back}
      <PageHead
        eyebrow={r.requesterName}
        title={requestTypeLabel(t, r.type)}
        actions={
          <span className="flex items-center gap-2">
            <RequestStatusPill request={r} />
            <DuePill request={r} />
          </span>
        }
      />
      <PrivacyNav />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,380px)]">
        <div className="flex min-w-0 flex-col gap-4">
          <section className="card" aria-labelledby="request-heading">
            <h2 id="request-heading" className="mb-3">
              {t("privacy.request.heading")}
            </h2>
            <dl className="kv" data-testid="request-facts">
              <dt>{t("privacy.request.raisedBy")}</dt>
              <dd>{r.requesterName}</dd>
              <dt>{t("privacy.request.about")}</dt>
              <dd>
                {r.subject === "CHILD" && r.studentId ? (
                  <Link href={`/app/students/${r.studentId}`} className="link">
                    {aboutLabel(t, r)}
                    {r.admissionNo ? ` · ${r.admissionNo}` : ""}
                  </Link>
                ) : (
                  aboutLabel(t, r)
                )}
                {r.studentStatus && r.studentStatus !== "ACTIVE" ? (
                  <span className="ml-2 text-[12.5px] text-ink-3">
                    {translateOr(t, `studentStatus.${r.studentStatus}`, r.studentStatus)}
                  </span>
                ) : null}
              </dd>
              <dt>{t("privacy.request.raisedOn")}</dt>
              <dd>{formatDate(r.createdAt, locale)}</dd>
              <dt>{t("privacy.request.dueOn")}</dt>
              <dd>{formatPlainDate(r.dueOn, locale)}</dd>
              <dt>{t("privacy.request.assignedTo")}</dt>
              <dd>{r.assignedTo?.name ?? t("privacy.unassigned")}</dd>
              {r.erasedAt ? (
                <>
                  <dt>{t("privacy.request.erased")}</dt>
                  <dd>{formatDateTime(r.erasedAt, locale)}</dd>
                </>
              ) : null}
              {r.closedAt ? (
                <>
                  <dt>{t("privacy.request.closed")}</dt>
                  <dd>
                    {t("privacy.request.closedBy", {
                      name: r.closedByName ?? t("common.system"),
                      date: formatDate(r.closedAt, locale),
                    })}
                  </dd>
                </>
              ) : null}
            </dl>
            <h3 className="mt-4 text-[14px] font-semibold">{t("privacy.request.details")}</h3>
            <p className="mt-1 whitespace-pre-line break-words text-[14.5px]">
              {r.details || <span className="text-ink-3">{t("privacy.request.noDetails")}</span>}
            </p>
            {r.closingNote ? (
              <>
                <h3 className="mt-4 text-[14px] font-semibold">{t("privacy.request.closingNote")}</h3>
                <p className="mt-1 whitespace-pre-line break-words text-[14.5px]">{r.closingNote}</p>
              </>
            ) : null}
          </section>

          {open ? <ReplyForm requestId={r.id} onChange={onChange} /> : null}

          <section className="card" aria-labelledby="timeline-heading">
            <h2 id="timeline-heading" className="mb-3">
              {t("privacy.timeline.title")}
            </h2>
            <RequestTimeline request={r} />
          </section>
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          {open ? <AssignCard request={r} onChange={onChange} /> : null}
          {r.type === "ACCESS" ? <ExportCard request={r} onChange={onChange} /> : null}
          {r.type === "ERASURE" && r.subject === "CHILD" ? <EraseCard request={r} onChange={onChange} /> : null}
          {open ? <CloseCard request={r} onChange={onChange} /> : null}
        </div>
      </div>
    </>
  );
}

function ReplyForm({ requestId, onChange }: { requestId: string; onChange: (next: DataRequestDetail) => void }) {
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
      onChange(await privacyApi.reply(requestId, body));
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
          label={t("privacy.reply.label")}
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

function AssignCard({
  request,
  onChange,
}: {
  request: DataRequestDetail;
  onChange: (next: DataRequestDetail) => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const staff = useApiData("privacy:staff", privacyApi.staff);
  const form = useForm({ assigneeId: request.assignedTo?.id ?? "" });

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(form.values.assigneeId ? {} : { assigneeId: "validation.choose" }, t, event.currentTarget)) return;
    let name = "";
    const ok = await form.submit(t, async () => {
      const next = await privacyApi.assign(request.id, form.values.assigneeId);
      name = next.assignedTo?.name ?? "";
      onChange(next);
    });
    if (ok) toast(t("privacy.assign.done", { name }));
  };

  const options = [
    { value: "", label: t("common.choose") },
    ...(staff.data ?? []).filter((s) => s.id).map((s) => ({ value: s.id as string, label: s.name })),
  ];

  return (
    <section className="card">
      <form method="post" className="flex flex-col gap-3" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError ?? (staff.error ? errorMessage(staff.error, t) : null)} />
        <SelectField
          label={t("privacy.assign.label")}
          name="assigneeId"
          value={form.values.assigneeId}
          onChange={(e) => form.set("assigneeId", e.target.value)}
          options={options}
          error={form.errors.assigneeId}
        />
        <div className="flex justify-end">
          <button type="submit" className="btn" disabled={form.submitting || !staff.data}>
            {form.submitting ? t("common.working") : t("privacy.assign.submit")}
          </button>
        </div>
      </form>
    </section>
  );
}

function exportSummary(t: Translate, e: DataExport, locale: string): string {
  return [
    translateOr(t, `privacy.export.${e.status}`, e.status),
    formatDateTime(e.createdAt, locale),
    t("privacy.export.size", { size: sizeInKb(e.sizeBytes) }),
    e.downloadCount > 0 ? plural(t, "privacy.export.downloads", e.downloadCount) : null,
  ]
    .filter(Boolean)
    .join(" · ");
}

function ExportCard({ request, onChange }: { request: DataRequestDetail; onChange: (next: DataRequestDetail) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ready = request.export;
  const open = request.status !== "CLOSED";

  const make = async () => {
    setBusy(true);
    setError(null);
    try {
      onChange(await privacyApi.createExport(request.id));
      toast(t("privacy.export.made", { days: EXPORT_KEEP_DAYS }));
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  const download = async () => {
    if (!ready) return;
    setError(null);
    try {
      await downloadExport(privacyDownloads.staff(request.id), ready.fileName);
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    }
  };

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="export-heading" data-testid="export-card">
      <h2 id="export-heading">{t("privacy.export.title")}</h2>
      <p className="text-[13.5px] text-ink-2">{t("privacy.export.intro", { days: EXPORT_KEEP_DAYS })}</p>
      <FormAlert message={error} />
      {ready ? (
        <div className="flex flex-col gap-1 rounded-lg border border-line p-3">
          <span className="mono flex items-center gap-2 break-all text-[13px]">
            <FileArchive size={16} aria-hidden="true" className="flex-none" />
            {ready.fileName}
          </span>
          <span className="text-[12.5px] text-ink-3">
            {t("privacy.export.expires", { date: formatDateTime(ready.expiresAt, locale) })}
          </span>
          <button type="button" className="btn btn-sm mt-1 self-start" onClick={download}>
            <Download size={16} aria-hidden="true" />
            {t("privacy.export.download")}
          </button>
        </div>
      ) : (
        <p className="text-[13.5px] text-ink-3">{t("privacy.export.none")}</p>
      )}
      {open ? (
        <button type="button" className="btn btn-primary self-start" onClick={make} disabled={busy}>
          {busy ? t("privacy.export.making") : ready ? t("privacy.export.remake") : t("privacy.export.make")}
        </button>
      ) : null}
      {request.exports.length > (ready ? 1 : 0) ? (
        <details>
          <summary className="cursor-pointer text-[13.5px]">{t("privacy.export.history")}</summary>
          <ul className="list mt-1">
            {request.exports
              .filter((e) => e.id !== ready?.id)
              .map((e) => (
                <li key={e.id} className="li text-[13px] text-ink-2">
                  {exportSummary(t, e, locale)}
                </li>
              ))}
          </ul>
        </details>
      ) : null}
    </section>
  );
}

function EraseCard({ request, onChange }: { request: DataRequestDetail; onChange: (next: DataRequestDetail) => void }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const [dialog, setDialog] = useState(false);
  const form = useForm({ confirmAdmissionNo: "" });

  const close = () => {
    setDialog(false);
    form.reset({ confirmAdmissionNo: "" });
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!erasureConfirmed(form.values.confirmAdmissionNo, request.admissionNo)) {
      problems.confirmAdmissionNo = "privacy.erase.mismatch";
    }
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      onChange(await privacyApi.erase(request.id, form.values.confirmAdmissionNo.trim()));
    });
    if (ok) {
      close();
      toast(t("privacy.erase.done"));
    }
  };

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="erase-heading" data-testid="erase-card">
      <h2 id="erase-heading">{t("privacy.erase.title")}</h2>
      {request.erasedAt ? (
        <p className="text-[13.5px]">{t("privacy.erase.erasedOn", { date: formatDate(request.erasedAt, locale) })}</p>
      ) : request.canErase ? (
        <>
          <p className="text-[13.5px] text-ink-2">{t("privacy.erase.ready", { name: request.studentName ?? "" })}</p>
          <button type="button" className="btn btn-danger self-start" onClick={() => setDialog(true)}>
            <Trash2 size={16} aria-hidden="true" />
            {t("privacy.erase.open")}
          </button>
        </>
      ) : request.status !== "CLOSED" ? (
        <p className="text-[13.5px] text-ink-2" data-testid="erase-not-left">
          {t("privacy.erase.notLeft")}
        </p>
      ) : null}

      <Dialog open={dialog} onClose={close} title={t("privacy.erase.title")} closeLabel={t("common.close")}>
        <form method="post" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <p className="text-[14px] text-ink-2">{t("privacy.erase.intro", { name: request.studentName ?? "" })}</p>
          <FormAlert message={form.formError} />
          <TextField
            label={t("privacy.erase.confirmLabel", { admissionNo: request.admissionNo ?? "" })}
            name="confirmAdmissionNo"
            autoComplete="off"
            maxLength={30}
            value={form.values.confirmAdmissionNo}
            onChange={(e) => form.set("confirmAdmissionNo", e.target.value)}
            error={form.errors.confirmAdmissionNo}
            data-autofocus
          />
          <div className="flex justify-end gap-2">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button
              type="submit"
              className="btn btn-danger"
              disabled={form.submitting || !erasureConfirmed(form.values.confirmAdmissionNo, request.admissionNo)}
            >
              {form.submitting ? t("common.working") : t("privacy.erase.submit")}
            </button>
          </div>
        </form>
      </Dialog>
    </section>
  );
}

function CloseCard({ request, onChange }: { request: DataRequestDetail; onChange: (next: DataRequestDetail) => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [dialog, setDialog] = useState(false);
  const empty = { resolution: "" as RequestResolution | "", note: "" };
  const form = useForm(empty);
  const needsErasure = request.type === "ERASURE" && request.subject === "CHILD" && !request.erasedAt;

  const close = () => {
    setDialog(false);
    form.reset(empty);
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(closeProblems(form.values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      onChange(
        await privacyApi.close(request.id, {
          resolution: form.values.resolution as RequestResolution,
          note: form.values.note.trim() || null,
        }),
      );
    });
    if (ok) {
      close();
      toast(t("privacy.close.done"));
    }
  };

  return (
    <section className="card flex flex-col gap-3">
      <h2>{t("privacy.close.title")}</h2>
      <p className="text-[13.5px] text-ink-2">{needsErasure ? t("privacy.close.eraseFirst") : t("privacy.close.sub")}</p>
      <button type="button" className="btn self-start" onClick={() => setDialog(true)}>
        {t("privacy.close.open")}
      </button>
      <Dialog open={dialog} onClose={close} title={t("privacy.close.title")} closeLabel={t("common.close")}>
        <form method="post" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <FormAlert message={form.formError} />
          <div className="field">
            <span className="field-label" id="close-resolution-label">
              {t("privacy.close.resolution")}
            </span>
            <div className="seg" role="radiogroup" aria-labelledby="close-resolution-label">
              {(["COMPLETED", "DECLINED"] as const).map((value) => (
                <label key={value}>
                  <input
                    type="radio"
                    name="resolution"
                    value={value}
                    checked={form.values.resolution === value}
                    disabled={value === "COMPLETED" && needsErasure}
                    onChange={() => form.set("resolution", value)}
                  />
                  {translateOr(t, `privacy.resolution.${value}`, value)}
                </label>
              ))}
            </div>
            {form.errors.resolution ? <p className="field-error">{form.errors.resolution}</p> : null}
          </div>
          <TextAreaField
            label={t("privacy.close.note")}
            hint={t("privacy.close.noteHint")}
            name="note"
            rows={3}
            maxLength={REPLY_MAX}
            value={form.values.note}
            onChange={(e) => form.set("note", e.target.value)}
            error={form.errors.note}
          />
          <div className="flex justify-end gap-2">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={form.submitting}>
              {form.submitting ? t("common.working") : t("privacy.close.submit")}
            </button>
          </div>
        </form>
      </Dialog>
    </section>
  );
}
