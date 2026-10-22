"use client";

import { FilePlus2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { certificatesApi, type CertificateQuery } from "@/lib/certificates-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { CERTIFICATE_STATUSES, CERTIFICATE_TYPES, type CertificateStatus, type CertificateType } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  CertificatesNav,
  CertificateStatusPill,
  isStaffType,
  statusLabel,
  SubjectPicker,
  subjectName,
  subjectSub,
  typeLabel,
  useCanIssue,
  type PickedSubject,
} from "./certificates-shared";

const PAGE_SIZE = 50;

/** /app/certificates: the register of certificates, newest request first, and the "Prepare" dialog. */
export function CertificatesView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const canIssue = useCanIssue();
  const [status, setStatus] = useState<"" | CertificateStatus>("");
  const [type, setType] = useState<"" | CertificateType>("");
  const [page, setPage] = useState(0);
  const [preparing, setPreparing] = useState(false);
  const query: CertificateQuery = { status: status || undefined, type: type || undefined, page, size: PAGE_SIZE };
  const list = useApiData(`certificates:list:${JSON.stringify(query)}`, () => certificatesApi.list(query));
  const data = list.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <PageHead
        eyebrow={t("nav.certificates")}
        title={t("certificates.register.title")}
        actions={
          canIssue ? (
            <button type="button" className="btn btn-primary" onClick={() => setPreparing(true)}>
              <FilePlus2 size={18} aria-hidden="true" />
              {t("certificates.prepare")}
            </button>
          ) : null
        }
      />
      <CertificatesNav />

      <section className="card flex flex-col gap-3.5">
        <form method="post" className="toolbar" aria-label={t("certificates.filters")} onSubmit={(e) => e.preventDefault()}>
          <SelectField
            label={t("certificates.col.status")}
            name="status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as "" | CertificateStatus);
              setPage(0);
            }}
            options={[{ value: "", label: t("certificates.filter.allStatuses") }, ...CERTIFICATE_STATUSES.map((s) => ({ value: s, label: statusLabel(t, s) }))]}
            className="w-full sm:w-auto sm:min-w-44"
          />
          <SelectField
            label={t("certificates.col.type")}
            name="type"
            value={type}
            onChange={(e) => {
              setType(e.target.value as "" | CertificateType);
              setPage(0);
            }}
            options={[{ value: "", label: t("certificates.filter.allTypes") }, ...CERTIFICATE_TYPES.map((x) => ({ value: x, label: typeLabel(t, x) }))]}
            className="w-full sm:w-auto sm:min-w-44"
          />
        </form>

        {list.error && !data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : data.items.length === 0 ? (
          <p className="empty" data-testid="certificates-empty">
            {t("certificates.register.empty")}
          </p>
        ) : (
          <>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="certificates-table">
                <caption className="sr-only">{t("certificates.register.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("certificates.col.serial")}</th>
                    <th scope="col">{t("certificates.col.type")}</th>
                    <th scope="col">{t("certificates.col.person")}</th>
                    <th scope="col">{t("certificates.col.purpose")}</th>
                    <th scope="col">{t("certificates.col.status")}</th>
                    <th scope="col">{t("certificates.col.issuedOn")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((row) => (
                    <tr key={row.id} data-testid="certificate-row">
                      <th scope="row">
                        <Link href={`/app/certificates/${encodeURIComponent(row.id)}`} className="link mono">
                          {row.serialNo ?? t("certificates.doc.unnumbered")}
                        </Link>
                        {row.versionNo > 1 ? <span className="block text-[12px] text-ink-3">{t("certificates.version", { n: row.versionNo })}</span> : null}
                      </th>
                      <td>{typeLabel(t, row.type)}</td>
                      <td>
                        <span className="block font-semibold">{subjectName(t, row.subject)}</span>
                        <span className="block text-[12.5px] text-ink-3">{subjectSub(row.subject)}</span>
                      </td>
                      <td>{row.purpose ?? "—"}</td>
                      <td>
                        <CertificateStatusPill status={row.status} />
                        {row.requestedVia === "FAMILY" ? <span className="block text-[12px] text-ink-3">{t("certificates.viaFamily")}</span> : null}
                      </td>
                      <td className="whitespace-nowrap">{row.issuedOn ? formatPlainDate(row.issuedOn, locale) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" aria-label={t("certificates.register.title")}>
              {data.items.map((row) => (
                <li key={row.id}>
                  <Link href={`/app/certificates/${encodeURIComponent(row.id)}`} className="rowcard" data-testid="certificate-card">
                    <span className="min-w-0 flex-1">
                      <b className="block">{subjectName(t, row.subject)}</b>
                      <span className="block text-[12.5px] text-ink-3">
                        {typeLabel(t, row.type)} · {row.serialNo ?? t("certificates.doc.unnumbered")}
                      </span>
                    </span>
                    <CertificateStatusPill status={row.status} />
                  </Link>
                </li>
              ))}
            </ul>
            {pages > 1 ? (
              <nav className="flex items-center justify-between gap-2 text-[13px]" aria-label={t("certificates.pages")}>
                <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  {t("common.previous")}
                </button>
                <span>{t("certificates.pageOf", { page: page + 1, pages })}</span>
                <button type="button" className="btn btn-sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>
                  {t("common.next")}
                </button>
              </nav>
            ) : null}
          </>
        )}
      </section>

      <PrepareDialog open={preparing} onClose={() => setPreparing(false)} />
    </>
  );
}

type PrepareValues = { type: CertificateType; purpose: string; from: string; to: string };

/** The same checks as the API. Exported for tests. */
export function validatePrepare(values: PrepareValues, subject: PickedSubject | null): Problems {
  const problems: Problems = {};
  if (!subject) problems.subjectSearch = isStaffType(values.type) ? "certificates.v.staff" : "certificates.v.student";
  if (values.purpose.trim().length > 300) problems.purpose = "validation.tooLong";
  if (values.from && values.to && values.to < values.from) problems.to = "certificates.v.period";
  return problems;
}

/** Choose the type and the person; the certificate opens prepared, ready to edit and sign. */
export function PrepareDialog({ open, onClose, initialType = "BONAFIDE" }: { open: boolean; onClose: () => void; initialType?: CertificateType }) {
  const { t } = useI18n();
  const router = useRouter();
  const form = useForm<PrepareValues>({ type: initialType, purpose: "", from: "", to: "" });
  const [subject, setSubject] = useState<PickedSubject | null>(null);
  const staff = isStaffType(form.values.type);

  const close = () => {
    form.reset({ type: initialType, purpose: "", from: "", to: "" });
    setSubject(null);
    onClose();
  };

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validatePrepare(form.values, subject), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      const created = await certificatesApi.prepare({
        type: form.values.type,
        studentId: staff ? undefined : subject?.id,
        staffUserId: staff ? subject?.id : undefined,
        purpose: form.values.purpose.trim() || undefined,
        from: form.values.from || undefined,
        to: form.values.to || undefined,
      });
      router.push(`/app/certificates/${encodeURIComponent(created.id)}`);
    });
    if (ok) close();
  };

  return (
    <Dialog open={open} onClose={close} title={t("certificates.prepare")} description={t("certificates.prepare.intro")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate data-testid="prepare-form">
        <FormAlert message={form.formError} />
        <SelectField
          label={t("certificates.col.type")}
          name="type"
          value={form.values.type}
          onChange={(e) => {
            const next = e.target.value as CertificateType;
            if (isStaffType(next) !== staff) setSubject(null);
            form.set("type", next);
          }}
          options={CERTIFICATE_TYPES.map((x) => ({ value: x, label: typeLabel(t, x) }))}
        />
        {subject ? (
          <div className="flex items-center justify-between gap-2 rounded-lg border border-line p-2.5" data-testid="picked-subject">
            <span className="min-w-0">
              <b className="block">{subject.name}</b>
              <span className="block text-[12.5px] text-ink-3">{[subject.detail, subject.code].filter(Boolean).join(" · ")}</span>
            </span>
            <button type="button" className="btn btn-sm" onClick={() => setSubject(null)}>
              {t("certificates.pick.change")}
            </button>
          </div>
        ) : (
          <div className="field">
            <span className="field-label">{t(staff ? "certificates.col.staff" : "certificates.col.student")}</span>
            <SubjectPicker subjectType={staff ? "STAFF" : "STUDENT"} onPick={setSubject} />
            {form.errors.subjectSearch ? <p className="field-error">{form.errors.subjectSearch}</p> : null}
          </div>
        )}
        <TextField
          label={t("certificates.col.purpose")}
          name="purpose"
          value={form.values.purpose}
          onChange={(e) => form.set("purpose", e.target.value)}
          maxLength={300}
          error={form.errors.purpose}
          hint={t("certificates.purpose.hint")}
        />
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
            {form.submitting ? t("common.working") : t("certificates.prepare.go")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
