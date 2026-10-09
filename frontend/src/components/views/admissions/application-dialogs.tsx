"use client";

import { CircleCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { admissionsApi } from "@/lib/admissions-api";
import { api } from "@/lib/api";
import { todayInIndia } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import {
  ASSESSMENT_KINDS,
  ASSESSMENT_MODES,
  FEE_STATUSES,
  GENDERS,
  MAX_FEE_PAISE,
  PAYMENT_METHODS,
  type ApplicationDetail,
  type ApplicationFee,
  type ApplicationOffer,
  type ApplicationStage,
  type AssessmentKind,
  type AssessmentMode,
  type AssessmentSlot,
  type FeeStatus,
  type Gender,
  type PaymentMethod,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate, isValidAdmissionNo, isWholeNumberInRange } from "@/lib/validation";
import { kindLabel, methodLabel, modeLabel, moveLabel, paiseFromRupees, stageLabel } from "./admission-labels";
import { addDays, fromIndiaInput, toIndiaInput } from "./admission-time";

/** The parts of an application the dialogs need; a board card has them as well as the detail page. */
export type ApplicationRef = {
  id: string;
  childName: string;
  stage: ApplicationStage;
  classId: string;
  className: string;
  /** Unknown on a board card: the API then says whether it is needed. */
  gender?: Gender | null;
};

const orNull = (value: string) => value.trim() || null;

/* ------------------------------------------------------------------ moving between stages */

export type MoveValues = { note: string; offeredOn: string; validUntil: string };

export function validateMove(to: ApplicationStage, values: MoveValues): Problems {
  const problems: Problems = {};
  if (values.note.trim().length > 2000) problems.note = "validation.tooLong";
  if (to === "OFFERED") {
    if (!isPlainDate(values.offeredOn)) problems.offeredOn = "validation.date";
    if (values.validUntil && !isPlainDate(values.validUntil)) problems.offerValidUntil = "validation.date";
    else if (values.validUntil && !problems.offeredOn && values.validUntil < values.offeredOn) {
      problems.offerValidUntil = "admissions.v.offerEnds";
    }
  }
  return problems;
}

export function MoveStageDialog({
  application,
  to,
  onClose,
  onMoved,
}: {
  application: ApplicationRef;
  /** The stage picked from the menu; the dialog is open while it is set. */
  to: ApplicationStage | null;
  onClose: () => void;
  onMoved: (updated: ApplicationDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const initial = (): MoveValues => ({ note: "", offeredOn: today, validUntil: addDays(today, 14) });
  const form = useForm<MoveValues>(initial());
  const { values, errors } = form;
  const closing = to === "REJECTED" || to === "WITHDRAWN";

  const close = () => {
    form.reset(initial());
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!to) return;
    if (!form.check(validateMove(to, values), t, event.currentTarget)) return;
    let updated: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = await admissionsApi.moveStage(application.id, {
        stage: to,
        note: orNull(values.note),
        ...(to === "OFFERED" ? { offeredOn: values.offeredOn, offerValidUntil: values.validUntil || null } : {}),
      });
    });
    if (ok && updated) {
      form.reset(initial());
      onMoved(updated);
    }
  };

  return (
    <Dialog
      open={to !== null}
      onClose={close}
      title={to ? moveLabel(t, to) : ""}
      description={
        to
          ? t("admissions.moveDialog.description", {
              name: application.childName,
              from: stageLabel(t, application.stage),
              to: stageLabel(t, to),
            })
          : undefined
      }
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError ?? errors.stage} />
        {to === "OFFERED" ? (
          <div className="grid2">
            <TextField
              label={t("admissions.field.offeredOn")}
              name="offeredOn"
              type="date"
              value={values.offeredOn}
              onChange={(e) => form.set("offeredOn", e.target.value)}
              error={errors.offeredOn}
              required
            />
            <TextField
              label={t("admissions.field.validUntil")}
              name="offerValidUntil"
              type="date"
              min={values.offeredOn || undefined}
              hint={t("admissions.field.validUntil.hint")}
              value={values.validUntil}
              onChange={(e) => {
                form.set("validUntil", e.target.value);
                form.setErrors((prev) => ({ ...prev, offerValidUntil: undefined }));
              }}
              error={errors.offerValidUntil}
            />
          </div>
        ) : null}
        <TextAreaField
          label={t(closing ? "admissions.moveDialog.reason" : "admissions.field.note")}
          name="note"
          rows={3}
          maxLength={2000}
          hint={t("admissions.moveDialog.noteHint")}
          value={values.note}
          onChange={(e) => form.set("note", e.target.value)}
          error={errors.note}
          data-autofocus
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className={closing ? "btn btn-danger" : "btn btn-primary"} disabled={form.submitting}>
            {form.submitting ? t("common.working") : to ? moveLabel(t, to) : ""}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ admitting */

export type AdmitValues = {
  sectionId: string;
  admissionNo: string;
  rollNo: string;
  admissionDate: string;
  gender: "" | Gender;
};

export function validateAdmit(values: AdmitValues, needGender: boolean): Problems {
  const problems: Problems = {};
  if (!values.sectionId) problems.sectionId = "students.v.section";
  if (!values.admissionNo.trim()) problems.admissionNo = "validation.required";
  else if (!isValidAdmissionNo(values.admissionNo)) problems.admissionNo = "students.v.admissionNo";
  if (values.rollNo.trim() && !isWholeNumberInRange(values.rollNo, 1, 999)) problems.rollNo = "students.v.rollNo";
  if (values.admissionDate && !isPlainDate(values.admissionDate)) problems.admissionDate = "validation.date";
  if (needGender && !values.gender) problems.gender = "validation.choose";
  return problems;
}

/**
 * Creates the student record from an offered application: the child, the parents as guardians
 * and an enrollment in a section of the class applied for. Safe to repeat.
 */
export function AdmitDialog({
  open,
  application,
  onClose,
  onAdmitted,
}: {
  open: boolean;
  application: ApplicationRef;
  onClose: () => void;
  onAdmitted: (updated: ApplicationDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const initial = (): AdmitValues => ({
    sectionId: "",
    admissionNo: "",
    rollNo: "",
    admissionDate: today,
    gender: application.gender ?? "",
  });
  const form = useForm<AdmitValues>(initial());
  const { values, errors } = form;
  const [admitted, setAdmitted] = useState<ApplicationDetail | null>(null);
  const classes = useApiData(open ? "academics:classes" : null, api.listClasses);
  const sections = classes.data?.find((c) => c.id === application.classId)?.sections ?? [];
  const sectionValue = values.sectionId || (sections.length === 1 ? sections[0].id : "");
  const needGender = application.gender === null;

  const close = () => {
    form.reset(initial());
    setAdmitted(null);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const filled = { ...values, sectionId: sectionValue };
    if (!form.check(validateAdmit(filled, needGender), t, event.currentTarget)) return;
    let updated: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = await admissionsApi.admit(application.id, {
        sectionId: filled.sectionId,
        admissionNo: filled.admissionNo.trim(),
        rollNo: filled.rollNo.trim() ? Number(filled.rollNo) : null,
        admissionDate: filled.admissionDate || null,
        gender: filled.gender || null,
      });
    });
    if (ok && updated) {
      setAdmitted(updated);
      onAdmitted(updated);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("admissions.admit.title", { name: application.childName })}
      description={admitted ? undefined : t("admissions.admit.description", { className: application.className })}
      closeLabel={t("common.close")}
    >
      {admitted ? (
        <div className="flex flex-col items-start gap-3" data-testid="admit-done">
          <p className="alert alert-info w-full" role="status">
            <CircleCheck size={18} aria-hidden="true" className="mt-0.5 flex-none" />
            {t("admissions.admit.done", { name: admitted.childName })}
          </p>
          <div className="flex flex-wrap gap-2">
            {admitted.studentId ? (
              <Link href={`/app/students/${admitted.studentId}`} className="btn btn-primary">
                {t("admissions.admit.openStudent")}
              </Link>
            ) : null}
            <button type="button" className="btn" onClick={close}>
              {t("common.close")}
            </button>
          </div>
        </div>
      ) : classes.loading && !classes.data ? (
        <LoadingRows rows={3} />
      ) : classes.data && sections.length === 0 ? (
        <div className="flex flex-col items-start gap-3">
          <p className="alert alert-info w-full">{t("admissions.admit.noSections", { className: application.className })}</p>
          <button type="button" className="btn" onClick={close}>
            {t("common.close")}
          </button>
        </div>
      ) : (
        <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
          <FormAlert message={form.formError ?? errors.stage} />
          <div className="grid2">
            <SelectField
              label={t("students.field.section")}
              name="sectionId"
              value={sectionValue}
              onChange={(e) => form.set("sectionId", e.target.value)}
              error={errors.sectionId}
              required
              options={[
                { value: "", label: t("common.choose") },
                ...sections.map((s) => ({ value: s.id, label: `${s.className} ${s.name}` })),
              ]}
            />
            <TextField
              label={t("students.field.rollNo")}
              name="rollNo"
              inputMode="numeric"
              maxLength={3}
              hint={t("students.field.rollNo.hint")}
              value={values.rollNo}
              onChange={(e) => form.set("rollNo", e.target.value)}
              error={errors.rollNo}
            />
          </div>
          <div className="grid2">
            <TextField
              label={t("students.field.admissionNo")}
              name="admissionNo"
              autoComplete="off"
              maxLength={30}
              className="[&_input]:font-mono"
              placeholder={t("students.field.admissionNo.placeholder")}
              value={values.admissionNo}
              onChange={(e) => form.set("admissionNo", e.target.value)}
              error={errors.admissionNo}
              required
              data-autofocus
            />
            <TextField
              label={t("students.field.admissionDate")}
              name="admissionDate"
              type="date"
              value={values.admissionDate}
              onChange={(e) => form.set("admissionDate", e.target.value)}
              error={errors.admissionDate}
            />
          </div>
          {application.gender ? null : (
            <SelectField
              label={t("students.field.gender")}
              name="gender"
              value={values.gender}
              onChange={(e) => form.set("gender", e.target.value as AdmitValues["gender"])}
              error={errors.gender}
              required={needGender}
              hint={needGender ? undefined : t("admissions.admit.genderHint")}
              options={[
                { value: "", label: t("common.choose") },
                ...GENDERS.map((g) => ({ value: g, label: translateOr(t, `gender.${g}`, g) })),
              ]}
            />
          )}
          <p className="field-hint">{t("admissions.admit.guardiansHint")}</p>
          <div className="flex justify-end gap-2 pt-1">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={form.submitting}>
              {form.submitting ? t("common.working") : t("admissions.admit.submit")}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

/* ------------------------------------------------------------------ the application fee */

export type FeeValues = { status: FeeStatus; amount: string; method: "" | PaymentMethod; reference: string; paidOn: string; note: string };

export function validateFee(values: FeeValues, today: string): Problems {
  const problems: Problems = {};
  if (values.status === "PAID") {
    const paise = paiseFromRupees(values.amount);
    if (!values.amount.trim()) problems.amountPaise = "validation.required";
    else if (paise === null) problems.amountPaise = "admissions.v.amount";
    else if (paise > MAX_FEE_PAISE) problems.amountPaise = "admissions.v.amountTooLarge";
    if (!values.method) problems.method = "validation.choose";
  }
  if (values.reference.trim().length > 100) problems.reference = "validation.tooLong";
  if (!isPlainDate(values.paidOn)) problems.paidOn = "validation.date";
  else if (values.paidOn > today) problems.paidOn = "admissions.v.notFuture";
  if (values.note.trim().length > 2000) problems.note = "validation.tooLong";
  return problems;
}

export function FeeDialog({
  open,
  application,
  fee,
  onClose,
  onSaved,
}: {
  open: boolean;
  application: ApplicationRef;
  fee: ApplicationFee | null;
  onClose: () => void;
  onSaved: (updated: ApplicationDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const initial = (): FeeValues => ({
    status: fee?.status ?? "PAID",
    amount: fee?.amountPaise ? String(fee.amountPaise / 100) : "",
    method: fee?.method ?? "",
    reference: fee?.reference ?? "",
    paidOn: fee?.paidOn ?? today,
    note: "",
  });
  const form = useForm<FeeValues>(initial());
  const { values, errors } = form;

  const close = () => {
    form.reset(initial());
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateFee(values, today), t, event.currentTarget)) return;
    const paid = values.status === "PAID";
    let updated: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = await admissionsApi.recordFee(application.id, {
        status: values.status,
        amountPaise: paid ? paiseFromRupees(values.amount) : null,
        method: paid ? (values.method as PaymentMethod) : null,
        reference: orNull(values.reference),
        paidOn: values.paidOn,
        note: orNull(values.note),
      });
    });
    if (ok && updated) onSaved(updated);
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("admissions.fee.title")}
      description={t("admissions.fee.description", { name: application.childName })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="seg self-start" role="radiogroup" aria-label={t("admissions.fee.status")}>
          {FEE_STATUSES.map((status) => (
            <label key={status}>
              <input
                type="radio"
                name="status"
                value={status}
                checked={values.status === status}
                onChange={() => form.set("status", status)}
              />
              {t(status === "PAID" ? "admissions.fee.paid" : "admissions.fee.waived")}
            </label>
          ))}
        </div>
        {values.status === "PAID" ? (
          <div className="grid2">
            <TextField
              label={t("admissions.fee.amount")}
              name="amountPaise"
              inputMode="decimal"
              autoComplete="off"
              maxLength={12}
              placeholder="500"
              value={values.amount}
              onChange={(e) => {
                form.setValues((prev) => ({ ...prev, amount: e.target.value }));
                form.setErrors((prev) => ({ ...prev, amountPaise: undefined }));
              }}
              error={errors.amountPaise}
              required
              data-autofocus
            />
            <SelectField
              label={t("admissions.fee.method")}
              name="method"
              value={values.method}
              onChange={(e) => form.set("method", e.target.value as FeeValues["method"])}
              error={errors.method}
              required
              options={[
                { value: "", label: t("common.choose") },
                ...PAYMENT_METHODS.map((m) => ({ value: m, label: methodLabel(t, m) })),
              ]}
            />
          </div>
        ) : null}
        <div className="grid2">
          <TextField
            label={t(values.status === "PAID" ? "admissions.fee.paidOn" : "admissions.fee.waivedOn")}
            name="paidOn"
            type="date"
            max={today}
            value={values.paidOn}
            onChange={(e) => form.set("paidOn", e.target.value)}
            error={errors.paidOn}
            required
          />
          <TextField
            label={t("admissions.fee.reference")}
            name="reference"
            autoComplete="off"
            maxLength={100}
            hint={t("admissions.fee.reference.hint")}
            value={values.reference}
            onChange={(e) => form.set("reference", e.target.value)}
            error={errors.reference}
          />
        </div>
        <TextAreaField
          label={t("admissions.field.note")}
          name="note"
          rows={2}
          maxLength={2000}
          value={values.note}
          onChange={(e) => form.set("note", e.target.value)}
          error={errors.note}
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("admissions.fee.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ the offer */

export type OfferValues = { offeredOn: string; validUntil: string };

export function validateOffer(values: OfferValues): Problems {
  const problems: Problems = {};
  if (!isPlainDate(values.offeredOn)) problems.offeredOn = "validation.date";
  if (values.validUntil && !isPlainDate(values.validUntil)) problems.validUntil = "validation.date";
  else if (values.validUntil && !problems.offeredOn && values.validUntil < values.offeredOn) {
    problems.validUntil = "admissions.v.offerEnds";
  }
  return problems;
}

export function OfferDialog({
  open,
  application,
  offer,
  onClose,
  onSaved,
}: {
  open: boolean;
  application: ApplicationRef;
  offer: ApplicationOffer;
  onClose: () => void;
  onSaved: (updated: ApplicationDetail) => void;
}) {
  const { t } = useI18n();
  const initial = (): OfferValues => ({ offeredOn: offer.offeredOn, validUntil: offer.validUntil ?? "" });
  const form = useForm<OfferValues>(initial());
  const { values, errors } = form;

  const close = () => {
    form.reset(initial());
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateOffer(values), t, event.currentTarget)) return;
    let updated: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = await admissionsApi.updateOffer(application.id, {
        offeredOn: values.offeredOn,
        validUntil: values.validUntil || null,
      });
    });
    if (ok && updated) onSaved(updated);
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("admissions.offer.editTitle")}
      description={t("admissions.offer.editDescription", { name: application.childName })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("admissions.field.offeredOn")}
            name="offeredOn"
            type="date"
            value={values.offeredOn}
            onChange={(e) => form.set("offeredOn", e.target.value)}
            error={errors.offeredOn}
            required
            data-autofocus
          />
          <TextField
            label={t("admissions.field.validUntil")}
            name="validUntil"
            type="date"
            min={values.offeredOn || undefined}
            hint={t("admissions.field.validUntil.hint")}
            value={values.validUntil}
            onChange={(e) => form.set("validUntil", e.target.value)}
            error={errors.validUntil}
          />
        </div>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ tests and interviews */

export type SlotValues = {
  kind: AssessmentKind;
  at: string;
  mode: AssessmentMode;
  location: string;
  meetingLink: string;
  interviewerId: string;
};

export function isMeetingLink(link: string): boolean {
  const trimmed = link.trim();
  if (!trimmed.startsWith("https://") || /\s/.test(trimmed)) return false;
  try {
    return new URL(trimmed).hostname.length > 0;
  } catch {
    return false;
  }
}

export function validateSlot(values: SlotValues, now: Date): Problems {
  const problems: Problems = {};
  const at = fromIndiaInput(values.at);
  if (!at) problems.scheduledAt = "admissions.v.dateTime";
  else if (new Date(at).getTime() <= now.getTime()) problems.scheduledAt = "admissions.v.future";
  else if (new Date(at).getTime() > now.getTime() + 366 * 86_400_000) problems.scheduledAt = "admissions.v.withinYear";
  if (values.mode === "IN_PERSON") {
    if (!values.location.trim()) problems.location = "admissions.v.location";
    else if (values.location.trim().length > 200) problems.location = "validation.tooLong";
  } else if (!isMeetingLink(values.meetingLink)) {
    problems.meetingLink = "admissions.v.link";
  } else if (values.meetingLink.trim().length > 500) {
    problems.meetingLink = "validation.tooLong";
  }
  return problems;
}

/** Schedules a test or interview, or moves an existing one (`slot`). */
export function SlotDialog({
  open,
  application,
  slot,
  onClose,
  onSaved,
}: {
  open: boolean;
  application: ApplicationRef;
  slot: AssessmentSlot | null;
  onClose: () => void;
  onSaved: (updated: ApplicationDetail) => void;
}) {
  const { t } = useI18n();
  const initial = (): SlotValues => ({
    kind: slot?.kind ?? "INTERVIEW",
    at: slot ? toIndiaInput(slot.scheduledAt) : "",
    mode: slot?.mode ?? "IN_PERSON",
    location: slot?.location ?? "",
    meetingLink: slot?.meetingLink ?? "",
    interviewerId: slot?.interviewer?.id ?? "",
  });
  const form = useForm<SlotValues>(initial());
  const { values, errors } = form;
  const staff = useApiData(open ? "admissions:staff" : null, admissionsApi.staff);

  const close = () => {
    form.reset(initial());
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateSlot(values, new Date()), t, event.currentTarget)) return;
    const inPerson = values.mode === "IN_PERSON";
    const body = {
      kind: values.kind,
      scheduledAt: fromIndiaInput(values.at) ?? "",
      mode: values.mode,
      location: inPerson ? orNull(values.location) : null,
      meetingLink: inPerson ? null : orNull(values.meetingLink),
      interviewerId: values.interviewerId || null,
    };
    let updated: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = slot
        ? await admissionsApi.rescheduleSlot(application.id, slot.id, body)
        : await admissionsApi.scheduleSlot(application.id, body);
    });
    if (ok && updated) {
      form.reset(initial());
      onSaved(updated);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={slot ? t("admissions.slot.rescheduleTitle") : t("admissions.slot.scheduleTitle")}
      description={t("admissions.slot.description", { name: application.childName })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="seg self-start" role="radiogroup" aria-label={t("admissions.slot.kind")}>
          {ASSESSMENT_KINDS.map((kind) => (
            <label key={kind}>
              <input
                type="radio"
                name="kind"
                value={kind}
                checked={values.kind === kind}
                onChange={() => form.set("kind", kind)}
              />
              {kindLabel(t, kind)}
            </label>
          ))}
        </div>
        <TextField
          label={t("admissions.slot.at")}
          name="scheduledAt"
          type="datetime-local"
          hint={t("admissions.slot.at.hint")}
          value={values.at}
          onChange={(e) => {
            form.setValues((prev) => ({ ...prev, at: e.target.value }));
            form.setErrors((prev) => ({ ...prev, scheduledAt: undefined }));
          }}
          error={errors.scheduledAt}
          required
          data-autofocus
        />
        <div className="seg self-start" role="radiogroup" aria-label={t("admissions.slot.mode")}>
          {ASSESSMENT_MODES.map((mode) => (
            <label key={mode}>
              <input
                type="radio"
                name="mode"
                value={mode}
                checked={values.mode === mode}
                onChange={() => form.set("mode", mode)}
              />
              {modeLabel(t, mode)}
            </label>
          ))}
        </div>
        {values.mode === "IN_PERSON" ? (
          <TextField
            label={t("admissions.slot.location")}
            name="location"
            maxLength={200}
            placeholder={t("admissions.slot.location.placeholder")}
            value={values.location}
            onChange={(e) => form.set("location", e.target.value)}
            error={errors.location}
            required
          />
        ) : (
          <TextField
            label={t("admissions.slot.link")}
            name="meetingLink"
            type="url"
            inputMode="url"
            maxLength={500}
            placeholder="https://"
            value={values.meetingLink}
            onChange={(e) => form.set("meetingLink", e.target.value)}
            error={errors.meetingLink}
            required
          />
        )}
        <SelectField
          label={t("admissions.slot.interviewer")}
          name="interviewerId"
          value={values.interviewerId}
          onChange={(e) => form.set("interviewerId", e.target.value)}
          error={errors.interviewerId}
          options={[
            { value: "", label: t("admissions.slot.interviewer.none") },
            ...(staff.data ?? []).map((s) => ({ value: s.id, label: s.name })),
          ]}
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : slot ? t("admissions.slot.reschedule") : t("admissions.slot.schedule")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

export function OutcomeDialog({
  open,
  application,
  slot,
  onClose,
  onSaved,
}: {
  open: boolean;
  application: ApplicationRef;
  slot: AssessmentSlot;
  onClose: () => void;
  onSaved: (updated: ApplicationDetail) => void;
}) {
  const { t } = useI18n();
  const form = useForm<{ notes: string }>({ notes: slot.outcomeNotes ?? "" });
  const { values, errors } = form;

  const close = () => {
    form.reset({ notes: slot.outcomeNotes ?? "" });
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    if (!values.notes.trim()) problems.notes = "validation.required";
    else if (values.notes.trim().length > 2000) problems.notes = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    let updated: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      updated = await admissionsApi.recordOutcome(application.id, slot.id, values.notes.trim());
    });
    if (ok && updated) onSaved(updated);
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("admissions.slot.outcomeTitle")}
      description={t("admissions.slot.outcomeDescription", { kind: kindLabel(t, slot.kind), name: application.childName })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("admissions.slot.outcome")}
          name="notes"
          rows={4}
          maxLength={2000}
          value={values.notes}
          onChange={(e) => form.set("notes", e.target.value)}
          error={errors.notes}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
