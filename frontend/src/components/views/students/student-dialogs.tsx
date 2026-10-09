"use client";

import { useState } from "react";
import { generatePassword } from "@/components/views/add-user-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { api } from "@/lib/api";
import { todayInIndia } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import type { Guardian, StudentDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate, isValidEmail, MIN_PASSWORD_LENGTH } from "@/lib/validation";
import {
  EMPTY_GUARDIAN,
  GuardianInputs,
  guardianBody,
  ProfileFields,
  profileBody,
  profileOf,
  sectionOptions,
  validateGuardianValues,
  validatePlacement,
  validateProfileValues,
  type GuardianValues,
  type ProfileValues,
} from "./student-form";

function Actions({ submitting, onCancel, label }: { submitting: boolean; onCancel: () => void; label?: string }) {
  const { t } = useI18n();
  return (
    <div className="flex justify-end gap-2 pt-1">
      <button type="button" className="btn" onClick={onCancel}>
        {t("common.cancel")}
      </button>
      <button type="submit" className="btn btn-primary" disabled={submitting}>
        {submitting ? t("common.saving") : (label ?? t("common.save"))}
      </button>
    </div>
  );
}

/* ------------------------------------------------------------------ edit profile */

type EditValues = ProfileValues & { sectionId: string; rollNo: string };

