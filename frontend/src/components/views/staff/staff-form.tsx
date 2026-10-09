"use client";

import { useId, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { generatePassword } from "@/components/views/add-user-dialog";
import { todayInIndia } from "@/lib/format";
import { roleLabel, translateOr, useI18n } from "@/lib/i18n";
import { staffApi } from "@/lib/staff-api";
import {
  EMPLOYMENT_TYPES,
  type EmploymentType,
  type StaffDetail,
  type StaffLeavingRequest,
  type StaffProfileRequest,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  isPlainDate,
  isValidEmail,
  isValidIndianMobile,
  MIN_PASSWORD_LENGTH,
  normalizeIndianMobile,
} from "@/lib/validation";

/** The API's employee code rule (docs/api/phase-1-staff.md). */
export const EMPLOYEE_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9/._-]*$/;

export type StaffValues = {
  name: string;
  email: string;
  password: string;
  roles: string[];
  employeeCode: string;
  designation: string;
  departmentId: string;
  employmentType: EmploymentType;
  dateOfJoining: string;
  mobile: string;
  qualifications: string;
  emergencyContactName: string;
  emergencyContactMobile: string;
};

export const EMPTY_STAFF: StaffValues = {
  name: "",
  email: "",
  password: "",
  roles: [],
  employeeCode: "",
  designation: "",
  departmentId: "",
  employmentType: "PERMANENT",
  dateOfJoining: "",
  mobile: "",
  qualifications: "",
  emergencyContactName: "",
  emergencyContactMobile: "",
};

/** One year ahead, as the API allows for joining dates. */
function yearAhead(today: string): string {
  return `${Number(today.slice(0, 4)) + 1}${today.slice(4)}`;
}

/**
 * The same checks the API makes. `withSignIn` adds the sign-in fields of "Add staff".
 * Pure and exported for tests.
 */
export function validateStaff(values: StaffValues, withSignIn: boolean, today: string): Problems {
  const problems: Problems = {};
  if (withSignIn) {
    if (!values.name.trim()) problems.name = "validation.required";
    if (!isValidEmail(values.email)) problems.email = "validation.email";
    if (values.password.length < MIN_PASSWORD_LENGTH) problems.password = "validation.password";
    if (values.roles.length === 0) problems.roles = "validation.roles";
  }
  const code = values.employeeCode.trim();
  if (!code) problems.employeeCode = "validation.required";
  else if (code.length > 30 || !EMPLOYEE_CODE_PATTERN.test(code)) problems.employeeCode = "staff.v.employeeCode";
  if (!values.designation.trim()) problems.designation = "validation.required";
  else if (values.designation.trim().length > 100) problems.designation = "validation.tooLong";
  if (!isPlainDate(values.dateOfJoining)) problems.dateOfJoining = "validation.required";
  else if (values.dateOfJoining > yearAhead(today)) problems.dateOfJoining = "staff.v.joiningFuture";
  if (!values.mobile.trim()) problems.mobile = "validation.required";
  else if (!isValidIndianMobile(values.mobile)) problems.mobile = "staff.v.mobile";
  if (values.qualifications.trim().length > 500) problems.qualifications = "validation.tooLong";
  const emergencyName = values.emergencyContactName.trim();
  const emergencyMobile = values.emergencyContactMobile.trim();
  if (emergencyMobile && !isValidIndianMobile(emergencyMobile)) problems.emergencyContactMobile = "staff.v.mobile";
  else if (emergencyName && !emergencyMobile) problems.emergencyContactMobile = "staff.v.emergencyBoth";
  if (!emergencyName && emergencyMobile) problems.emergencyContactName = "staff.v.emergencyBoth";
  return problems;
}

export function profileRequest(values: StaffValues): StaffProfileRequest {
  const emergencyMobile = values.emergencyContactMobile.trim();
  return {
    employeeCode: values.employeeCode.trim(),
    designation: values.designation.trim(),
    departmentId: values.departmentId || null,
    employmentType: values.employmentType,
    dateOfJoining: values.dateOfJoining,
    mobile: normalizeIndianMobile(values.mobile) ?? values.mobile.trim(),
    qualifications: values.qualifications.trim() || null,
    emergencyContactName: values.emergencyContactName.trim() || null,
    emergencyContactMobile: emergencyMobile ? (normalizeIndianMobile(emergencyMobile) ?? emergencyMobile) : null,
  };
}

export function valuesOf(detail: StaffDetail): StaffValues {
  const p = detail.profile;
  return {
    ...EMPTY_STAFF,
    name: detail.name,
    email: detail.email,
    roles: detail.roles,
    employeeCode: p?.employeeCode ?? "",
    designation: p?.designation ?? "",
    departmentId: p?.department?.id ?? "",
    employmentType: p?.employmentType ?? "PERMANENT",
    dateOfJoining: p?.dateOfJoining ?? "",
    mobile: p?.mobile ?? "",
    qualifications: p?.qualifications ?? "",
    emergencyContactName: p?.emergencyContactName ?? "",
    emergencyContactMobile: p?.emergencyContactMobile ?? "",
  };
}

