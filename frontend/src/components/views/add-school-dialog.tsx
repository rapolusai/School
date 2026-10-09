"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { api, toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { BOARDS, PLANS, type Board, type CreateTenantRequest, type Plan, type TenantSummary } from "@/lib/types";
import {
  isValidEmail,
  isValidSchoolCode,
  MIN_PASSWORD_LENGTH,
  suggestSchoolCode,
} from "@/lib/validation";
import { generatePassword } from "./add-user-dialog";

type Values = CreateTenantRequest;
type Field = keyof Values;

const EMPTY: Values = {
  schoolName: "",
  schoolCode: "",
  board: "CBSE",
  city: "",
  plan: "STARTER",
  adminName: "",
  adminEmail: "",
  password: "",
};

export function validateNewTenant(values: Values): Partial<Record<Field, MessageKey>> {
  const errors: Partial<Record<Field, MessageKey>> = {};
  if (!values.schoolName.trim()) errors.schoolName = "validation.required";
  if (!isValidSchoolCode(values.schoolCode)) errors.schoolCode = "validation.schoolCode";
  if (!values.city.trim()) errors.city = "validation.required";
  if (!values.adminName.trim()) errors.adminName = "validation.required";
  if (!isValidEmail(values.adminEmail)) errors.adminEmail = "validation.email";
  if (values.password.length < MIN_PASSWORD_LENGTH) errors.password = "validation.password";
  return errors;
}

export function AddSchoolDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (tenant: TenantSummary) => void;
}) {
  const { t } = useI18n();
  const [values, setValues] = useState<Values>(EMPTY);
  const [codeEdited, setCodeEdited] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const reset = () => {
    setValues(EMPTY);
    setCodeEdited(false);
    setErrors({});
    setFormError(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  const set = <K extends Field>(field: K, value: Values[K]) => {
    setValues((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    const found = validateNewTenant(values);
    if (Object.keys(found).length) {
      setErrors(Object.fromEntries(Object.entries(found).map(([k, v]) => [k, t(v)])));
      event.currentTarget
        .querySelector<HTMLElement>(`[name="${Object.keys(found)[0]}"]`)
        ?.focus();
      return;
    }
    setSubmitting(true);
    setFormError(null);
    try {
      const tenant = await api.createTenant({
        ...values,
        schoolName: values.schoolName.trim(),
        city: values.city.trim(),
        adminName: values.adminName.trim(),
        adminEmail: values.adminEmail.trim(),
      });
      reset();
      onCreated(tenant);
    } catch (caught) {
      const error = toApiError(caught);
      if (error.errors) {
        setErrors(error.errors as Partial<Record<Field, string>>);
        setFormError(t("validation.fixErrors"));
      } else if (error.status === 409) {
        setErrors({ schoolCode: error.detail ?? error.title });
      } else {
        setFormError(errorMessage(error, t));
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("schools.dialog.title")}
      description={t("schools.dialog.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={formError} />
        <TextField
          label={t("signup.schoolName")}
          name="schoolName"
          autoComplete="off"
          value={values.schoolName}
          onChange={(e) => {
            const name = e.target.value;
            setValues((prev) => ({
              ...prev,
              schoolName: name,
              schoolCode: codeEdited ? prev.schoolCode : suggestSchoolCode(name),
            }));
            setErrors((prev) => ({ ...prev, schoolName: undefined, schoolCode: undefined }));
          }}
          error={errors.schoolName}
          required
          data-autofocus
        />
        <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2">
          <TextField
            label={t("signup.schoolCode")}
            name="schoolCode"
            autoCapitalize="none"
            spellCheck={false}
            className="[&_input]:font-mono"
            value={values.schoolCode}
            onChange={(e) => {
              const code = e.target.value.toLowerCase().replace(/\s+/g, "-");
              setCodeEdited(code !== "");
              set("schoolCode", code);
            }}
            error={errors.schoolCode}
            maxLength={40}
            required
          />
          <TextField
            label={t("signup.city")}
            name="city"
            autoComplete="off"
            value={values.city}
            onChange={(e) => set("city", e.target.value)}
            error={errors.city}
            required
          />
          <SelectField
            label={t("signup.board")}
            name="board"
            value={values.board}
            onChange={(e) => set("board", e.target.value as Board)}
            options={BOARDS.map((b) => ({ value: b, label: t(`board.${b}` as MessageKey) }))}
            error={errors.board}
          />
          <SelectField
            label={t("schools.field.plan")}
            name="plan"
            value={values.plan}
            onChange={(e) => set("plan", e.target.value as Plan)}
            options={PLANS.map((p) => ({ value: p, label: t(`plan.${p}` as MessageKey) }))}
            error={errors.plan}
          />
          <TextField
            label={t("schools.field.adminName")}
            name="adminName"
            autoComplete="off"
            value={values.adminName}
            onChange={(e) => set("adminName", e.target.value)}
            error={errors.adminName}
            required
          />
          <TextField
            label={t("schools.field.adminEmail")}
            name="adminEmail"
            type="email"
            autoComplete="off"
            value={values.adminEmail}
            onChange={(e) => set("adminEmail", e.target.value)}
            error={errors.adminEmail}
            required
          />
        </div>
        <TextField
          label={t("schools.field.password")}
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
          required
          labelAction={
            <button
              type="button"
              className="linkbtn text-[13px]"
              onClick={() => set("password", generatePassword())}
            >
              {t("users.generate")}
            </button>
          }
        />
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? t("schools.submitting") : t("schools.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
