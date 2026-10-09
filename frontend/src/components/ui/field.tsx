"use client";

import { useId } from "react";

type BaseProps = {
  label: string;
  hint?: string;
  error?: string;
  className?: string;
};

function describedBy(hintId: string, errorId: string, hint?: string, error?: string) {
  return [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
}

type TextFieldProps = BaseProps &
  Omit<React.InputHTMLAttributes<HTMLInputElement>, "className" | "id"> & {
    /** Element rendered at the end of the label row (e.g. a "Generate" button). */
    labelAction?: React.ReactNode;
  };

/** Labelled text input with optional hint and inline error, wired up with aria attributes. */
export function TextField({ label, hint, error, className, labelAction, ...input }: TextFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  return (
    <div className={`field ${className ?? ""}`}>
      <div className="flex items-center justify-between gap-2">
        <label htmlFor={id} className="field-label">
          {label}
        </label>
        {labelAction}
      </div>
      <input
        id={id}
        className="input"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(hintId, errorId, hint, error)}
        {...input}
      />
      {hint ? (
        <p id={hintId} className="field-hint">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="field-error">
          {error}
        </p>
      ) : null}
    </div>
  );
}

type SelectFieldProps = BaseProps &
  Omit<React.SelectHTMLAttributes<HTMLSelectElement>, "className" | "id"> & {
    options: { value: string; label: string }[];
  };

export function SelectField({ label, hint, error, className, options, ...select }: SelectFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  return (
    <div className={`field ${className ?? ""}`}>
      <label htmlFor={id}>{label}</label>
      <select
        id={id}
        className="input"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(hintId, errorId, hint, error)}
        {...select}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {hint ? (
        <p id={hintId} className="field-hint">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="field-error">
          {error}
        </p>
      ) : null}
    </div>
  );
}
