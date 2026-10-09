"use client";

import { Plus, X } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { admissionsApi } from "@/lib/admissions-api";
import { api } from "@/lib/api";
import { todayInIndia } from "@/lib/format";
import { translateOr, useI18n } from "@/lib/i18n";
import {
  APPLICATION_SOURCES,
  GENDERS,
  GUARDIAN_RELATIONS,
  type ApplicationDetail,
  type ApplicationRequest,
  type ApplicationSource,
  type Gender,
  type GuardianRelation,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate, isValidEmail, isValidIndianMobile } from "@/lib/validation";
import { sourceLabel } from "./admission-labels";
import { yearsBefore } from "./admission-time";

export const MAX_CONTACTS = 4;

export type ContactValues = { name: string; relation: "" | GuardianRelation; phone: string; email: string };

export type ApplicationValues = {
  stage: "ENQUIRY" | "APPLICATION";
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: "" | Gender;
  previousSchool: string;
  classId: string;
  academicYearId: string;
  source: "" | ApplicationSource;
  assignedToId: string;
  followUpOn: string;
  note: string;
  guardians: ContactValues[];
  /** Index of the primary contact. */
  primary: number;
};

const EMPTY_CONTACT: ContactValues = { name: "", relation: "", phone: "", email: "" };

export function emptyApplication(): ApplicationValues {
  return {
    stage: "ENQUIRY",
    firstName: "",
    lastName: "",
    dateOfBirth: "",
    gender: "",
    previousSchool: "",
    classId: "",
    academicYearId: "",
    source: "",
    assignedToId: "",
    followUpOn: "",
    note: "",
    guardians: [{ ...EMPTY_CONTACT }],
    primary: 0,
  };
}

export function applicationValuesOf(a: ApplicationDetail): ApplicationValues {
  const primary = a.guardians.findIndex((g) => g.primary);
  return {
    stage: "ENQUIRY",
    firstName: a.firstName,
    lastName: a.lastName ?? "",
    dateOfBirth: a.dateOfBirth,
    gender: a.gender ?? "",
    previousSchool: a.previousSchool ?? "",
    classId: a.classId,
    academicYearId: a.academicYearId,
    source: a.source,
    assignedToId: a.assignedTo?.id ?? "",
    followUpOn: a.followUpOn ?? "",
    note: "",
    guardians: a.guardians.map((g) => ({ name: g.name, relation: g.relation, phone: g.phone, email: g.email ?? "" })),
    primary: primary < 0 ? 0 : primary,
  };
}

/**
 * The same checks as the API. `existing` is the application being edited: an unchanged
 * follow-up date may already be in the past.
 */
export function validateApplication(values: ApplicationValues, today: string, existing?: ApplicationDetail): Problems {
  const problems: Problems = {};
  if (!values.firstName.trim()) problems.firstName = "validation.required";
  else if (values.firstName.trim().length > 100) problems.firstName = "validation.tooLong";
  if (values.lastName.trim().length > 100) problems.lastName = "validation.tooLong";
  if (!isPlainDate(values.dateOfBirth)) problems.dateOfBirth = "validation.date";
  else if (values.dateOfBirth >= today) problems.dateOfBirth = "students.v.dobPast";
  else if (values.dateOfBirth < yearsBefore(today, 25)) problems.dateOfBirth = "admissions.v.dob";
  if (values.previousSchool.trim().length > 200) problems.previousSchool = "validation.tooLong";
  if (!values.classId) problems.classId = "admissions.v.class";
  if (!values.academicYearId) problems.academicYearId = "admissions.v.year";
  if (!values.source) problems.source = "validation.choose";
  if (values.followUpOn) {
    if (!isPlainDate(values.followUpOn)) problems.followUpOn = "validation.date";
    else if (values.followUpOn < today && values.followUpOn !== existing?.followUpOn) {
      problems.followUpOn = "admissions.v.followUp";
    }
  }
  if (values.note.trim().length > 2000) problems.note = "validation.tooLong";
  values.guardians.forEach((g, i) => {
    const prefix = `guardians[${i}].`;
    if (!g.name.trim()) problems[`${prefix}name`] = "validation.required";
    else if (g.name.trim().length > 200) problems[`${prefix}name`] = "validation.tooLong";
    if (!g.relation) problems[`${prefix}relation`] = "validation.choose";
    if (!g.phone.trim()) problems[`${prefix}phone`] = "validation.required";
    else if (!isValidIndianMobile(g.phone)) problems[`${prefix}phone`] = "students.v.phone";
    if (g.email.trim() && !isValidEmail(g.email)) problems[`${prefix}email`] = "validation.email";
  });
  return problems;
}

const orNull = (value: string) => value.trim() || null;

export function applicationBody(values: ApplicationValues, creating: boolean): ApplicationRequest {
  return {
    ...(creating ? { stage: values.stage, note: orNull(values.note) } : {}),
    firstName: values.firstName.trim(),
    lastName: orNull(values.lastName),
    dateOfBirth: values.dateOfBirth,
    gender: values.gender || null,
    previousSchool: orNull(values.previousSchool),
    classId: values.classId,
    academicYearId: values.academicYearId,
    source: values.source as ApplicationSource,
    assignedToId: values.assignedToId || null,
    followUpOn: values.followUpOn || null,
    guardians: values.guardians.map((g, i) => ({
      name: g.name.trim(),
      relation: g.relation as GuardianRelation,
      phone: g.phone.trim(),
      email: orNull(g.email),
      primary: i === values.primary,
    })),
  };
}

