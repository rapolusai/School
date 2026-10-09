"use client";

import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import type { FieldErrors, Problems } from "@/lib/use-form";
import { translateOr, useI18n } from "@/lib/i18n";
import {
  BLOOD_GROUPS,
  GENDERS,
  GUARDIAN_RELATIONS,
  type ClassView,
  type Gender,
  type GuardianFields,
  type GuardianRelation,
  type StudentDetail,
} from "@/lib/types";
import {
  APAAR_PATTERN,
  isPlainDate,
  isValidAdmissionNo,
  isValidEmail,
  isValidIndianMobile,
  isWholeNumberInRange,
} from "@/lib/validation";

/* ------------------------------------------------------------------ values */

export type ProfileValues = {
  admissionNo: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: "" | Gender;
  admissionDate: string;
  bloodGroup: string;
  address: string;
  previousSchool: string;
  apaarId: string;
};

export type PlacementValues = { sectionId: string; rollNo: string };

export type GuardianValues = {
  name: string;
  relation: "" | GuardianRelation;
  phone: string;
  email: string;
  occupation: string;
};

export const EMPTY_GUARDIAN: GuardianValues = { name: "", relation: "", phone: "", email: "", occupation: "" };

export function emptyProfile(today: string): ProfileValues {
  return {
    admissionNo: "",
    firstName: "",
    lastName: "",
    dateOfBirth: "",
    gender: "",
    admissionDate: today,
    bloodGroup: "",
    address: "",
    previousSchool: "",
    apaarId: "",
  };
}

export function profileOf(student: StudentDetail): ProfileValues {
  return {
    admissionNo: student.admissionNo,
    firstName: student.firstName,
    lastName: student.lastName ?? "",
    dateOfBirth: student.dateOfBirth,
    gender: student.gender,
    admissionDate: student.admissionDate,
    bloodGroup: student.bloodGroup ?? "",
    address: student.address ?? "",
    previousSchool: student.previousSchool ?? "",
    apaarId: student.apaarId ?? "",
  };
}

/* ------------------------------------------------------------------ checks (mirror the API) */

export function validateProfileValues(values: ProfileValues, today: string): Problems {
  const problems: Problems = {};
  if (!values.admissionNo.trim()) problems.admissionNo = "validation.required";
  else if (!isValidAdmissionNo(values.admissionNo)) problems.admissionNo = "students.v.admissionNo";
  if (!values.firstName.trim()) problems.firstName = "validation.required";
  else if (values.firstName.trim().length > 100) problems.firstName = "validation.tooLong";
  if (values.lastName.trim().length > 100) problems.lastName = "validation.tooLong";
  if (!isPlainDate(values.dateOfBirth)) problems.dateOfBirth = "validation.date";
  else if (values.dateOfBirth >= today) problems.dateOfBirth = "students.v.dobPast";
  if (!values.gender) problems.gender = "validation.choose";
  if (!isPlainDate(values.admissionDate)) problems.admissionDate = "validation.date";
  else if (!problems.dateOfBirth && values.dateOfBirth >= values.admissionDate) {
    problems.dateOfBirth = "students.v.dobBeforeAdmission";
  }
  if (values.bloodGroup && !(BLOOD_GROUPS as readonly string[]).includes(values.bloodGroup)) {
    problems.bloodGroup = "validation.choose";
  }
  if (values.apaarId.trim() && !APAAR_PATTERN.test(values.apaarId.trim())) problems.apaarId = "students.v.apaar";
  if (values.address.trim().length > 500) problems.address = "validation.tooLong";
  if (values.previousSchool.trim().length > 200) problems.previousSchool = "validation.tooLong";
  return problems;
}

export function validatePlacement(values: PlacementValues, required: boolean): Problems {
  const problems: Problems = {};
  if (required && !values.sectionId) problems.sectionId = "students.v.section";
  if (values.rollNo.trim() && !isWholeNumberInRange(values.rollNo, 1, 999)) problems.rollNo = "students.v.rollNo";
  return problems;
}

