import { useCallback, useState } from "react";
import { toApiError } from "./api";
import { errorMessage } from "./error-message";
import type { MessageKey, Translate } from "./i18n";

export type FieldErrors = Record<string, string | undefined>;

/** Client-side check results: field name → message key. */
export type Problems = Partial<Record<string, MessageKey>>;

/**
 * Form state shared by the setup and student dialogs: values, per-field errors (client checks
 * first, then the API's problem+json `errors`), a form-level alert and a submitting flag.
 */
export function useForm<V extends Record<string, unknown>>(initial: V) {
  const [values, setValues] = useState<V>(initial);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const set = useCallback(<K extends keyof V>(field: K, value: V[K]) => {
    setValues((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => (prev[field as string] ? { ...prev, [field as string]: undefined } : prev));
  }, []);

  const reset = useCallback((next: V) => {
    setValues(next);
    setErrors({});
    setFormError(null);
  }, []);

  /**
   * Shows client-side problems (translated) and focuses the first one. Returns true when the
   * form may be sent.
   */
  const check = (problems: Problems, t: Translate, form: HTMLFormElement | null): boolean => {
    const entries = Object.entries(problems).filter((e): e is [string, MessageKey] => Boolean(e[1]));
    if (entries.length === 0) return true;
    setErrors(Object.fromEntries(entries.map(([field, key]) => [field, t(key)])));
    setFormError(null);
    // Focus the first invalid field in the order the form shows them.
    const invalid = new Set(entries.map(([field]) => field));
    const first = Array.from(form?.querySelectorAll<HTMLElement>("[name]") ?? []).find((el) =>
      invalid.has(el.getAttribute("name") ?? ""),
    );
    first?.focus();
    return false;
  };

  /** Runs the API call, mapping field errors from the server onto the form. */
  const submit = async (t: Translate, action: () => Promise<void>): Promise<boolean> => {
    if (submitting) return false;
    setSubmitting(true);
    setFormError(null);
    try {
      await action();
      return true;
    } catch (caught) {
      const error = toApiError(caught);
      if (error.errors && Object.keys(error.errors).length) {
        setErrors(error.errors);
        setFormError(error.status === 409 ? (error.detail ?? t("validation.fixErrors")) : t("validation.fixErrors"));
      } else {
        setFormError(errorMessage(error, t));
      }
      return false;
    } finally {
      setSubmitting(false);
    }
  };

  return { values, set, setValues, errors, setErrors, formError, setFormError, submitting, reset, check, submit };
}
