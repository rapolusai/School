"use client";

import { ArrowLeft, Ban, FilePenLine, PenLine, Printer, Save } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { certificatesApi } from "@/lib/certificates-api";
import { formatDateTime, formatPaise } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import type { CertificateDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { CertificateDocument, usePageOrigin } from "./certificate-document";
import {
  CertificatesNav,
  CertificateStatusPill,
  placeholderLabel,
  subjectName,
  subjectSub,
  typeLabel,
  useCanIssue,
  useCanSign,
} from "./certificates-shared";

/* /app/certificates/[id]: the certificate's values (editable until signed), the actions and the printed page. */

/** Placeholders filled on signing: the editor never shows them. */
const FROZEN = new Set(["school", "date", "serialNo"]);

type EditorValues = { title: string; purpose: string; fields: Record<string, string> };

/** The editor's checks. Exported for tests. */
export function validateEditor(values: EditorValues): Problems {
  const problems: Problems = {};
  if (!values.title.trim()) problems.title = "validation.required";
  else if (values.title.trim().length > 120) problems.title = "validation.tooLong";
  if (values.purpose.trim().length > 300) problems.purpose = "validation.tooLong";
  for (const [key, value] of Object.entries(values.fields)) {
    if (value.length > 2000) problems[`field.${key}`] = "validation.tooLong";
  }
  return problems;
}

/** The keys shown in the editor: the type's placeholders (in order), then any others the certificate carries. */
export function editorKeys(certificate: Pick<CertificateDetail, "placeholders" | "fields">): string[] {
  const keys = certificate.placeholders.filter((k) => !FROZEN.has(k));
  for (const k of Object.keys(certificate.fields)) if (!keys.includes(k) && !FROZEN.has(k)) keys.push(k);
  return keys;
}

function toValues(certificate: CertificateDetail): EditorValues {
  const fields: Record<string, string> = {};
  for (const key of editorKeys(certificate)) fields[key] = certificate.fields[key] ?? "";
  return { title: certificate.title, purpose: certificate.purpose ?? "", fields };
}

export function CertificateView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const router = useRouter();
  const { toast } = useToast();
  const canIssue = useCanIssue();
  const canSign = useCanSign();
  const loaded = useApiData(`certificates:detail:${id}`, () => certificatesApi.detail(id));
  const [certificate, setCertificate] = useState<CertificateDetail | undefined>(undefined);
  const [synced, setSynced] = useState<CertificateDetail | undefined>(undefined);
  const [signing, setSigning] = useState(false);
  const [reasonFor, setReasonFor] = useState<"cancel" | "correct" | null>(null);
  const origin = usePageOrigin();
  const form = useForm<EditorValues>({ title: "", purpose: "", fields: {} });
  const { reset } = form;

  // A fresh load (first render, reload) replaces the working copy and the form; done during render, not in an effect.
  if (loaded.data && loaded.data !== synced) {
    setSynced(loaded.data);
    setCertificate(loaded.data);
    reset(toValues(loaded.data));
  }

  const apply = (next: CertificateDetail) => {
    setCertificate(next);
    reset(toValues(next));
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!certificate) return;
    if (!form.check(validateEditor(form.values), t, event.currentTarget)) return;
    const fields: Record<string, string> = {};
    for (const [key, value] of Object.entries(form.values.fields)) fields[key] = value.trim();
    const ok = await form.submit(t, async () => {
      apply(await certificatesApi.edit(certificate.id, { title: form.values.title.trim(), purpose: form.values.purpose.trim() || undefined, fields }));
    });
    if (ok) toast(t("certificates.saved"));
  };

  const sign = async () => {
    if (!certificate) return;
    apply(await certificatesApi.sign(certificate.id));
    setSigning(false);
    toast(t("certificates.signed.toast"));
  };

  if (loaded.error && !certificate) {
    return (
      <>
        <PageHead eyebrow={t("nav.certificates")} title={t("certificates.single")} />
        <CertificatesNav />
        <ErrorState error={loaded.error} onRetry={loaded.reload} />
      </>
    );
  }
  if (!certificate) {
    return (
      <>
        <PageHead eyebrow={t("nav.certificates")} title={t("certificates.single")} />
        <CertificatesNav />
        <LoadingRows rows={6} />
      </>
    );
  }

  const editable = certificate.editable && canIssue;
  const keys = editorKeys(certificate);

  return (
    <>
      <PageHead
        eyebrow={t("nav.certificates")}
        title={
          <span className="flex flex-wrap items-center gap-2">
            {typeLabel(t, certificate.type)}
            <CertificateStatusPill status={certificate.status} />
          </span>
        }
        actions={
          <>
            <Link href="/app/certificates" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {certificate.status === "ISSUED" || certificate.status === "CANCELLED" ? (
              <button type="button" className="btn" onClick={() => window.print()} data-testid="print-certificate">
                <Printer size={18} aria-hidden="true" />
                {t("certificates.print")}
              </button>
            ) : null}
            {certificate.status === "PREPARED" && canSign ? (
              <button type="button" className="btn btn-primary" onClick={() => setSigning(true)} data-testid="sign-certificate">
                <PenLine size={18} aria-hidden="true" />
                {t("certificates.sign")}
              </button>
            ) : null}
            {certificate.status === "ISSUED" && canIssue && !certificate.supersededById ? (
              <button type="button" className="btn" onClick={() => setReasonFor("correct")} data-testid="correct-certificate">
                <FilePenLine size={18} aria-hidden="true" />
                {t("certificates.correct")}
              </button>
            ) : null}
            {certificate.status !== "CANCELLED" && canIssue ? (
              <button type="button" className="btn btn-danger" onClick={() => setReasonFor("cancel")} data-testid="cancel-certificate">
                <Ban size={18} aria-hidden="true" />
                {t("certificates.cancel")}
              </button>
            ) : null}
          </>
        }
      />
      <CertificatesNav />

      <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div className="flex flex-col gap-3.5 no-print">
          <section className="card flex flex-col gap-2 text-[13.5px]" aria-label={t("certificates.about")}>
            <p>
              <b>{subjectName(t, certificate.subject)}</b>
              <span className="block text-ink-3">{subjectSub(certificate.subject)}</span>
              {!certificate.subject.active ? <span className="block text-ink-3">{t("certificates.subject.left")}</span> : null}
            </p>
            <p className="text-ink-2">
              {t("certificates.requestedLine", {
                name: certificate.requestedByName ?? "—",
                via: t(certificate.requestedVia === "FAMILY" ? "certificates.viaFamily" : "certificates.viaOffice"),
                when: formatDateTime(certificate.requestedAt, locale),
              })}
            </p>
            {certificate.signedAt ? (
              <p className="text-ink-2">{t("certificates.signedLine", { name: certificate.signedByName ?? "—", when: formatDateTime(certificate.signedAt, locale) })}</p>
            ) : null}
            {certificate.cancelledAt ? (
              <p className="text-bad" data-testid="cancel-reason">
                {t("certificates.cancelledLine", { name: certificate.cancelledByName ?? "—", when: formatDateTime(certificate.cancelledAt, locale), reason: certificate.cancelReason ?? "" })}
              </p>
            ) : null}
            {certificate.versionNo > 1 ? <p className="text-ink-2">{t("certificates.version", { n: certificate.versionNo })}</p> : null}
            {certificate.supersedesId ? (
              <Link href={`/app/certificates/${encodeURIComponent(certificate.supersedesId)}`} className="link">
                {t("certificates.supersedes")}
              </Link>
            ) : null}
            {certificate.supersededById ? (
              <Link href={`/app/certificates/${encodeURIComponent(certificate.supersededById)}`} className="link">
                {t("certificates.supersededBy")}
              </Link>
            ) : null}
            {certificate.type === "TRANSFER" && certificate.duesPaise !== null && certificate.duesPaise > 0 ? (
              <p className="alert alert-warn" role="status" data-testid="dues-warning">
                {t("certificates.duesWarning", { amount: formatPaise(certificate.duesPaise) })}
              </p>
            ) : null}
            {certificate.status === "REQUESTED" ? <p className="alert alert-info">{t("certificates.requested.hint")}</p> : null}
          </section>

          <form method="post" className="card flex flex-col gap-3" onSubmit={save} noValidate aria-label={t("certificates.editor")} data-testid="certificate-editor">
            <FormAlert message={form.formError} />
            <TextField
              label={t("certificates.col.title")}
              name="title"
              value={form.values.title}
              onChange={(e) => form.set("title", e.target.value)}
              maxLength={120}
              error={form.errors.title}
              disabled={!editable}
              required
            />
            <TextField
              label={t("certificates.col.purpose")}
              name="purpose"
              value={form.values.purpose}
              onChange={(e) => form.set("purpose", e.target.value)}
              maxLength={300}
              error={form.errors.purpose}
              disabled={!editable}
            />
            <div className="grid gap-3 sm:grid-cols-2">
              {keys.map((key) => {
                const value = form.values.fields[key] ?? "";
                const long = value.length > 80 || key === "address" || key === "reason";
                const props = {
                  label: placeholderLabel(t, key),
                  name: `field.${key}`,
                  value,
                  error: form.errors[`field.${key}`],
                  disabled: !editable,
                  maxLength: 2000,
                };
                return long ? (
                  <TextAreaField key={key} {...props} rows={2} className="sm:col-span-2" onChange={(e) => form.set("fields", { ...form.values.fields, [key]: e.target.value })} />
                ) : (
                  <TextField key={key} {...props} onChange={(e) => form.set("fields", { ...form.values.fields, [key]: e.target.value })} />
                );
              })}
            </div>
            {editable ? (
              <div className="flex justify-end">
                <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="save-certificate">
                  <Save size={18} aria-hidden="true" />
                  {form.submitting ? t("common.working") : t("common.save")}
                </button>
              </div>
            ) : (
              <p className="field-hint">{t(certificate.editable ? "certificates.editor.readOnly" : "certificates.editor.frozen")}</p>
            )}
          </form>
        </div>

        <section className="cert-preview" aria-label={t("certificates.preview")}>
          <CertificateDocument certificate={certificate} origin={origin} />
        </section>
      </div>

      <ConfirmDialog
        open={signing}
        title={t("certificates.sign.title")}
        body={t("certificates.sign.body", { serial: "", name: subjectName(t, certificate.subject) })}
        confirmLabel={t("certificates.sign")}
        onConfirm={sign}
        onClose={() => setSigning(false)}
        danger={false}
      />
      <ReasonDialog
        open={reasonFor !== null}
        kind={reasonFor ?? "cancel"}
        onClose={() => setReasonFor(null)}
        onDone={(next, corrected) => {
          setReasonFor(null);
          if (corrected) {
            toast(t("certificates.corrected.toast"));
            router.push(`/app/certificates/${encodeURIComponent(next.id)}`);
          } else {
            apply(next);
            toast(t("certificates.cancelled.toast"));
          }
        }}
        certificateId={certificate.id}
      />
    </>
  );
}