/** Checks one guardian; `prefix` is "guardians[0]." in the admission form, "" elsewhere. */
export function validateGuardianValues(values: GuardianValues, prefix = ""): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems[`${prefix}name`] = "validation.required";
  else if (values.name.trim().length > 200) problems[`${prefix}name`] = "validation.tooLong";
  if (!values.relation) problems[`${prefix}relation`] = "validation.choose";
  if (!values.phone.trim()) problems[`${prefix}phone`] = "validation.required";
  else if (!isValidIndianMobile(values.phone)) problems[`${prefix}phone`] = "students.v.phone";
  if (values.email.trim() && !isValidEmail(values.email)) problems[`${prefix}email`] = "validation.email";
  if (values.occupation.trim().length > 100) problems[`${prefix}occupation`] = "validation.tooLong";
  return problems;
}

/* ------------------------------------------------------------------ request bodies */

const orNull = (value: string) => value.trim() || null;

export function profileBody(values: ProfileValues) {
  return {
    admissionNo: values.admissionNo.trim(),
    firstName: values.firstName.trim(),
    lastName: orNull(values.lastName),
    dateOfBirth: values.dateOfBirth,
    gender: values.gender as Gender,
    admissionDate: values.admissionDate,
    bloodGroup: values.bloodGroup || null,
    address: orNull(values.address),
    previousSchool: orNull(values.previousSchool),
    apaarId: orNull(values.apaarId),
  };
}

export function guardianBody(values: GuardianValues, primary: boolean): GuardianFields {
  return {
    name: values.name.trim(),
    relation: values.relation as GuardianRelation,
    phone: values.phone.trim(),
    email: orNull(values.email),
    occupation: orNull(values.occupation),
    primary,
  };
}

/** "Class 5 A · 32 of 40" options for a section picker, in class order. */
export function sectionOptions(
  classes: ClassView[],
  seatsLabel: (used: number, capacity: number) => string,
): { value: string; label: string }[] {
  return classes.flatMap((c) =>
    c.sections.map((s) => ({
      value: s.id,
      label: s.capacity === null ? `${c.name} ${s.name}` : `${c.name} ${s.name} · ${seatsLabel(s.studentCount, s.capacity)}`,
    })),
  );
}

/* ------------------------------------------------------------------ fields */

