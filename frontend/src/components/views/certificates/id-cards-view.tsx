"use client";

import { IdCard as IdCardIcon, ImageUp, Printer, RefreshCw } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { certificatesApi, type CardQuery } from "@/lib/certificates-api";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { fileProblem, formatBytes, MAX_FILE_BYTES } from "@/lib/files-api";
import { formatPlainDate, parseRupees } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { BLOOD_GROUPS, type CertificateSubjectType, type IdCard } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isValidIndianMobile } from "@/lib/validation";
import {
  CardStatusPill,
  CertificatesNav,
  maskPhone,
  SubjectPicker,
  subjectName,
  subjectSub,
  useCanIssue,
  useSectionOptions,
  type PickedSubject,
} from "./certificates-shared";
import { IdCardSheet } from "./id-card";

const PHOTO_ACCEPT = ".jpg,.jpeg,.png,.webp";

/** /app/certificates/id-cards: the register, cards for a section at once or one at a time, reissues, photos, printing. */
export function IdCardsView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const canIssue = useCanIssue();
  const sections = useSectionOptions();
  const [subjectType, setSubjectType] = useState<CertificateSubjectType>("STUDENT");
  const [sectionId, setSectionId] = useState("");
  const [all, setAll] = useState(false);
  const [issuing, setIssuing] = useState(false);
  const [reissuing, setReissuing] = useState<IdCard | null>(null);
  const [printing, setPrinting] = useState<IdCard[] | null>(null);
  const [batchError, setBatchError] = useState<string | null>(null);
  const [batching, setBatching] = useState(false);
  const query: CardQuery = { subjectType, sectionId: subjectType === "STUDENT" && sectionId ? sectionId : undefined, all, size: 500 };
  const register = useApiData(`certificates:cards:${JSON.stringify(query)}`, () => certificatesApi.cards(query));
  const data = register.data;
  const active = data?.items.filter((c) => c.status === "ACTIVE") ?? [];

  const issueBatch = async () => {
    setBatching(true);
    setBatchError(null);
    try {
      const result = await certificatesApi.issueCards(subjectType === "STAFF" ? { staff: true } : { sectionId });
      toast(plural(t, "certificates.card.batchDone", result.issued));
      register.reload();
    } catch (caught) {
      setBatchError(errorMessage(toApiError(caught), t));
    } finally {
      setBatching(false);
    }
  };

  const printCards = (cards: IdCard[]) => {
    setPrinting(cards);
    // Let the sheet render before the print dialog opens.
    setTimeout(() => window.print(), 50);
  };

  return (
    <>
      <PageHead
        eyebrow={t("nav.certificates")}
        title={t("certificates.cards.title")}
        actions={
          <>
            {canIssue ? (
              <button type="button" className="btn" onClick={() => setIssuing(true)} data-testid="issue-card">
                <IdCardIcon size={18} aria-hidden="true" />
                {t("certificates.card.issueOne")}
              </button>
            ) : null}
            {canIssue ? (
              <button
                type="button"
                className="btn"
                onClick={issueBatch}
                disabled={batching || (subjectType === "STUDENT" && !sectionId)}
                data-testid="issue-batch"
              >
                <IdCardIcon size={18} aria-hidden="true" />
                {batching ? t("common.working") : t(subjectType === "STAFF" ? "certificates.card.issueStaff" : "certificates.card.issueSection")}
              </button>
            ) : null}
            <button type="button" className="btn btn-primary" onClick={() => printCards(active)} disabled={active.length === 0} data-testid="print-cards">
              <Printer size={18} aria-hidden="true" />
              {t("certificates.card.printAll")}
            </button>
          </>
        }
      />
      <CertificatesNav />

      <section className="card flex flex-col gap-3.5 no-print">
        <form method="post" className="toolbar" aria-label={t("certificates.filters")} onSubmit={(e) => e.preventDefault()}>
          <SelectField
            label={t("certificates.card.for")}
            name="subjectType"
            value={subjectType}
            onChange={(e) => setSubjectType(e.target.value as CertificateSubjectType)}
            options={[
              { value: "STUDENT", label: t("certificates.subjectType.STUDENT") },
              { value: "STAFF", label: t("certificates.subjectType.STAFF") },
            ]}
            className="w-full sm:w-auto sm:min-w-40"
          />
          {subjectType === "STUDENT" ? (
            <SelectField
              label={t("certificates.card.section")}
              name="sectionId"
              value={sectionId}
              onChange={(e) => setSectionId(e.target.value)}
              options={[{ value: "", label: t("certificates.card.allSections") }, ...sections]}
              className="w-full sm:w-auto sm:min-w-44"
            />
          ) : null}
          <label className="check self-end pb-2">
            <input type="checkbox" name="all" checked={all} onChange={(e) => setAll(e.target.checked)} />
            <span>{t("certificates.card.showCancelled")}</span>
          </label>
        </form>
        <FormAlert message={batchError} />
        {register.error && !data ? (
          <ErrorState error={register.error} onRetry={register.reload} />
        ) : !data ? (
          <LoadingRows rows={5} />
        ) : data.items.length === 0 ? (
          <p className="empty" data-testid="cards-empty">
            {t(subjectType === "STUDENT" && !sectionId ? "certificates.card.emptyAll" : "certificates.card.empty")}
          </p>
        ) : (
          <>
            <p className="text-[13px] text-ink-2" role="status" data-testid="cards-count">
              {plural(t, "certificates.card.count", data.total)}
            </p>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="cards-table">
                <caption className="sr-only">{t("certificates.cards.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("certificates.card.no")}</th>
                    <th scope="col">{t("certificates.col.person")}</th>
                    <th scope="col">{t("certificates.card.bloodGroup")}</th>
                    <th scope="col">{t("certificates.card.emergency")}</th>
                    <th scope="col">{t("certificates.card.validUntil")}</th>
                    <th scope="col">{t("certificates.col.status")}</th>
                    <th scope="col" className="r">
                      {t("common.actions")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((card) => (
                    <tr key={card.id} data-testid="card-row">
                      <th scope="row" className="mono">
                        {card.cardNo}
                        {card.issueNo > 1 ? <span className="block text-[12px] font-normal text-ink-3">{t("certificates.card.issueNo", { n: card.issueNo })}</span> : null}
                      </th>
                      <td>
                        <span className="block font-semibold">{subjectName(t, card.subject)}</span>
                        <span className="block text-[12.5px] text-ink-3">{subjectSub(card.subject)}</span>
                      </td>
                      <td>{card.bloodGroup ?? "—"}</td>
                      <td className="mono" data-testid="card-phone">
                        {maskPhone(card.emergencyPhone)}
                      </td>
                      <td className="whitespace-nowrap">{formatPlainDate(card.validUntil, locale)}</td>
                      <td>
                        <CardStatusPill status={card.status} />
                      </td>
                      <td className="r">
                        <CardActions card={card} canIssue={canIssue} onPrint={() => printCards([card])} onReissue={() => setReissuing(card)} onPhoto={register.reload} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" aria-label={t("certificates.cards.title")}>
              {data.items.map((card) => (
                <li key={card.id} className="rowcard flex-wrap" data-testid="card-card">
                  <span className="min-w-0 flex-1">
                    <b className="block">{subjectName(t, card.subject)}</b>
                    <span className="block text-[12.5px] text-ink-3">
                      {card.cardNo} · {subjectSub(card.subject)}
                    </span>
                    <span className="block text-[12.5px] text-ink-3">
                      {card.bloodGroup ?? "—"} · {maskPhone(card.emergencyPhone)} · {formatPlainDate(card.validUntil, locale)}
                    </span>
                  </span>
                  <CardStatusPill status={card.status} />
                  <span className="flex w-full justify-end gap-2">
                    <CardActions card={card} canIssue={canIssue} onPrint={() => printCards([card])} onReissue={() => setReissuing(card)} onPhoto={register.reload} />
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>

      {data && printing && printing.length > 0 ? (
        <section className="idcard-preview" aria-label={t("certificates.card.preview")}>
          <p className="field-hint no-print">{t("certificates.card.printHint")}</p>
          <IdCardSheet cards={printing} school={data.school} />
        </section>
      ) : null}

      <IssueCardDialog
        open={issuing}
        onClose={() => setIssuing(false)}
        onDone={() => {
          setIssuing(false);
          toast(t("certificates.card.issued"));
          register.reload();
        }}
      />
      <ReissueDialog
        card={reissuing}
        onClose={() => setReissuing(null)}
        onDone={() => {
          setReissuing(null);
          toast(t("certificates.card.reissued"));
          register.reload();
        }}
      />
    </>
  );
}

function CardActions({ card, canIssue, onPrint, onReissue, onPhoto }: { card: IdCard; canIssue: boolean; onPrint: () => void; onReissue: () => void; onPhoto: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [uploading, setUploading] = useState(false);
  const upload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    const problem = fileProblem(file);
    if (problem) {
      toast(problem === "size" ? t("certificates.card.photoTooLarge", { max: formatBytes(MAX_FILE_BYTES) }) : t("certificates.card.photoType"));
      return;
    }
    setUploading(true);
    try {
      await certificatesApi.uploadPhoto(card.subject.type, card.subject.id, file);
      toast(t("certificates.card.photoSaved"));
      onPhoto();
    } catch (caught) {
      toast(errorMessage(toApiError(caught), t));
    } finally {
      setUploading(false);
    }
  };
  return (
    <span className="inline-flex flex-wrap justify-end gap-1.5">
      {card.status === "ACTIVE" ? (
        <button type="button" className="btn btn-sm" onClick={onPrint} data-testid="print-card">
          <Printer size={16} aria-hidden="true" />
          {t("certificates.print")}
        </button>
      ) : null}
      {canIssue ? (
        <label className={`btn btn-sm${uploading ? " opacity-60" : ""}`} data-testid="upload-photo">
          <ImageUp size={16} aria-hidden="true" />
          {uploading ? t("common.working") : t(card.photoFileId ? "certificates.card.replacePhoto" : "certificates.card.addPhoto")}
          <input type="file" name="photo" accept={PHOTO_ACCEPT} className="sr-only" onChange={upload} disabled={uploading} />
        </label>
      ) : null}
      {canIssue && card.status === "ACTIVE" ? (
        <button type="button" className="btn btn-sm" onClick={onReissue} data-testid="reissue-card">
          <RefreshCw size={16} aria-hidden="true" />
          {t("certificates.card.reissue")}
        </button>
      ) : null}
    </span>
  );
}

type IssueValues = { subjectType: CertificateSubjectType; bloodGroup: string; emergencyPhone: string; validUntil: string };

/** The same checks as the API. Exported for tests. */
export function validateCard(values: Pick<IssueValues, "emergencyPhone">, subject: PickedSubject | null): Problems {
  const problems: Problems = {};
  if (subject === null) problems.subjectSearch = "certificates.v.person";
  if (values.emergencyPhone.trim() && !isValidIndianMobile(values.emergencyPhone)) problems.emergencyPhone = "students.v.phone";
  return problems;
}

function IssueCardDialog({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const form = useForm<IssueValues>({ subjectType: "STUDENT", bloodGroup: "", emergencyPhone: "", validUntil: "" });
  const [subject, setSubject] = useState<PickedSubject | null>(null);
  const close = () => {
    form.reset({ subjectType: "STUDENT", bloodGroup: "", emergencyPhone: "", validUntil: "" });
    setSubject(null);
    onClose();
  };
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateCard(form.values, subject), t, event.currentTarget)) return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await certificatesApi.issueCard({
        subjectType: v.subjectType,
        studentId: v.subjectType === "STUDENT" ? subject?.id : undefined,
        staffUserId: v.subjectType === "STAFF" ? subject?.id : undefined,
        bloodGroup: v.bloodGroup || undefined,
        emergencyPhone: v.emergencyPhone.trim() || undefined,
        validUntil: v.validUntil || undefined,
      });
    });
    if (ok) {
      form.reset({ subjectType: "STUDENT", bloodGroup: "", emergencyPhone: "", validUntil: "" });
      setSubject(null);
      onDone();
    }
  };
  return (
    <Dialog open={open} onClose={close} title={t("certificates.card.issueOne")} description={t("certificates.card.issueIntro")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate data-testid="issue-card-form">
        <FormAlert message={form.formError} />
        <SelectField
          label={t("certificates.card.for")}
          name="subjectType"
          value={form.values.subjectType}
          onChange={(e) => {
            form.set("subjectType", e.target.value as CertificateSubjectType);
            setSubject(null);
          }}
          options={[
            { value: "STUDENT", label: t("certificates.subjectType.STUDENT") },
            { value: "STAFF", label: t("certificates.subjectType.STAFF") },
          ]}
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
            <span className="field-label">{t(form.values.subjectType === "STUDENT" ? "certificates.col.student" : "certificates.col.staff")}</span>
            <SubjectPicker subjectType={form.values.subjectType} onPick={setSubject} />
            {form.errors.subjectSearch ? <p className="field-error">{form.errors.subjectSearch}</p> : null}
          </div>
        )}
        <div className="grid grid-cols-2 gap-3">
          <SelectField
            label={t("certificates.card.bloodGroup")}
            name="bloodGroup"
            value={form.values.bloodGroup}
            onChange={(e) => form.set("bloodGroup", e.target.value)}
            options={[{ value: "", label: t("certificates.card.fromRecord") }, ...BLOOD_GROUPS.map((g) => ({ value: g, label: g }))]}
            error={form.errors.bloodGroup}
          />
          <TextField label={t("certificates.card.validUntil")} name="validUntil" type="date" value={form.values.validUntil} onChange={(e) => form.set("validUntil", e.target.value)} error={form.errors.validUntil} hint={t("certificates.card.validHint")} />
        </div>
        <TextField
          label={t("certificates.card.emergency")}
          name="emergencyPhone"
          inputMode="tel"
          value={form.values.emergencyPhone}
          onChange={(e) => form.set("emergencyPhone", e.target.value)}
          maxLength={20}
          error={form.errors.emergencyPhone}
          hint={t("certificates.card.emergencyHint")}
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("certificates.card.issue")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type ReissueValues = { reason: string; lostFee: string; feeNote: string; bloodGroup: string; emergencyPhone: string };

export function validateReissue(values: ReissueValues): Problems {
  const problems: Problems = {};
  if (!values.reason.trim()) problems.reason = "validation.required";
  else if (values.reason.trim().length > 300) problems.reason = "validation.tooLong";
  if (values.lostFee.trim()) {
    const paise = parseRupees(values.lostFee);
    if (paise === null || paise > 10_000_000) problems.lostFee = "certificates.v.fee";
  }
  if (values.feeNote.trim().length > 300) problems.feeNote = "validation.tooLong";
  if (values.emergencyPhone.trim() && !isValidIndianMobile(values.emergencyPhone)) problems.emergencyPhone = "students.v.phone";
  return problems;
}

function ReissueDialog({ card, onClose, onDone }: { card: IdCard | null; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const form = useForm<ReissueValues>({ reason: "", lostFee: "", feeNote: "", bloodGroup: "", emergencyPhone: "" });
  const close = () => {
    form.reset({ reason: "", lostFee: "", feeNote: "", bloodGroup: "", emergencyPhone: "" });
    onClose();
  };
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!card) return;
    if (!form.check(validateReissue(form.values), t, event.currentTarget)) return;
    const v = form.values;
    const ok = await form.submit(t, async () => {
      await certificatesApi.reissueCard(card.id, {
        reason: v.reason.trim(),
        lostFeePaise: v.lostFee.trim() ? (parseRupees(v.lostFee) ?? undefined) : undefined,
        feeNote: v.feeNote.trim() || undefined,
        bloodGroup: v.bloodGroup || undefined,
        emergencyPhone: v.emergencyPhone.trim() || undefined,
      });
    });
    if (ok) {
      form.reset({ reason: "", lostFee: "", feeNote: "", bloodGroup: "", emergencyPhone: "" });
      onDone();
    }
  };
  return (
    <Dialog open={card !== null} onClose={close} title={t("certificates.card.reissue")} description={card ? t("certificates.card.reissueIntro", { no: card.cardNo, name: subjectName(t, card.subject) }) : ""} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3" onSubmit={submit} noValidate data-testid="reissue-form">
        <FormAlert message={form.formError} />
        <TextField label={t("certificates.reason")} name="reason" value={form.values.reason} onChange={(e) => form.set("reason", e.target.value)} maxLength={300} error={form.errors.reason} required hint={t("certificates.card.reasonHint")} data-autofocus />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("certificates.card.lostFee")} name="lostFee" inputMode="decimal" value={form.values.lostFee} onChange={(e) => form.set("lostFee", e.target.value)} error={form.errors.lostFee} hint={t("certificates.card.lostFeeHint")} />
          <SelectField
            label={t("certificates.card.bloodGroup")}
            name="bloodGroup"
            value={form.values.bloodGroup}
            onChange={(e) => form.set("bloodGroup", e.target.value)}
            options={[{ value: "", label: t("certificates.card.keep") }, ...BLOOD_GROUPS.map((g) => ({ value: g, label: g }))]}
          />
        </div>
        <TextField label={t("certificates.card.emergency")} name="emergencyPhone" inputMode="tel" value={form.values.emergencyPhone} onChange={(e) => form.set("emergencyPhone", e.target.value)} maxLength={20} error={form.errors.emergencyPhone} hint={t("certificates.card.keepHint")} />
        <TextAreaField label={t("certificates.card.feeNote")} name="feeNote" value={form.values.feeNote} onChange={(e) => form.set("feeNote", e.target.value)} rows={2} maxLength={300} error={form.errors.feeNote} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("certificates.card.reissue")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