export function EditStudentDialog({
  open,
  student,
  onClose,
  onSaved,
}: {
  open: boolean;
  student: StudentDetail;
  onClose: () => void;
  onSaved: (student: StudentDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const active = student.status === "ACTIVE";
  const initial: EditValues = {
    ...profileOf(student),
    sectionId: student.currentEnrollment?.sectionId ?? "",
    rollNo: student.currentEnrollment?.rollNo == null ? "" : String(student.currentEnrollment.rollNo),
  };
  const form = useForm<EditValues>(initial);
  const classes = useApiData(open && active ? "academics:classes" : null, api.listClasses);
  const options = sectionOptions(classes.data ?? [], (used, capacity) => t("setup.sections.seats", { used, capacity }));

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems = {
      ...validateProfileValues(form.values, today),
      ...(active ? validatePlacement(form.values, false) : {}),
    };
    if (!form.check(problems, t, event.currentTarget)) return;
    let saved: StudentDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await api.updateStudent(student.id, {
        ...profileBody(form.values),
        sectionId: active && form.values.sectionId ? form.values.sectionId : null,
        rollNo: active && form.values.sectionId && form.values.rollNo.trim() ? Number(form.values.rollNo) : null,
      });
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog open={open} onClose={close} title={t("student.edit.title")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <ProfileFields
          values={form.values}
          errors={form.errors}
          set={(field, value) => form.set(field, value as EditValues[typeof field])}
          today={today}
          autoFocus
        />
        {active ? (
          classes.loading && !classes.data ? (
            <LoadingRows rows={1} />
          ) : (
            <div className="grid2">
              <SelectField
                label={t("students.field.section")}
                name="sectionId"
                value={form.values.sectionId}
                onChange={(e) => form.set("sectionId", e.target.value)}
                error={form.errors.sectionId}
                hint={t("student.edit.sectionHint")}
                options={[{ value: "", label: t("student.edit.noSection") }, ...options]}
              />
              <TextField
                label={t("students.field.rollNo")}
                name="rollNo"
                inputMode="numeric"
                maxLength={3}
                value={form.values.rollNo}
                onChange={(e) => form.set("rollNo", e.target.value)}
                error={form.errors.rollNo}
              />
            </div>
          )
        ) : null}
        <Actions submitting={form.submitting} onCancel={close} />
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ transfer / withdraw */

type LeaveValues = { status: "TRANSFERRED" | "WITHDRAWN"; leftOn: string; reason: string };

export function validateLeave(values: LeaveValues, admissionDate: string): Problems {
  const problems: Problems = {};
  if (!isPlainDate(values.leftOn)) problems.leftOn = "validation.date";
  else if (values.leftOn < admissionDate) problems.leftOn = "student.leave.beforeAdmission";
  if (!values.reason.trim()) problems.reason = "validation.required";
  else if (values.reason.trim().length > 500) problems.reason = "validation.tooLong";
  return problems;
}

export function LeaveDialog({
  open,
  student,
  onClose,
  onSaved,
}: {
  open: boolean;
  student: StudentDetail;
  onClose: () => void;
  onSaved: (student: StudentDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const initial: LeaveValues = { status: "TRANSFERRED", leftOn: today, reason: "" };
  const form = useForm<LeaveValues>(initial);
  const close = () => {
    form.reset(initial);
    onClose();
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateLeave(form.values, student.admissionDate), t, event.currentTarget)) return;
    let saved: StudentDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await api.leaveStudent(student.id, {
        status: form.values.status,
        leftOn: form.values.leftOn,
        reason: form.values.reason.trim(),
      });
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("student.leave.title", { name: student.fullName })}
      description={t("student.leave.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="field-label mb-1.5">{t("student.leave.kind")}</legend>
          <div className="seg">
            {(["TRANSFERRED", "WITHDRAWN"] as const).map((status) => (
              <label key={status}>
                <input
                  type="radio"
                  name="status"
                  value={status}
                  checked={form.values.status === status}
                  onChange={() => form.set("status", status)}
                />
                {t(status === "TRANSFERRED" ? "student.leave.transfer" : "student.leave.withdraw")}
              </label>
            ))}
          </div>
        </fieldset>
        <TextField
          label={t("student.leave.leftOn")}
          name="leftOn"
          type="date"
          min={student.admissionDate}
          value={form.values.leftOn}
          onChange={(e) => form.set("leftOn", e.target.value)}
          error={form.errors.leftOn}
          required
        />
        <TextAreaField
          label={t("student.leave.reason")}
          name="reason"
          rows={3}
          maxLength={500}
          placeholder={t("student.leave.reason.placeholder")}
          value={form.values.reason}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          required
          data-autofocus
        />
        <Actions submitting={form.submitting} onCancel={close} label={t("student.leave.submit")} />
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ guardian */

export function GuardianDialog({
  open,
  studentId,
  guardian,
  onClose,
  onSaved,
}: {
  open: boolean;
  studentId: string;
  guardian: Guardian | null;
  onClose: () => void;
  onSaved: (guardian: Guardian) => void;
}) {
  const { t } = useI18n();
  const initial: GuardianValues & { primary: boolean } = guardian
    ? {
        name: guardian.name,
        relation: guardian.relation,
        phone: guardian.phone,
        email: guardian.email ?? "",
        occupation: guardian.occupation ?? "",
        primary: guardian.primary,
      }
    : { ...EMPTY_GUARDIAN, primary: false };
  const form = useForm(initial);
  const close = () => {
    form.reset(initial);
    onClose();
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateGuardianValues(form.values), t, event.currentTarget)) return;
    const body = guardianBody(form.values, form.values.primary);
    let saved: Guardian | undefined;
    const ok = await form.submit(t, async () => {
      saved = guardian
        ? await api.updateGuardian(studentId, guardian.id, body)
        : await api.addGuardian(studentId, body);
    });
    if (ok && saved) onSaved(saved);
  };
  return (
    <Dialog
      open={open}
      onClose={close}
      title={guardian ? t("student.guardian.edit") : t("student.guardian.add")}
      description={guardian ? t("student.guardian.sharedNote") : undefined}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <GuardianInputs
          values={form.values}
          errors={form.errors}
          autoFocus
          onChange={(next, field) => {
            form.setValues((prev) => ({ ...prev, ...next }));
            form.setErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
          }}
        />
        {!guardian?.primary ? (
          <label className="check">
            <input
              type="checkbox"
              name="primary"
              checked={form.values.primary}
              onChange={(e) => form.set("primary", e.target.checked)}
            />
            <span>{t("students.guardian.primary")}</span>
          </label>
        ) : null}
        <Actions submitting={form.submitting} onCancel={close} />
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ sign-in */

type SignInValues = { mode: "CREATE" | "LINK"; email: string; password: string };

export function validateSignIn(values: SignInValues): Problems {
  const problems: Problems = {};
  if (!isValidEmail(values.email)) problems.email = "validation.email";
  if (values.mode === "CREATE" && values.password.length < MIN_PASSWORD_LENGTH) problems.password = "validation.password";
  return problems;
}

/**
 * Gives a parent or the student a sign-in: a new account with a temporary password, or an
 * existing account (with the Parent or Student role) in this school.
 */
export function SignInDialog({
  open,
  title,
  description,
  defaultEmail,
  onClose,
  onSubmit: send,
  onSaved,
}: {
  open: boolean;
  title: string;
  description: string;
  defaultEmail: string;
  onClose: () => void;
  onSubmit: (values: SignInValues) => Promise<void>;
  onSaved: (values: SignInValues) => void;
}) {
  const { t } = useI18n();
  const initial: SignInValues = { mode: "CREATE", email: defaultEmail, password: "" };
  const form = useForm<SignInValues>(initial);
  const close = () => {
    form.reset(initial);
    onClose();
  };
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateSignIn(form.values), t, event.currentTarget)) return;
    const values = { ...form.values, email: form.values.email.trim() };
    const ok = await form.submit(t, () => send(values));
    if (ok) onSaved(values);
  };
  return (
    <Dialog open={open} onClose={close} title={title} description={description} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="seg self-start">
          {(["CREATE", "LINK"] as const).map((mode) => (
            <label key={mode}>
              <input
                type="radio"
                name="mode"
                value={mode}
                checked={form.values.mode === mode}
                onChange={() => form.set("mode", mode)}
              />
              {t(mode === "CREATE" ? "student.signIn.create" : "student.signIn.link")}
            </label>
          ))}
        </div>
        <TextField
          label={t("users.field.email")}
          name="email"
          type="email"
          autoComplete="off"
          maxLength={254}
          value={form.values.email}
          onChange={(e) => form.set("email", e.target.value)}
          error={form.errors.email}
          hint={form.values.mode === "LINK" ? t("student.signIn.linkHint") : undefined}
          required
          data-autofocus
        />
        {form.values.mode === "CREATE" ? (
          <TextField
            label={t("users.field.password")}
            name="password"
            type="text"
            autoComplete="new-password"
            spellCheck={false}
            className="[&_input]:font-mono"
            value={form.values.password}
            onChange={(e) => form.set("password", e.target.value)}
            hint={t("users.field.password.hint")}
            error={form.errors.password}
            minLength={MIN_PASSWORD_LENGTH}
            required
            labelAction={
              <button type="button" className="linkbtn text-[13px]" onClick={() => form.set("password", generatePassword())}>
                {t("users.generate")}
              </button>
            }
          />
        ) : null}
        <Actions submitting={form.submitting} onCancel={close} label={t("student.signIn.submit")} />
      </form>
    </Dialog>
  );
}