export function ProfileFields({
  values,
  errors,
  set,
  today,
  autoFocus = false,
}: {
  values: ProfileValues;
  errors: FieldErrors;
  set: <K extends keyof ProfileValues>(field: K, value: ProfileValues[K]) => void;
  today: string;
  autoFocus?: boolean;
}) {
  const { t } = useI18n();
  return (
    <>
      <div className="grid2">
        <TextField
          label={t("students.field.firstName")}
          name="firstName"
          autoComplete="off"
          maxLength={100}
          value={values.firstName}
          onChange={(e) => set("firstName", e.target.value)}
          error={errors.firstName}
          required
          data-autofocus={autoFocus || undefined}
        />
        <TextField
          label={t("students.field.lastName")}
          name="lastName"
          autoComplete="off"
          maxLength={100}
          hint={t("students.field.lastName.hint")}
          value={values.lastName}
          onChange={(e) => set("lastName", e.target.value)}
          error={errors.lastName}
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
          onChange={(e) => set("admissionNo", e.target.value)}
          error={errors.admissionNo}
          required
        />
        <TextField
          label={t("students.field.admissionDate")}
          name="admissionDate"
          type="date"
          value={values.admissionDate}
          onChange={(e) => set("admissionDate", e.target.value)}
          error={errors.admissionDate}
          required
        />
      </div>
      <div className="grid2">
        <TextField
          label={t("students.field.dateOfBirth")}
          name="dateOfBirth"
          type="date"
          max={today}
          value={values.dateOfBirth}
          onChange={(e) => set("dateOfBirth", e.target.value)}
          error={errors.dateOfBirth}
          required
        />
        <SelectField
          label={t("students.field.gender")}
          name="gender"
          value={values.gender}
          onChange={(e) => set("gender", e.target.value as ProfileValues["gender"])}
          error={errors.gender}
          required
          options={[
            { value: "", label: t("common.choose") },
            ...GENDERS.map((g) => ({ value: g, label: translateOr(t, `gender.${g}`, g) })),
          ]}
        />
      </div>
      <div className="grid2">
        <SelectField
          label={t("students.field.bloodGroup")}
          name="bloodGroup"
          value={values.bloodGroup}
          onChange={(e) => set("bloodGroup", e.target.value)}
          error={errors.bloodGroup}
          options={[{ value: "", label: t("common.notRecorded") }, ...BLOOD_GROUPS.map((b) => ({ value: b, label: b }))]}
        />
        <TextField
          label={t("students.field.apaarId")}
          name="apaarId"
          inputMode="numeric"
          autoComplete="off"
          maxLength={12}
          className="[&_input]:font-mono"
          hint={t("students.field.apaarId.hint")}
          value={values.apaarId}
          onChange={(e) => set("apaarId", e.target.value)}
          error={errors.apaarId}
        />
      </div>
      <TextAreaField
        label={t("students.field.address")}
        name="address"
        rows={2}
        maxLength={500}
        value={values.address}
        onChange={(e) => set("address", e.target.value)}
        error={errors.address}
      />
      <TextField
        label={t("students.field.previousSchool")}
        name="previousSchool"
        maxLength={200}
        value={values.previousSchool}
        onChange={(e) => set("previousSchool", e.target.value)}
        error={errors.previousSchool}
      />
    </>
  );
}

export function GuardianInputs({
  values,
  errors,
  onChange,
  prefix = "",
  autoFocus = false,
}: {
  values: GuardianValues;
  errors: FieldErrors;
  onChange: (next: GuardianValues, field: string) => void;
  prefix?: string;
  autoFocus?: boolean;
}) {
  const { t } = useI18n();
  const set = (field: keyof GuardianValues, value: string) =>
    onChange({ ...values, [field]: value }, `${prefix}${field}`);
  return (
    <>
      <div className="grid2">
        <TextField
          label={t("students.guardian.name")}
          name={`${prefix}name`}
          autoComplete="off"
          maxLength={200}
          value={values.name}
          onChange={(e) => set("name", e.target.value)}
          error={errors[`${prefix}name`]}
          required
          data-autofocus={autoFocus || undefined}
        />
        <SelectField
          label={t("students.guardian.relation")}
          name={`${prefix}relation`}
          value={values.relation}
          onChange={(e) => set("relation", e.target.value)}
          error={errors[`${prefix}relation`]}
          required
          options={[
            { value: "", label: t("common.choose") },
            ...GUARDIAN_RELATIONS.map((r) => ({ value: r, label: translateOr(t, `relation.${r}`, r) })),
          ]}
        />
      </div>
      <div className="grid2">
        <TextField
          label={t("students.guardian.phone")}
          name={`${prefix}phone`}
          type="tel"
          inputMode="tel"
          autoComplete="off"
          maxLength={20}
          placeholder="98765 43210"
          hint={t("students.guardian.phone.hint")}
          value={values.phone}
          onChange={(e) => set("phone", e.target.value)}
          error={errors[`${prefix}phone`]}
          required
        />
        <TextField
          label={t("students.guardian.email")}
          name={`${prefix}email`}
          type="email"
          autoComplete="off"
          maxLength={254}
          value={values.email}
          onChange={(e) => set("email", e.target.value)}
          error={errors[`${prefix}email`]}
        />
      </div>
      <TextField
        label={t("students.guardian.occupation")}
        name={`${prefix}occupation`}
        autoComplete="off"
        maxLength={100}
        value={values.occupation}
        onChange={(e) => set("occupation", e.target.value)}
        error={errors[`${prefix}occupation`]}
      />
    </>
  );
}
