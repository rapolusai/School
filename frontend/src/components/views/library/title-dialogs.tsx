"use client";

import { CircleCheck, SearchCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { FormAlert } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { parseRupees, rupeesInput, todayInIndia } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import {
  COPY_SOURCES,
  EDITABLE_COPY_STATUSES,
  type CopySource,
  type CopyStatus,
  type CopyView,
  type IsbnLookup,
  type TitleDetail,
  type TitleRequest,
} from "@/lib/types";
import { useForm, type Problems } from "@/lib/use-form";
import { copyStatusLabel, isbnProblem, MAX_COPIES_AT_ONCE, MAX_PRICE_PAISE, sourceLabel } from "./library-shared";

/* Adding and editing titles and copies (docs/api/phase-2-library.md, "Catalogue"). */

export type TitleValues = {
  isbn: string;
  title: string;
  subtitle: string;
  authors: string;
  publisher: string;
  publicationYear: string;
  edition: string;
  language: string;
  subject: string;
  callNumber: string;
  shelfLocation: string;
  copies: string;
  price: string;
  source: CopySource;
  acquiredOn: string;
};

export const EMPTY_TITLE: TitleValues = {
  isbn: "",
  title: "",
  subtitle: "",
  authors: "",
  publisher: "",
  publicationYear: "",
  edition: "",
  language: "",
  subject: "",
  callNumber: "",
  shelfLocation: "",
  copies: "1",
  price: "",
  source: "PURCHASE",
  acquiredOn: "",
};

/** The longest text the API takes in each field. */
const MAX_LENGTH: Partial<Record<keyof TitleValues, number>> = {
  title: 300,
  subtitle: 300,
  authors: 500,
  publisher: 200,
  edition: 60,
  language: 40,
  subject: 100,
  callNumber: 40,
  shelfLocation: 60,
};

export function titleValues(title: TitleDetail): TitleValues {
  return {
    ...EMPTY_TITLE,
    isbn: title.isbn13 ?? "",
    title: title.title,
    subtitle: title.subtitle ?? "",
    authors: title.authors ?? "",
    publisher: title.publisher ?? "",
    publicationYear: title.publicationYear === null ? "" : String(title.publicationYear),
    edition: title.edition ?? "",
    language: title.language ?? "",
    subject: title.subject ?? "",
    callNumber: title.callNumber ?? "",
    shelfLocation: title.shelfLocation ?? "",
  };
}

const isWhole = (text: string) => /^\d+$/.test(text.trim());

/** The same checks the API makes on a title (and, when adding, its first copies). Pure and exported for tests. */
export function validateTitle(values: TitleValues, adding: boolean, today: string): Problems {
  const problems: Problems = {};
  if (values.isbn.trim()) {
    const problem = isbnProblem(values.isbn);
    if (problem) problems.isbn = problem;
  }
  if (!values.title.trim()) problems.title = "validation.required";
  for (const [field, max] of Object.entries(MAX_LENGTH) as [keyof TitleValues, number][]) {
    if (!problems[field] && String(values[field]).trim().length > max) problems[field] = "validation.tooLong";
  }
  const year = values.publicationYear.trim();
  if (year && (!isWhole(year) || Number(year) < 1400 || Number(year) > 2100)) problems.publicationYear = "library.v.year";
  if (adding) {
    const copies = values.copies.trim();
    if (!isWhole(copies) || Number(copies) > MAX_COPIES_AT_ONCE) problems.copies = "library.v.copies";
    if (values.price.trim()) {
      const paise = parseRupees(values.price);
      if (paise === null || paise > MAX_PRICE_PAISE) problems.pricePaise = "library.v.amount";
    }
    if (values.acquiredOn && values.acquiredOn > today) problems.acquiredOn = "library.v.future";
  }
  return problems;
}

const orNull = (text: string) => text.trim() || null;

/** The request body for the form. Pure and exported for tests. */
export function toTitleRequest(values: TitleValues, adding: boolean): TitleRequest {
  const body: TitleRequest = {
    isbn: orNull(values.isbn),
    title: values.title.trim(),
    subtitle: orNull(values.subtitle),
    authors: orNull(values.authors),
    publisher: orNull(values.publisher),
    publicationYear: values.publicationYear.trim() ? Number(values.publicationYear.trim()) : null,
    edition: orNull(values.edition),
    language: orNull(values.language),
    subject: orNull(values.subject),
    callNumber: orNull(values.callNumber),
    shelfLocation: orNull(values.shelfLocation),
  };
  if (!adding) return body;
  return {
    ...body,
    copies: Number(values.copies.trim()),
    pricePaise: values.price.trim() ? parseRupees(values.price) : null,
    source: values.source,
    acquiredOn: values.acquiredOn || null,
  };
}

/** Add a title (with its first copies) or edit one. */
export function TitleDialog({
  open,
  title,
  onClose,
  onSaved,
}: {
  open: boolean;
  /** the title being edited; null to add one */
  title: TitleDetail | null;
  onClose: () => void;
  onSaved: (title: TitleDetail) => void;
}) {
  const { t } = useI18n();
  const adding = title === null;
  const initial = title ? titleValues(title) : EMPTY_TITLE;
  const form = useForm<TitleValues>(initial);
  const { values, set, errors } = form;
  const [today] = useState(() => todayInIndia());
  const [lookup, setLookup] = useState<IsbnLookup | null>(null);
  const [checking, setChecking] = useState(false);

  const close = () => {
    form.reset(initial);
    setLookup(null);
    onClose();
  };

  const checkIsbn = async () => {
    const problem = values.isbn.trim() ? isbnProblem(values.isbn) : "validation.required";
    if (problem) {
      form.setErrors({ ...errors, isbn: t(problem) });
      return;
    }
    setChecking(true);
    try {
      const found = await libraryApi.isbn(values.isbn.trim());
      setLookup(found);
      if (!found.valid) form.setErrors({ ...errors, isbn: found.problem ?? t("library.v.isbnCheck") });
    } catch (caught) {
      form.setFormError(errorMessage(toApiError(caught), t));
    } finally {
      setChecking(false);
    }
  };

  const fillIn = () => {
    const s = lookup?.suggestion;
    if (!s) return;
    form.setValues((prev) => ({
      ...prev,
      title: s.title ?? prev.title,
      subtitle: s.subtitle ?? prev.subtitle,
      authors: s.authors ?? prev.authors,
      publisher: s.publisher ?? prev.publisher,
      publicationYear: s.publicationYear === null ? prev.publicationYear : String(s.publicationYear),
      edition: s.edition ?? prev.edition,
      language: s.language ?? prev.language,
      subject: s.subject ?? prev.subject,
      callNumber: s.callNumber ?? prev.callNumber,
    }));
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateTitle(values, adding, today), t, event.currentTarget)) return;
    let saved: TitleDetail | undefined;
    const body = toTitleRequest(values, adding);
    const ok = await form.submit(t, async () => {
      saved = title ? await libraryApi.updateTitle(title.id, body) : await libraryApi.createTitle(body);
    });
    if (ok && saved) {
      form.reset(adding ? EMPTY_TITLE : titleValues(saved));
      setLookup(null);
      onSaved(saved);
    }
  };

  const text = (field: keyof TitleValues, label: string, extra: Partial<React.ComponentProps<typeof TextField>> = {}) => (
    <TextField
      label={label}
      name={field === "price" ? "pricePaise" : field}
      value={String(values[field])}
      onChange={(e) => set(field, e.target.value as never)}
      error={errors[field === "price" ? "pricePaise" : field]}
      maxLength={MAX_LENGTH[field]}
      autoComplete="off"
      {...extra}
    />
  );

  return (
    <Dialog
      open={open}
      onClose={close}
      title={adding ? t("library.title.add") : t("library.title.edit")}
      description={adding ? t("library.title.addDescription") : undefined}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="title-form">
        <FormAlert message={form.formError} />
        <div className="flex flex-col gap-2">
          {text("isbn", t("library.field.isbn"), {
            hint: t("library.field.isbnHint"),
            maxLength: 20,
            inputMode: "numeric",
            labelAction: (
              <button type="button" className="linkbtn text-[13px]" onClick={checkIsbn} disabled={checking}>
                <SearchCheck size={14} aria-hidden="true" />
                {checking ? t("common.working") : t("library.isbn.check")}
              </button>
            ),
            "data-autofocus": true,
          } as Partial<React.ComponentProps<typeof TextField>>)}
          {lookup?.valid ? (
            <div className="alert alert-info flex-col items-start gap-1" role="status" data-testid="isbn-result">
              <p className="flex items-center gap-1.5">
                <CircleCheck size={16} aria-hidden="true" />
                {t("library.isbn.valid", { isbn13: lookup.isbn13 ?? "", isbn10: lookup.isbn10 ?? "—" })}
              </p>
              {lookup.existing && lookup.existing.id !== title?.id ? (
                <p>
                  {t("library.isbn.existing", { title: lookup.existing.title, copies: lookup.existing.copies })}{" "}
                  <Link className="link" href={`/app/library/titles/${lookup.existing.id}`} onClick={close}>
                    {t("library.isbn.open")}
                  </Link>
                </p>
              ) : lookup.suggestion ? null : (
                <p>{t("library.isbn.noLookup")}</p>
              )}
              {lookup.suggestion && lookup.existing?.id !== title?.id ? (
                <button type="button" className="btn btn-sm" onClick={fillIn}>
                  {t("library.isbn.fillIn")}
                </button>
              ) : null}
            </div>
          ) : null}
        </div>
        {text("title", t("library.field.title"), { required: true })}
        {text("subtitle", t("library.field.subtitle"))}
        {text("authors", t("library.field.authors"), { hint: t("library.field.authorsHint") })}
        <div className="grid2">
          {text("publisher", t("library.field.publisher"))}
          {text("publicationYear", t("library.field.year"), { inputMode: "numeric", maxLength: 4 })}
        </div>
        <div className="grid2">
          {text("edition", t("library.field.edition"))}
          {text("language", t("library.field.language"))}
        </div>
        <div className="grid2">
          {text("subject", t("library.field.subject"))}
          {text("callNumber", t("library.field.callNumber"), { hint: t("library.field.callNumberHint") })}
        </div>
        {text("shelfLocation", t("library.field.shelf"))}
        {adding ? (
          <fieldset className="fieldset flex flex-col gap-3">
            <legend>{t("library.title.firstCopies")}</legend>
            <div className="grid2">
              {text("copies", t("library.field.copies"), { inputMode: "numeric", maxLength: 3, hint: t("library.field.copiesHint") })}
              {text("price", t("library.field.price"), { inputMode: "decimal", maxLength: 14 })}
            </div>
            <div className="grid2">
              <SelectField
                label={t("library.field.source")}
                name="source"
                value={values.source}
                onChange={(e) => set("source", e.target.value as CopySource)}
                options={COPY_SOURCES.map((s) => ({ value: s, label: sourceLabel(t, s) }))}
              />
              {text("acquiredOn", t("library.field.acquiredOn"), { type: "date", max: today })}
            </div>
          </fieldset>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : adding ? t("library.title.addSubmit") : t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ copies */

type CopiesValues = { count: string; price: string; source: CopySource; acquiredOn: string };

/** Adds more copies of a title; each gets the next accession number. */
export function AddCopiesDialog({
  open,
  title,
  onClose,
  onSaved,
}: {
  open: boolean;
  title: TitleDetail;
  onClose: () => void;
  onSaved: (title: TitleDetail, added: number) => void;
}) {
  const { t } = useI18n();
  const initial: CopiesValues = { count: "1", price: "", source: "PURCHASE", acquiredOn: "" };
  const form = useForm<CopiesValues>(initial);
  const { values, set, errors } = form;
  const [today] = useState(() => todayInIndia());

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Problems = {};
    const count = values.count.trim();
    if (!isWhole(count) || Number(count) < 1 || Number(count) > MAX_COPIES_AT_ONCE) problems.count = "library.v.copiesAdd";
    const paise = values.price.trim() ? parseRupees(values.price) : null;
    if (values.price.trim() && (paise === null || paise > MAX_PRICE_PAISE)) problems.pricePaise = "library.v.amount";
    if (values.acquiredOn && values.acquiredOn > today) problems.acquiredOn = "library.v.future";
    if (!form.check(problems, t, event.currentTarget)) return;
    let saved: TitleDetail | undefined;
    const ok = await form.submit(t, async () => {
      saved = await libraryApi.addCopies(title.id, {
        count: Number(count),
        pricePaise: paise,
        source: values.source,
        acquiredOn: values.acquiredOn || null,
      });
    });
    if (ok && saved) {
      form.reset(initial);
      onSaved(saved, Number(count));
    }
  };

  return (
    <Dialog open={open} onClose={close} title={t("library.copies.addTitle", { title: title.title })} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="copies-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("library.field.copies")}
            name="count"
            inputMode="numeric"
            value={values.count}
            onChange={(e) => set("count", e.target.value)}
            error={errors.count}
            maxLength={3}
            required
            data-autofocus
          />
          <TextField
            label={t("library.field.price")}
            name="pricePaise"
            inputMode="decimal"
            value={values.price}
            onChange={(e) => set("price", e.target.value)}
            error={errors.pricePaise}
            maxLength={14}
            autoComplete="off"
          />
        </div>
        <div className="grid2">
          <SelectField
            label={t("library.field.source")}
            name="source"
            value={values.source}
            onChange={(e) => set("source", e.target.value as CopySource)}
            options={COPY_SOURCES.map((s) => ({ value: s, label: sourceLabel(t, s) }))}
          />
          <TextField
            label={t("library.field.acquiredOn")}
            name="acquiredOn"
            type="date"
            max={today}
            value={values.acquiredOn}
            onChange={(e) => set("acquiredOn", e.target.value)}
            error={errors.acquiredOn}
          />
        </div>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("library.copies.addSubmit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type CopyValues = { price: string; source: CopySource; acquiredOn: string; status: CopyStatus; statusNote: string };

/** Edits one copy: price, source, date and its shelf status (lost, damaged, withdrawn or back on the shelf). */
export function CopyDialog({ copy, onClose, onSaved }: { copy: CopyView | null; onClose: () => void; onSaved: (copy: CopyView) => void }) {
  const { t } = useI18n();
  const initial: CopyValues = {
    price: copy?.pricePaise === null || copy?.pricePaise === undefined ? "" : rupeesInput(copy.pricePaise),
    source: copy?.source ?? "PURCHASE",
    acquiredOn: copy?.acquiredOn ?? "",
    status: copy?.status ?? "AVAILABLE",
    statusNote: copy?.statusNote ?? "",
  };
  const form = useForm<CopyValues>(initial);
  const { values, set, errors } = form;
  const [today] = useState(() => todayInIndia());
  const atDesk = copy?.status === "ISSUED" || copy?.status === "RESERVED";

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!copy) return;
    const problems: Problems = {};
    const paise = values.price.trim() ? parseRupees(values.price) : null;
    if (values.price.trim() && (paise === null || paise > MAX_PRICE_PAISE)) problems.pricePaise = "library.v.amount";
    if (values.acquiredOn && values.acquiredOn > today) problems.acquiredOn = "library.v.future";
    if (values.statusNote.trim().length > 300) problems.statusNote = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    let saved: CopyView | undefined;
    const ok = await form.submit(t, async () => {
      saved = await libraryApi.updateCopy(copy.id, {
        pricePaise: paise,
        source: values.source,
        acquiredOn: values.acquiredOn || null,
        status: values.status,
        statusNote: values.statusNote.trim() || null,
      });
    });
    if (ok && saved) onSaved(saved);
  };

  const statuses: readonly CopyStatus[] = atDesk && copy ? [copy.status] : EDITABLE_COPY_STATUSES;

  return (
    <Dialog
      open={copy !== null}
      onClose={close}
      title={t("library.copy.editTitle", { copy: copy?.accessionNo ?? "" })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="copy-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("library.field.price")}
            name="pricePaise"
            inputMode="decimal"
            value={values.price}
            onChange={(e) => set("price", e.target.value)}
            error={errors.pricePaise}
            maxLength={14}
            autoComplete="off"
            data-autofocus
          />
          <SelectField
            label={t("library.field.source")}
            name="source"
            value={values.source}
            onChange={(e) => set("source", e.target.value as CopySource)}
            options={COPY_SOURCES.map((s) => ({ value: s, label: sourceLabel(t, s) }))}
          />
        </div>
        <div className="grid2">
          <TextField
            label={t("library.field.acquiredOn")}
            name="acquiredOn"
            type="date"
            max={today}
            value={values.acquiredOn}
            onChange={(e) => set("acquiredOn", e.target.value)}
            error={errors.acquiredOn}
          />
          <SelectField
            label={t("library.field.status")}
            name="status"
            value={values.status}
            onChange={(e) => set("status", e.target.value as CopyStatus)}
            error={errors.status}
            disabled={atDesk}
            hint={atDesk ? t("library.copy.atDesk") : undefined}
            options={statuses.map((s) => ({ value: s, label: copyStatusLabel(t, s) }))}
          />
        </div>
        <TextAreaField
          label={t("library.field.noteOptional")}
          name="statusNote"
          value={values.statusNote}
          onChange={(e) => set("statusNote", e.target.value)}
          error={errors.statusNote}
          maxLength={300}
          rows={2}
        />
        <div className="flex justify-end gap-2">
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
