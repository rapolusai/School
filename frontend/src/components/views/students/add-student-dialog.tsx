"use client";

import { Plus, X } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { api } from "@/lib/api";
import { todayInIndia } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import type { StudentDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import {
  EMPTY_GUARDIAN,
  emptyProfile,
  GuardianInputs,
  guardianBody,
  ProfileFields,
  profileBody,
  sectionOptions,
  validateGuardianValues,
  validatePlacement,
  validateProfileValues,
  type GuardianValues,
  type ProfileValues,
} from "./student-form";

export const MAX_GUARDIANS = 4;

type Values = {
  profile: ProfileValues;
  sectionId: string;
  rollNo: string;
  guardians: GuardianValues[];
  /** Index of the primary contact. */
  primary: number;
};

export function validateAdmission(values: Values, today: string): Problems {
  return {
    ...validateProfileValues(values.profile, today),
    ...validatePlacement({ sectionId: values.sectionId, rollNo: values.rollNo }, true),
    ...Object.assign({}, ...values.guardians.map((g, i) => validateGuardianValues(g, `guardians[${i}].`))),
  };
}

function initialValues(today: string): Values {
  return { profile: emptyProfile(today), sectionId: "", rollNo: "", guardians: [{ ...EMPTY_GUARDIAN }], primary: 0 };
}

/** Admits a student into a section of the current academic year, with at least one parent or guardian. */
export function AddStudentDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (student: StudentDetail) => void;
}) {
  const { t } = useI18n();
  const [today] = useState(() => todayInIndia());
  const form = useForm<Values>(initialValues(today));
  const { values, errors } = form;
  const classes = useApiData(open ? "academics:classes" : null, api.listClasses);
  const years = useApiData(open ? "academics:years" : null, api.listYears);
  const currentYear = years.data?.find((y) => y.current);
  const options = sectionOptions(classes.data ?? [], (used, capacity) =>
    t("setup.sections.seats", { used, capacity }),
  );

  const clearError = (key: string) =>
    form.setErrors((prev) => (prev[key] ? { ...prev, [key]: undefined } : prev));

  const setProfile = <K extends keyof ProfileValues>(field: K, value: ProfileValues[K]) => {
    form.setValues((prev) => ({ ...prev, profile: { ...prev.profile, [field]: value } }));
    clearError(field);
  };

  const setGuardian = (index: number, next: GuardianValues, field: string) => {
    form.setValues((prev) => ({ ...prev, guardians: prev.guardians.map((g, i) => (i === index ? next : g)) }));
    clearError(field);
  };

  const addGuardian = () =>
    form.setValues((prev) => ({ ...prev, guardians: [...prev.guardians, { ...EMPTY_GUARDIAN }] }));

  const removeGuardian = (index: number) => {
    form.setValues((prev) => ({
      ...prev,
      guardians: prev.guardians.filter((_, i) => i !== index),
      primary: prev.primary === index ? 0 : prev.primary > index ? prev.primary - 1 : prev.primary,
    }));
    form.setErrors({});
  };

  const close = () => {
    form.reset(initialValues(today));
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateAdmission(values, today), t, event.currentTarget)) return;
    let created: StudentDetail | undefined;
    const ok = await form.submit(t, async () => {
      created = await api.createStudent({
        ...profileBody(values.profile),
        sectionId: values.sectionId,
        rollNo: values.rollNo.trim() ? Number(values.rollNo) : null,
        guardians: values.guardians.map((g, i) => guardianBody(g, i === values.primary)),
      });
    });
    if (ok && created) {
      form.reset(initialValues(today));
      onCreated(created);
    }
  };

  const loading = (classes.loading && !classes.data) || (years.loading && !years.data);
  const noYear = years.data !== undefined && !currentYear;
  const noSections = classes.data !== undefined && options.length === 0;

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("students.dialog.title")}
      description={
        currentYear ? t("students.dialog.description", { year: currentYear.name }) : t("students.dialog.descriptionNoYear")
      }
      closeLabel={t("common.close")}
    >
      {loading ? (
        <LoadingRows rows={4} />
      ) : noYear || noSections ? (
        <div className="flex flex-col items-start gap-3">
          <p className="alert alert-info w-full">{noYear ? t("students.noYear") : t("students.noSections")}</p>
          <button type="button" className="btn" onClick={close}>
            {t("common.close")}
          </button>
        </div>
      ) : (
        <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
          <FormAlert message={form.formError} />
          <fieldset className="fieldset">
            <legend>{t("students.dialog.student")}</legend>
            <ProfileFields values={values.profile} errors={errors} set={setProfile} today={today} autoFocus />
          </fieldset>

          <fieldset className="fieldset">
            <legend>{t("students.dialog.class")}</legend>
            <div className="grid2">
              <SelectField
                label={t("students.field.section")}
                name="sectionId"
                value={values.sectionId}
                onChange={(e) => {
                  form.setValues((prev) => ({ ...prev, sectionId: e.target.value }));
                  clearError("sectionId");
                }}
                error={errors.sectionId}
                required
                options={[{ value: "", label: t("common.choose") }, ...options]}
              />
              <TextField
                label={t("students.field.rollNo")}
                name="rollNo"
                inputMode="numeric"
                maxLength={3}
                hint={t("students.field.rollNo.hint")}
                value={values.rollNo}
                onChange={(e) => {
                  form.setValues((prev) => ({ ...prev, rollNo: e.target.value }));
                  clearError("rollNo");
                }}
                error={errors.rollNo}
              />
            </div>
          </fieldset>

          {values.guardians.map((guardian, index) => (
            <fieldset key={index} className="fieldset">
              <legend>{t("students.dialog.guardianN", { n: index + 1 })}</legend>
              <GuardianInputs
                values={guardian}
                errors={errors}
                prefix={`guardians[${index}].`}
                onChange={(next, field) => setGuardian(index, next, field)}
              />
              <div className="flex flex-wrap items-center justify-between gap-2">
                {values.guardians.length > 1 ? (
                  <label className="check">
                    <input
                      type="radio"
                      name="primary"
                      checked={values.primary === index}
                      onChange={() => form.setValues((prev) => ({ ...prev, primary: index }))}
                    />
                    <span>{t("students.guardian.primary")}</span>
                  </label>
                ) : (
                  <span className="field-hint">{t("students.guardian.primaryOnly")}</span>
                )}
                {values.guardians.length > 1 ? (
                  <button type="button" className="btn btn-sm btn-ghost" onClick={() => removeGuardian(index)}>
                    <X size={16} aria-hidden="true" />
                    {t("students.guardian.removeFromForm")}
                  </button>
                ) : null}
              </div>
            </fieldset>
          ))}
          {errors.guardians ? <p className="field-error">{errors.guardians}</p> : null}
          {values.guardians.length < MAX_GUARDIANS ? (
            <button type="button" className="btn self-start" onClick={addGuardian}>
              <Plus size={16} aria-hidden="true" />
              {t("students.guardian.addAnother")}
            </button>
          ) : null}

          <div className="flex justify-end gap-2 pt-1">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={form.submitting}>
              {form.submitting ? t("students.dialog.submitting") : t("students.dialog.submit")}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
