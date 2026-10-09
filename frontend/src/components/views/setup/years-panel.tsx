"use client";

import { CalendarCheck, Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import type { AcademicYear } from "@/lib/types";
import type { ApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { isPlainDate, suggestYearName } from "@/lib/validation";

type YearValues = { name: string; startsOn: string; endsOn: string; current: boolean };

/** The same checks the API makes (docs/api/phase-1.md). Overlaps are checked by the server. */
export function validateYear(values: YearValues): Problems {
  const problems: Problems = {};
  const name = values.name.trim() || suggestYearName(values.startsOn);
  if (!name) problems.name = "validation.required";
  else if (name.length > 20) problems.name = "validation.tooLong";
  if (!isPlainDate(values.startsOn)) problems.startsOn = "validation.date";
  if (!isPlainDate(values.endsOn)) problems.endsOn = "validation.date";
  else if (isPlainDate(values.startsOn) && values.endsOn <= values.startsOn) problems.endsOn = "setup.years.endAfterStart";
  return problems;
}

function YearDialog({
  open,
  year,
  firstYear,
  onClose,
  onSaved,
}: {
  open: boolean;
  year: AcademicYear | null;
  firstYear: boolean;
  onClose: () => void;
  onSaved: (year: AcademicYear) => void;
}) {
  const { t } = useI18n();
  const initial: YearValues = year
    ? { name: year.name, startsOn: year.startsOn, endsOn: year.endsOn, current: year.current }
    : { name: "", startsOn: "", endsOn: "", current: firstYear };
  const form = useForm<YearValues>(initial);
  const { values, set, errors } = form;

  const close = () => {
    form.reset(initial);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateYear(values), t, event.currentTarget)) return;
    const body = {
      name: values.name.trim() || suggestYearName(values.startsOn),
      startsOn: values.startsOn,
      endsOn: values.endsOn,
      current: values.current,
    };
    let saved: AcademicYear | undefined;
    const ok = await form.submit(t, async () => {
      saved = year ? await api.updateYear(year.id, body) : await api.createYear(body);
    });
    if (ok && saved) {
      form.reset(initial);
      onSaved(saved);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={year ? t("setup.years.edit") : t("setup.years.add")}
      description={t("setup.years.dialog.description")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <div className="grid2">
          <TextField
            label={t("setup.years.startsOn")}
            name="startsOn"
            type="date"
            value={values.startsOn}
            onChange={(e) => set("startsOn", e.target.value)}
            error={errors.startsOn}
            required
            data-autofocus
          />
          <TextField
            label={t("setup.years.endsOn")}
            name="endsOn"
            type="date"
            value={values.endsOn}
            min={values.startsOn || undefined}
            onChange={(e) => set("endsOn", e.target.value)}
            error={errors.endsOn}
            required
          />
        </div>
        <TextField
          label={t("setup.years.name")}
          name="name"
          value={values.name}
          maxLength={20}
          placeholder={suggestYearName(values.startsOn) || t("setup.years.name.placeholder")}
          hint={t("setup.years.name.hint")}
          onChange={(e) => set("name", e.target.value)}
          error={errors.name}
        />
        {!year ? (
          <label className="check">
            <input
              type="checkbox"
              name="current"
              checked={values.current}
              onChange={(e) => set("current", e.target.checked)}
            />
            <span>{t("setup.years.makeCurrent")}</span>
          </label>
        ) : null}
        <div className="flex justify-end gap-2 pt-1">
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

export function YearsPanel({ years, canManage }: { years: ApiData<AcademicYear[]>; canManage: boolean }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const [editing, setEditing] = useState<AcademicYear | "new" | null>(null);
  const [deleting, setDeleting] = useState<AcademicYear | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const list = years.data ?? [];

  const makeCurrent = async (year: AcademicYear) => {
    setBusyId(year.id);
    try {
      await api.setCurrentYear(year.id);
      years.reload();
      toast(t("setup.years.nowCurrent", { name: year.name }));
    } catch (caught) {
      toast(errorMessage(toApiError(caught), t));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <section className="card" aria-labelledby="years-title">
      <div className="card-head">
        <div>
          <h2 id="years-title">{t("setup.years.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("setup.years.sub")}</p>
        </div>
        {canManage ? (
          <button type="button" className="btn btn-primary" onClick={() => setEditing("new")}>
            <Plus size={18} aria-hidden="true" />
            {t("setup.years.add")}
          </button>
        ) : null}
      </div>

      {years.error && !years.data ? (
        <ErrorState error={years.error} onRetry={years.reload} />
      ) : years.loading && !years.data ? (
        <LoadingRows rows={2} />
      ) : list.length === 0 ? (
        <p className="empty">{canManage ? t("setup.years.empty") : t("setup.years.emptyReadOnly")}</p>
      ) : (
        <ul className="list" data-testid="years-list">
          {list.map((year) => (
            <li key={year.id} className="li items-center">
              <span className="badge-ic">
                <CalendarCheck size={18} aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2 font-semibold">
                  {year.name}
                  {year.current ? (
                    <Pill tone="good" dot>
                      {t("setup.years.current")}
                    </Pill>
                  ) : null}
                </p>
                <p className="text-[13px] text-ink-3">
                  {t("setup.years.range", {
                    start: formatPlainDate(year.startsOn, locale),
                    end: formatPlainDate(year.endsOn, locale),
                  })}
                </p>
              </div>
              {canManage ? (
                <div className="flex flex-wrap justify-end gap-1">
                  {!year.current ? (
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => makeCurrent(year)}
                      disabled={busyId === year.id}
                    >
                      {t("setup.years.setCurrent")}
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="iconbtn"
                    aria-label={t("setup.years.editNamed", { name: year.name })}
                    onClick={() => setEditing(year)}
                  >
                    <Pencil size={17} aria-hidden="true" />
                  </button>
                  {!year.current ? (
                    <button
                      type="button"
                      className="iconbtn"
                      aria-label={t("setup.years.deleteNamed", { name: year.name })}
                      onClick={() => setDeleting(year)}
                    >
                      <Trash2 size={17} aria-hidden="true" />
                    </button>
                  ) : null}
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {canManage ? (
        <>
          <YearDialog
            key={editing === null ? "closed" : editing === "new" ? "new" : editing.id}
            open={editing !== null}
            year={editing === "new" ? null : editing}
            firstYear={list.length === 0}
            onClose={() => setEditing(null)}
            onSaved={(year) => {
              setEditing(null);
              years.reload();
              toast(t("setup.years.saved", { name: year.name }));
            }}
          />
          <ConfirmDialog
            open={deleting !== null}
            title={t("setup.years.deleteTitle", { name: deleting?.name ?? "" })}
            body={t("setup.years.deleteBody")}
            confirmLabel={t("common.delete")}
            onClose={() => setDeleting(null)}
            onConfirm={async () => {
              if (!deleting) return;
              await api.deleteYear(deleting.id);
              toast(t("setup.years.deleted", { name: deleting.name }));
              setDeleting(null);
              years.reload();
            }}
          />
        </>
      ) : null}
    </section>
  );
}