/** "New enquiry" (staff entering a walk-in, call or referral) and "Edit details". */
export function ApplicationFormDialog({
  open,
  existing,
  onClose,
  onSaved,
}: {
  open: boolean;
  /** The application being edited; omit to create one. */
  existing?: ApplicationDetail;
  onClose: () => void;
  onSaved: (application: ApplicationDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const creating = !existing;
  const start = () => (existing ? applicationValuesOf(existing) : emptyApplication());
  const form = useForm<ApplicationValues>(start());
  const { values, errors } = form;
  const classes = useApiData(open ? "academics:classes" : null, api.listClasses);
  const years = useApiData(open ? "academics:years" : null, api.listYears);
  const staff = useApiData(open ? "admissions:staff" : null, admissionsApi.staff);

  // Default to the current year once the years have loaded.
  const currentYear = years.data?.find((y) => y.current);
  const yearValue = values.academicYearId || (creating ? (currentYear?.id ?? "") : "");
  const yearOptions = (years.data ?? []).filter((y) => y.endsOn >= today || y.id === existing?.academicYearId);

  const clearError = (key: string) =>
    form.setErrors((prev) => (prev[key] ? { ...prev, [key]: undefined } : prev));

  const set = <K extends keyof ApplicationValues>(field: K, value: ApplicationValues[K]) => {
    form.setValues((prev) => ({ ...prev, [field]: value }));
    clearError(field as string);
  };

  const setContact = (index: number, field: keyof ContactValues, value: string) => {
    form.setValues((prev) => ({
      ...prev,
      guardians: prev.guardians.map((g, i) => (i === index ? { ...g, [field]: value } : g)),
    }));
    clearError(`guardians[${index}].${field}`);
  };

  const removeContact = (index: number) => {
    form.setValues((prev) => ({
      ...prev,
      guardians: prev.guardians.filter((_, i) => i !== index),
      primary: prev.primary === index ? 0 : prev.primary > index ? prev.primary - 1 : prev.primary,
    }));
    form.setErrors({});
  };

  const close = () => {
    form.reset(start());
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const filled = { ...values, academicYearId: yearValue };
    if (!form.check(validateApplication(filled, today, existing), t, event.currentTarget)) return;
    let saved: ApplicationDetail | undefined;
    const ok = await form.submit(t, async () => {
      const body = applicationBody(filled, creating);
      saved = existing ? await admissionsApi.update(existing.id, body) : await admissionsApi.create(body);
    });
    if (ok && saved) {
      form.reset(existing ? applicationValuesOf(saved) : emptyApplication());
      onSaved(saved);
    }
  };

  const loading = (classes.loading && !classes.data) || (years.loading && !years.data);
  const noSetup =
    classes.data !== undefined && years.data !== undefined && (classes.data.length === 0 || yearOptions.length === 0);

  return (
    <Dialog
      open={open}
      onClose={close}
      title={creating ? t("admissions.form.newTitle") : t("admissions.form.editTitle", { name: existing.childName })}
      description={creating ? t("admissions.form.newDescription") : undefined}
      closeLabel={t("common.close")}
    >
      {loading ? (
        <LoadingRows rows={4} />
      ) : noSetup ? (
        <div className="flex flex-col items-start gap-3">
          <p className="alert alert-info w-full">{t("admissions.form.noSetup")}</p>
          <button type="button" className="btn" onClick={close}>
            {t("common.close")}
          </button>
        </div>
      ) : (
        <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
          <FormAlert message={form.formError} />
          {creating ? (
            <div className="seg self-start" role="radiogroup" aria-label={t("admissions.form.startAs")}>
              {(["ENQUIRY", "APPLICATION"] as const).map((stage) => (
                <label key={stage}>
                  <input
                    type="radio"
                    name="stage"
                    value={stage}
                    checked={values.stage === stage}
                    onChange={() => set("stage", stage)}
                  />
                  {t(stage === "ENQUIRY" ? "admissions.form.asEnquiry" : "admissions.form.asApplication")}
                </label>
              ))}
            </div>
          ) : null}

          <fieldset className="fieldset">
            <legend>{t("admissions.form.child")}</legend>
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
                data-autofocus
              />
              <TextField
                label={t("students.field.lastName")}
                name="lastName"
                autoComplete="off"
                maxLength={100}
                value={values.lastName}
                onChange={(e) => set("lastName", e.target.value)}
                error={errors.lastName}
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
                onChange={(e) => set("gender", e.target.value as ApplicationValues["gender"])}
                error={errors.gender}
                options={[
                  { value: "", label: t("common.notRecorded") },
                  ...GENDERS.map((g) => ({ value: g, label: translateOr(t, `gender.${g}`, g) })),
                ]}
              />
            </div>
            <TextField
              label={t("students.field.previousSchool")}
              name="previousSchool"
              maxLength={200}
              value={values.previousSchool}
              onChange={(e) => set("previousSchool", e.target.value)}
              error={errors.previousSchool}
            />
            <div className="grid2">
              <SelectField
                label={t("admissions.field.class")}
                name="classId"
                value={values.classId}
                onChange={(e) => set("classId", e.target.value)}
                error={errors.classId}
                required
                options={[
                  { value: "", label: t("common.choose") },
                  ...(classes.data ?? []).map((c) => ({ value: c.id, label: c.name })),
                ]}
              />
              <SelectField
                label={t("admissions.field.year")}
                name="academicYearId"
                value={yearValue}
                onChange={(e) => set("academicYearId", e.target.value)}
                error={errors.academicYearId}
                required
                options={[
                  { value: "", label: t("common.choose") },
                  ...yearOptions.map((y) => ({
                    value: y.id,
                    label: y.current ? t("students.filter.currentYear", { name: y.name }) : y.name,
                  })),
                ]}
              />
            </div>
          </fieldset>

          {values.guardians.map((contact, index) => {
            const prefix = `guardians[${index}].`;
            return (
              <fieldset key={index} className="fieldset">
                <legend>{t("admissions.form.contactN", { n: index + 1 })}</legend>
                <div className="grid2">
                  <TextField
                    label={t("students.guardian.name")}
                    name={`${prefix}name`}
                    autoComplete="off"
                    maxLength={200}
                    value={contact.name}
                    onChange={(e) => setContact(index, "name", e.target.value)}
                    error={errors[`${prefix}name`]}
                    required
                  />
                  <SelectField
                    label={t("students.guardian.relation")}
                    name={`${prefix}relation`}
                    value={contact.relation}
                    onChange={(e) => setContact(index, "relation", e.target.value)}
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
                    value={contact.phone}
                    onChange={(e) => setContact(index, "phone", e.target.value)}
                    error={errors[`${prefix}phone`]}
                    required
                  />
                  <TextField
                    label={t("students.guardian.email")}
                    name={`${prefix}email`}
                    type="email"
                    autoComplete="off"
                    maxLength={254}
                    value={contact.email}
                    onChange={(e) => setContact(index, "email", e.target.value)}
                    error={errors[`${prefix}email`]}
                  />
                </div>
                {values.guardians.length > 1 ? (
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <label className="check">
                      <input
                        type="radio"
                        name="primary"
                        checked={values.primary === index}
                        onChange={() => form.setValues((prev) => ({ ...prev, primary: index }))}
                      />
                      <span>{t("students.guardian.primary")}</span>
                    </label>
                    <button type="button" className="btn btn-sm btn-ghost" onClick={() => removeContact(index)}>
                      <X size={16} aria-hidden="true" />
                      {t("students.guardian.removeFromForm")}
                    </button>
                  </div>
                ) : null}
              </fieldset>
            );
          })}
          {errors.guardians ? <p className="field-error">{errors.guardians}</p> : null}
          {values.guardians.length < MAX_CONTACTS ? (
            <button
              type="button"
              className="btn self-start"
              onClick={() =>
                form.setValues((prev) => ({ ...prev, guardians: [...prev.guardians, { ...EMPTY_CONTACT }] }))
              }
            >
              <Plus size={16} aria-hidden="true" />
              {t("students.guardian.addAnother")}
            </button>
          ) : null}

          <fieldset className="fieldset">
            <legend>{t("admissions.form.tracking")}</legend>
            <div className="grid2">
              <SelectField
                label={t("admissions.field.source")}
                name="source"
                value={values.source}
                onChange={(e) => set("source", e.target.value as ApplicationValues["source"])}
                error={errors.source}
                required
                options={[
                  { value: "", label: t("common.choose") },
                  ...APPLICATION_SOURCES.map((s) => ({ value: s, label: sourceLabel(t, s) })),
                ]}
              />
              <SelectField
                label={t("admissions.field.counsellor")}
                name="assignedToId"
                value={values.assignedToId}
                onChange={(e) => set("assignedToId", e.target.value)}
                error={errors.assignedToId}
                options={[
                  { value: "", label: t("admissions.field.counsellor.none") },
                  ...(staff.data ?? []).map((s) => ({ value: s.id, label: s.name })),
                ]}
              />
            </div>
            <TextField
              label={t("admissions.field.followUpOn")}
              name="followUpOn"
              type="date"
              min={today}
              hint={t("admissions.field.followUpOn.hint")}
              value={values.followUpOn}
              onChange={(e) => set("followUpOn", e.target.value)}
              error={errors.followUpOn}
            />
            {creating ? (
              <TextAreaField
                label={t("admissions.field.note")}
                name="note"
                rows={2}
                maxLength={2000}
                value={values.note}
                onChange={(e) => set("note", e.target.value)}
                error={errors.note}
              />
            ) : null}
          </fieldset>

          <div className="flex justify-end gap-2 pt-1">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={form.submitting}>
              {form.submitting ? t("common.saving") : creating ? t("admissions.form.create") : t("common.save")}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