type Form = ReturnType<typeof useForm<StaffValues>>;

function ProfileInputs({ form, open }: { form: Form; open: boolean }) {
  const { t } = useI18n();
  const listId = useId();
  const departments = useApiData(open ? "staff:departments" : null, staffApi.departments);
  const designations = useApiData(open ? "staff:designations" : null, staffApi.designations);
  const { values, set, errors } = form;
  return (
    <>
      <div className="grid2">
        <TextField
          label={t("staff.field.employeeCode")}
          name="employeeCode"
          value={values.employeeCode}
          onChange={(e) => set("employeeCode", e.target.value)}
          error={errors.employeeCode}
          maxLength={30}
          autoComplete="off"
          className="[&_input]:font-mono"
          required
        />
        <TextField
          label={t("staff.field.designation")}
          name="designation"
          value={values.designation}
          onChange={(e) => set("designation", e.target.value)}
          error={errors.designation}
          maxLength={100}
          list={listId}
          autoComplete="off"
          hint={t("staff.field.designation.hint")}
          required
        />
        <datalist id={listId}>
          {(designations.data ?? []).map((d) => (
            <option key={d} value={d} />
          ))}
        </datalist>
        <SelectField
          label={t("staff.field.department")}
          name="departmentId"
          value={values.departmentId}
          onChange={(e) => set("departmentId", e.target.value)}
          error={errors.departmentId}
          options={[
            { value: "", label: t("staff.field.department.none") },
            ...(departments.data ?? []).map((d) => ({ value: d.id, label: d.name })),
          ]}
        />
        <SelectField
          label={t("staff.field.employmentType")}
          name="employmentType"
          value={values.employmentType}
          onChange={(e) => set("employmentType", e.target.value as EmploymentType)}
          error={errors.employmentType}
          options={EMPLOYMENT_TYPES.map((type) => ({
            value: type,
            label: translateOr(t, `staff.employment.${type}`, type),
          }))}
        />
        <TextField
          label={t("staff.field.dateOfJoining")}
          name="dateOfJoining"
          type="date"
          value={values.dateOfJoining}
          onChange={(e) => set("dateOfJoining", e.target.value)}
          error={errors.dateOfJoining}
          required
        />
        <TextField
          label={t("staff.field.mobile")}
          name="mobile"
          type="tel"
          inputMode="tel"
          autoComplete="off"
          value={values.mobile}
          onChange={(e) => set("mobile", e.target.value)}
          error={errors.mobile}
          maxLength={20}
          required
        />
      </div>
      <TextAreaField
        label={t("staff.field.qualifications")}
        name="qualifications"
        value={values.qualifications}
        onChange={(e) => set("qualifications", e.target.value)}
        error={errors.qualifications}
        hint={t("staff.field.optional")}
        maxLength={500}
        rows={2}
      />
      <fieldset className="fieldset">
        <legend>{t("staff.field.emergency")}</legend>
        <div className="grid2">
          <TextField
            label={t("staff.field.emergencyName")}
            name="emergencyContactName"
            value={values.emergencyContactName}
            onChange={(e) => set("emergencyContactName", e.target.value)}
            error={errors.emergencyContactName}
            maxLength={200}
            autoComplete="off"
          />
          <TextField
            label={t("staff.field.emergencyMobile")}
            name="emergencyContactMobile"
            type="tel"
            inputMode="tel"
            autoComplete="off"
            value={values.emergencyContactMobile}
            onChange={(e) => set("emergencyContactMobile", e.target.value)}
            error={errors.emergencyContactMobile}
            maxLength={20}
          />
        </div>
      </fieldset>
      <p className="field-hint">{t("staff.field.privacy")}</p>
    </>
  );
}

function Actions({ submitting, onCancel, label }: { submitting: boolean; onCancel: () => void; label: string }) {
  const { t } = useI18n();
  return (
    <div className="flex justify-end gap-2 pt-1">
      <button type="button" className="btn" onClick={onCancel}>
        {t("common.cancel")}
      </button>
      <button type="submit" className="btn btn-primary" disabled={submitting}>
        {submitting ? t("common.saving") : label}
      </button>
    </div>
  );
}

