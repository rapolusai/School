"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { api, toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { roleLabel, useI18n, type MessageKey } from "@/lib/i18n";
import type { Role, UserSummary } from "@/lib/types";
import { isValidEmail, MIN_PASSWORD_LENGTH } from "@/lib/validation";

type Values = { name: string; email: string; password: string; roles: string[] };
type Field = keyof Values;

const EMPTY: Values = { name: "", email: "", password: "", roles: [] };

export function validateNewUser(values: Values): Partial<Record<Field, MessageKey>> {
  const errors: Partial<Record<Field, MessageKey>> = {};
  if (!values.name.trim()) errors.name = "validation.required";
  if (!isValidEmail(values.email)) errors.email = "validation.email";
  if (values.password.length < MIN_PASSWORD_LENGTH) errors.password = "validation.password";
  if (values.roles.length === 0) errors.roles = "validation.roles";
  return errors;
}

/** A readable random password (no look-alike characters). */
export function generatePassword(length = 14): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  const bytes = new Uint32Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join("");
}

export function AddUserDialog({
  open,
  onClose,
  roles,
  rolesLoading,
  rolesError,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  roles: Role[] | undefined;
  rolesLoading: boolean;
  rolesError: boolean;
  onCreated: (user: UserSummary) => void;
}) {
  const { t } = useI18n();
  const [values, setValues] = useState<Values>(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const close = () => {
    setValues(EMPTY);
    setErrors({});
    setFormError(null);
    onClose();
  };

  const set = <K extends Field>(field: K, value: Values[K]) => {
    setValues((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const toggleRole = (code: string, checked: boolean) => {
    set("roles", checked ? [...values.roles, code] : values.roles.filter((r) => r !== code));
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    const found = validateNewUser(values);
    if (Object.keys(found).length) {
      setErrors(Object.fromEntries(Object.entries(found).map(([k, v]) => [k, t(v)])));
      const first = Object.keys(found)[0];
      event.currentTarget.querySelector<HTMLElement>(`[name="${first}"]`)?.focus();
      return;
    }
    setSubmitting(true);
    setFormError(null);
    try {
      const user = await api.createUser({
        name: values.name.trim(),
        email: values.email.trim(),
        password: values.password,
        roles: values.roles,
      });
      setValues(EMPTY);
      setErrors({});
      onCreated(user);
    } catch (caught) {
      const error = toApiError(caught);
      if (error.errors) {
        setErrors(error.errors as Partial<Record<Field, string>>);
        setFormError(t("validation.fixErrors"));
      } else if (error.status === 409) {
        // Duplicate email within this school.
        setErrors({ email: error.detail ?? error.title });
      } else {
        setFormError(errorMessage(error, t));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const rolesUnavailable = rolesError || (!rolesLoading && !roles?.length);

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("users.dialog.title")}
      description={t("users.dialog.description")}
      closeLabel={t("common.close")}
    >
      <form className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={formError} />
        <TextField
          label={t("users.field.name")}
          name="name"
          autoComplete="off"
          value={values.name}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
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
          required
        />
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
        <fieldset
          className="field m-0 border-0 p-0"
          aria-describedby={errors.roles ? "new-user-roles-error" : undefined}
        >
          <legend className="field-label mb-1.5">{t("users.field.roles")}</legend>
          {rolesLoading && !roles ? (
            <LoadingRows rows={2} />
          ) : rolesUnavailable ? (
            <p className="field-hint">{t("users.rolesUnavailable")}</p>
          ) : (
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {roles?.map((role) => (
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
          {errors.roles ? (
            <p id="new-user-roles-error" className="field-error">
              {errors.roles}
            </p>
          ) : null}
        </fieldset>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={submitting || rolesUnavailable}
          >
            {submitting ? t("users.submitting") : t("users.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
