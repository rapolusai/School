"use client";

import { ArrowLeft, ArrowRight, BadgeCheck, FilePlus2, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { certificatesApi } from "@/lib/certificates-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { CertificateType, FamilyCertificates } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { CertificateDocument, usePageOrigin } from "./certificate-document";
import { CertificateStatusPill, typeLabel } from "./certificates-shared";
import { IdCardBack, IdCardFront } from "./id-card";

/* The family app: each child's certificates (request a bonafide or fee certificate, print an issued one) and ID card. */

/** /app/family/certificates */
export function FamilyCertificatesView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const mine = useApiData("me:certificates", certificatesApi.mine);
  const [requesting, setRequesting] = useState<string | null>(null);
  const children = mine.data?.children ?? [];
  return (
    <>
      <PageHead title={t("nav.certificates")} />
      {mine.error && !mine.data ? (
        <ErrorState error={mine.error} onRetry={mine.reload} />
      ) : !mine.data ? (
        <LoadingRows rows={4} />
      ) : children.length === 0 ? (
        <p className="empty">{t("certificates.family.none")}</p>
      ) : (
        children.map((child) => (
          <article key={child.studentId} className="card flex flex-col gap-3" aria-label={child.name} data-testid="family-certificates-child">
            <div className="card-head" style={{ marginBottom: 0 }}>
              <h2>
                {child.name}
                {child.className ? <span className="ml-2 text-[13px] font-normal text-ink-3">{child.className}</span> : null}
              </h2>
              <button type="button" className="btn btn-sm" onClick={() => setRequesting(child.studentId)} data-testid="request-certificate">
                <FilePlus2 size={16} aria-hidden="true" />
                {t("certificates.family.request")}
              </button>
            </div>
            {child.certificates.length === 0 ? (
              <p className="text-[13.5px] text-ink-3">{t("certificates.family.noCertificates")}</p>
            ) : (
              <ul className="rowcards" aria-label={t("certificates.family.list")}>
                {child.certificates.map((row) => (
                  <li key={row.id}>
                    <Link href={`/app/family/certificates/${encodeURIComponent(row.id)}`} className="rowcard" data-testid="family-certificate">
                      <span className="badge-ic" aria-hidden="true">
                        <BadgeCheck size={18} />
                      </span>
                      <span className="min-w-0 flex-1">
                        <b className="block">{typeLabel(t, row.type)}</b>
                        <span className="block text-[12.5px] text-ink-3">
                          {row.serialNo ?? row.purpose ?? ""}
                          {row.issuedOn ? ` · ${formatPlainDate(row.issuedOn, locale)}` : ""}
                        </span>
                      </span>
                      <CertificateStatusPill status={row.status} />
                      <ArrowRight size={18} aria-hidden="true" className="flex-none text-ink-3" />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex flex-col gap-2">
              <h3 className="text-[14px] font-semibold">{t("certificates.family.idCard")}</h3>
              {child.idCard ? (
                <div className="idcard-preview-small" data-testid="family-idcard">
                  <IdCardFront card={child.idCard} school={{ name: "", address: null, affiliationNo: null, logoText: null, city: null, phone: null }} />
                  <IdCardBack card={child.idCard} school={{ name: "", address: null, affiliationNo: null, logoText: null, city: null, phone: null }} />
                </div>
              ) : (
                <p className="text-[13.5px] text-ink-3" data-testid="family-idcard-none">
                  {t("certificates.family.noCard")}
                </p>
              )}
            </div>
          </article>
        ))
      )}
      <RequestDialog
        studentId={requesting}
        types={mine.data?.requestableTypes ?? []}
        onClose={() => setRequesting(null)}
        onDone={() => {
          setRequesting(null);
          toast(t("certificates.family.requested"));
          mine.reload();
        }}
      />
    </>
  );
}

type RequestValues = { type: CertificateType; purpose: string; from: string; to: string };

/** The same checks as the API. Exported for tests. */
export function validateRequest(values: RequestValues): Problems {
  const problems: Problems = {};
  if (!values.purpose.trim()) problems.purpose = "validation.required";
  else if (values.purpose.trim().length > 300) problems.purpose = "validation.tooLong";
  if (values.from && values.to && values.to < values.from) problems.to = "certificates.v.period";
  return problems;
}

function RequestDialog({ studentId, types, onClose, onDone }: { studentId: string | null; types: CertificateType[]; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const form = useForm<RequestValues>({ type: "BONAFIDE", purpose: "", from: "", to: "" });
  const close = () => {
    form.reset({ type: "BONAFIDE", purpose: "", from: "", to: "" });
    onClose();
  };
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!studentId) return;
    if (!form.check(validateRequest(form.values), t, event.currentTarget)) return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await certificatesApi.request({ studentId, type: v.type, purpose: v.purpose.trim(), from: v.from || undefined, to: v.to || undefined });
    });
    if (ok) {
      form.reset({ type: "BONAFIDE", purpose: "", from: "", to: "" });
      onDone();
    }
  };
  return (
    <Dialog open={studentId !== null} onClose={close} title={t("certificates.family.request")} description={t("certificates.family.requestIntro")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate data-testid="request-form">
        <FormAlert message={form.formError} />
        <SelectField label={t("certificates.col.type")} name="type" value={form.values.type} onChange={(e) => form.set("type", e.target.value as CertificateType)} options={types.map((x) => ({ value: x, label: typeLabel(t, x) }))} />
        <TextField label={t("certificates.col.purpose")} name="purpose" value={form.values.purpose} onChange={(e) => form.set("purpose", e.target.value)} maxLength={300} error={form.errors.purpose} required hint={t("certificates.family.purposeHint")} data-autofocus />
        {form.values.type === "FEE" ? (
          <div className="grid grid-cols-2 gap-3">
            <TextField label={t("certificates.field.from")} name="from" type="date" value={form.values.from} onChange={(e) => form.set("from", e.target.value)} error={form.errors.from} />
            <TextField label={t("certificates.field.to")} name="to" type="date" value={form.values.to} onChange={(e) => form.set("to", e.target.value)} error={form.errors.to} />
          </div>
        ) : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("certificates.family.send")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** /app/family/certificates/[id]: one certificate, printable once issued. */
export function FamilyCertificateView({ id }: { id: string }) {
  const { t } = useI18n();
  const detail = useApiData(`me:certificates:${id}`, () => certificatesApi.myCertificate(id));
  const origin = usePageOrigin();
  const certificate = detail.data;
  return (
    <>
      <PageHead
        title={certificate ? typeLabel(t, certificate.type) : t("certificates.single")}
        actions={
          <>
            <Link href="/app/family/certificates" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {certificate?.status === "ISSUED" ? (
              <button type="button" className="btn btn-primary" onClick={() => window.print()} data-testid="print-certificate">
                <Printer size={18} aria-hidden="true" />
                {t("certificates.family.print")}
              </button>
            ) : null}
          </>
        }
      />
      {detail.error && !certificate ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !certificate ? (
        <LoadingRows rows={6} />
      ) : certificate.status === "ISSUED" || certificate.status === "CANCELLED" ? (
        <>
          <p className="no-print text-[13.5px] text-ink-2">{t("certificates.family.printHint")}</p>
          <section className="cert-preview" aria-label={t("certificates.preview")}>
            <CertificateDocument certificate={certificate} origin={origin} />
          </section>
        </>
      ) : (
        <section className="card flex flex-col gap-2" data-testid="family-certificate-pending">
          <CertificateStatusPill status={certificate.status} />
          <p className="text-[13.5px] text-ink-2">{t("certificates.family.pending")}</p>
          {certificate.purpose ? <p className="text-[13.5px]">{t("certificates.col.purpose")}: {certificate.purpose}</p> : null}
        </section>
      )}
    </>
  );
}

/** The family home card: how many certificates were issued and whether the child has an ID card. */
export function FamilyCertificatesCard({ data, href, studentId }: { data: ApiData<FamilyCertificates>; href: string; studentId?: string }) {
  const { t } = useI18n();
  const children = (data.data?.children ?? []).filter((c) => !studentId || c.studentId === studentId);
  const issued = children.flatMap((c) => c.certificates).filter((c) => c.status === "ISSUED").length;
  const pending = children.flatMap((c) => c.certificates).filter((c) => c.status === "REQUESTED" || c.status === "PREPARED").length;
  const cards = children.filter((c) => c.idCard).length;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="family-certificates-card" data-testid="family-certificates-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="family-certificates-card">{t("nav.certificates")}</h2>
        <BadgeCheck size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {data.error && !data.data ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !data.data ? (
        <LoadingRows rows={2} />
      ) : (
        <div className="flex flex-col gap-1.5 text-[13.5px]">
          <p className={issued > 0 ? "font-semibold" : "text-ink-2"}>{plural(t, "certificates.family.issuedCount", issued)}</p>
          {pending > 0 ? <p>{plural(t, "certificates.family.pendingCount", pending)}</p> : null}
          <p className="text-ink-2">{t(cards > 0 ? "certificates.family.hasCard" : "certificates.family.noCard")}</p>
        </div>
      )}
      <Link href={href} className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
        {t("certificates.family.open")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </section>
  );
}