/** Asks why a certificate is cancelled or corrected; the reason goes into the audit trail. */
function ReasonDialog({
  open,
  kind,
  certificateId,
  onClose,
  onDone,
}: {
  open: boolean;
  kind: "cancel" | "correct";
  certificateId: string;
  onClose: () => void;
  onDone: (next: CertificateDetail, corrected: boolean) => void;
}) {
  const { t } = useI18n();
  const form = useForm<{ reason: string }>({ reason: "" });
  const close = () => {
    form.reset({ reason: "" });
    onClose();
  };
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!form.values.reason.trim()) problems.reason = "validation.required";
    else if (form.values.reason.trim().length > 500) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const reason = form.values.reason.trim();
    let next: CertificateDetail | null = null;
    const ok = await form.submit(t, async () => {
      next = kind === "cancel" ? await certificatesApi.cancel(certificateId, reason) : await certificatesApi.correct(certificateId, reason);
    });
    if (ok && next) {
      form.reset({ reason: "" });
      onDone(next, kind === "correct");
    }
  };
  return (
    <Dialog
      open={open}
      onClose={close}
      title={t(kind === "cancel" ? "certificates.cancel.title" : "certificates.correct.title")}
      description={t(kind === "cancel" ? "certificates.cancel.body" : "certificates.correct.body")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate data-testid="reason-form">
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("certificates.reason")}
          name="reason"
          value={form.values.reason}
          onChange={(e) => form.set("reason", e.target.value)}
          rows={3}
          maxLength={500}
          error={form.errors.reason}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className={kind === "cancel" ? "btn btn-danger" : "btn btn-primary"} disabled={form.submitting}>
            {form.submitting ? t("common.working") : t(kind === "cancel" ? "certificates.cancel" : "certificates.correct")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