/** "Add staff": the sign-in and the staff profile, saved together. */
export function AddStaffDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (staff: StaffDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const form = useForm<StaffValues>(EMPTY_STAFF);
  const roles = useApiData(open ? "staff:roles" : null, staffApi.roles);
  const { values, set, errors } = form;

  const close = () => {
    form.reset(EMPTY_STAFF);
    onClose();
  };

  const toggleRole = (code: string, checked: boolean) => {
    set("roles", checked ? [...values.roles, code] : values.roles.filter((r) => r !== code));
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateStaff(values, true, today), t, event.currentTarget)) return;
    let created: StaffDetail | undefined;
    const ok = await form.submit(t, async () => {
      created = await staffApi.create({
        ...profileRequest(values),
        name: values.name.trim(),
        email: values.email.trim(),
        password: values.password,
        roles: values.roles,
      });
    });
    if (ok && created) {
      form.reset(EMPTY_STAFF);
      onCreated(created);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("staff.add.title")}
      description={t("staff.add.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <fieldset className="fieldset">
          <legend>{t("staff.add.signIn")}</legend>
          <div className="grid2">
            <TextField
              label={t("users.field.name")}
              name="name"
              autoComplete="off"
              value={values.name}
              onChange={(e) => set("name", e.target.value)}
              error={errors.name}
              maxLength={200}
              required
              data-autofocus
            />
            <TextField
              label={t("users.field.email")}
              name="email"
              type="email"
              autoComplete="off"
              value={values.email}
              onChange={(e) => set("email", e.target.value)}
              error={errors.email}
              maxLength={254}
              required
            />
          </div>
          <TextField
            label={t("users.field.password")}
            name="password"
            type="text"
            autoComplete="new-password"
            spellCheck={false}
            className="[&_input]:font-mono"
            value={values.password}
            onChange={(e) => set("password", e.target.value)}
            hint={t("users.field.password.hint")}
            error={errors.password}
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={200}
            required
            labelAction={
              <button type="button" className="linkbtn text-[13px]" onClick={() => set("password", generatePassword())}>
                {t("users.generate")}
              </button>
            }
          />
          <div className="field">
            <span className="field-label" id="staff-roles-label">
              {t("users.field.roles")}
            </span>
            {roles.error && !roles.data ? (
              <p className="field-hint">{t("users.rolesUnavailable")}</p>
            ) : !roles.data ? (
              <LoadingRows rows={1} />
            ) : (
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2" role="group" aria-labelledby="staff-roles-label">
                {roles.data.map((role) => (
                  <label key={role.code} className="check">
                    <input
                      type="checkbox"
                      name="roles"
                      value={role.code}
                      checked={values.roles.includes(role.code)}
                      onChange={(e) => toggleRole(role.code, e.target.checked)}
                    />
                    <span>{roleLabel(t, role.code, role.name)}</span>
                  </label>
                ))}
              </div>
            )}
            {errors.roles ? <p className="field-error">{errors.roles}</p> : null}
          </div>
        </fieldset>
        <ProfileInputs form={form} open={open} />
        <Actions submitting={form.submitting} onCancel={close} label={t("staff.add.submit")} />
      </form>
    </Dialog>
  );
}

/** Completes a missing profile or edits one. */
export function ProfileDialog({
  open,
  staff,
  onClose,
  onSaved,
}: {
  open: boolean;
  staff: StaffDetail;
  onClose: () => void;
  onSaved: (staff: StaffDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const initial = valuesOf(staff);
  const form = useForm<StaffValues>(initial);

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateStaff(form.values, false, today), t, event.currentTarget)) return;
    let saved: StaffDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await staffApi.saveProfile(staff.userId, profileRequest(form.values));
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={staff.profile ? t("staff.profile.editTitle", { name: staff.name }) : t("staff.profile.completeTitle", { name: staff.name })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <ProfileInputs form={form} open={open} />
        <Actions submitting={form.submitting} onCancel={close} label={t("common.save")} />
      </form>
    </Dialog>
  );
}

/** Pure and exported for tests. */
export function validateLeaving(values: StaffLeavingRequest, joinedOn: string | null, today: string): Problems {
  const problems: Problems = {};
  if (!isPlainDate(values.leftOn)) problems.leftOn = "validation.required";
  else if (values.leftOn > today) problems.leftOn = "staff.v.leftFuture";
  else if (joinedOn && values.leftOn < joinedOn) problems.leftOn = "staff.v.leftBeforeJoining";
  if (!values.reason.trim()) problems.reason = "validation.required";
  else if (values.reason.trim().length > 500) problems.reason = "validation.tooLong";
  return problems;
}

/** Records that someone left: their sign-in is disabled; nothing is deleted. */
export function LeavingDialog({
  open,
  staff,
  onClose,
  onSaved,
}: {
  open: boolean;
  staff: StaffDetail;
  onClose: () => void;
  onSaved: (staff: StaffDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const initial: StaffLeavingRequest = { leftOn: today, reason: "" };
  const form = useForm<StaffLeavingRequest>(initial);

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const joined = staff.profile?.dateOfJoining ?? null;
    if (!form.check(validateLeaving(form.values, joined, today), t, event.currentTarget)) return;
    let saved: StaffDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await staffApi.recordLeaving(staff.userId, { leftOn: form.values.leftOn, reason: form.values.reason.trim() });
    });
    if (ok && saved) onSaved(saved);
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("staff.leaving.title", { name: staff.name })}
      description={t("staff.leaving.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextField
          label={t("staff.leaving.date")}
          name="leftOn"
          type="date"
          max={today}
          value={form.values.leftOn}
          onChange={(e) => form.set("leftOn", e.target.value)}
          error={form.errors.leftOn}
          required
        />
        <TextAreaField
          label={t("staff.leaving.reason")}
          name="reason"
          value={form.values.reason}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          maxLength={500}
          rows={2}
          required
        />
        <Actions submitting={form.submitting} onCancel={close} label={t("staff.leaving.submit")} />
      </form>
    </Dialog>
  );
}

