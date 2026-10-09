"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField } from "@/components/ui/field";
import { FormAlert, LoadingRows } from "@/components/ui/states";
import { api } from "@/lib/api";
import { plural, useI18n } from "@/lib/i18n";
import type { AcademicYear, PromotionResult } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { sectionOptions } from "./student-form";

type Values = {
  fromYearId: string;
  fromSectionId: string;
  action: "promote" | "graduate";
  toYearId: string;
  toSectionId: string;
};

export function validatePromotion(values: Values, years: AcademicYear[]): Problems {
  const problems: Problems = {};
  if (!values.fromYearId) problems.fromYearId = "validation.choose";
  if (!values.fromSectionId) problems.fromSectionId = "validation.choose";
  if (values.action === "promote") {
    const from = years.find((y) => y.id === values.fromYearId);
    const to = years.find((y) => y.id === values.toYearId);
    if (!to) problems.toYearId = "validation.choose";
    else if (from && to.startsOn <= from.startsOn) problems.toYearId = "students.promote.laterYear";
    if (!values.toSectionId) problems.toSectionId = "validation.choose";
  }
  return problems;
}

/** The year after `year`, if the school has set it up. */
export function nextYearOf(years: AcademicYear[], yearId: string): AcademicYear | undefined {
  const from = years.find((y) => y.id === yearId);
  if (!from) return undefined;
  return [...years].filter((y) => y.startsOn > from.startsOn).sort((a, b) => a.startsOn.localeCompare(b.startsOn))[0];
}

/**
 * Year-end promotion: every active student of one section moves to a section of a later year,
 * or the class leaves as alumni. Students already placed in the target year are skipped.
 */
export function PromoteDialog({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const years = useApiData(open ? "academics:years" : null, api.listYears);
  const classes = useApiData(open ? "academics:classes" : null, api.listClasses);
  const [result, setResult] = useState<PromotionResult | null>(null);
  const yearList = years.data ?? [];
  const current = yearList.find((y) => y.current);
  const empty: Values = { fromYearId: "", fromSectionId: "", action: "promote", toYearId: "", toSectionId: "" };
  const form = useForm<Values>(empty);
  const { values, errors, set } = form;

  // Until the person picks a year, the current one is the source and the year after it the target.
  const fromYearId = values.fromYearId || current?.id || "";
  const toYearId = values.toYearId || nextYearOf(yearList, fromYearId)?.id || "";
  const effective: Values = { ...values, fromYearId, toYearId };
  const options = sectionOptions(classes.data ?? [], (used, capacity) => t("setup.sections.seats", { used, capacity }))
    .map((o) => ({ value: o.value, label: o.label.split(" · ")[0] }));

  const close = () => {
    form.reset(empty);
    setResult(null);
    onClose();
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validatePromotion(effective, yearList), t, event.currentTarget)) return;
    const graduate = effective.action === "graduate";
    let outcome: PromotionResult | undefined;
    const ok = await form.submit(t, async () => {
      outcome = await api.promoteStudents({
        fromSectionId: effective.fromSectionId,
        fromYearId: effective.fromYearId,
        toSectionId: graduate ? null : effective.toSectionId,
        toYearId: graduate ? null : effective.toYearId,
        graduate,
      });
    });
    if (ok && outcome) {
      setResult(outcome);
      onDone();
    }
  };

  const yearOptions = yearList.map((y) => ({ value: y.id, label: y.name }));
  const laterYears = yearList.filter((y) => {
    const from = yearList.find((f) => f.id === fromYearId);
    return !from || y.startsOn > from.startsOn;
  });

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t("students.promote.title")}
      description={t("students.promote.description")}
      closeLabel={t("common.close")}
    >
      {result ? (
        <div className="flex flex-col gap-3" data-testid="promotion-result">
          <p className="alert alert-info">
            {result.graduated > 0
              ? plural(t, "students.promote.graduated", result.graduated)
              : plural(t, "students.promote.promoted", result.promoted)}
          </p>
          {result.skipped.length ? (
            <div>
              <p className="text-sm font-semibold">{plural(t, "students.promote.skipped", result.skipped.length)}</p>
              <ul className="mt-1 list-disc pl-5 text-sm text-ink-2">
                {result.skipped.map((s) => (
                  <li key={s.studentId}>
                    {s.fullName}: {s.reason}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setResult(null);
                form.reset({ ...empty, fromYearId: values.fromYearId, toYearId: values.toYearId });
              }}
            >
              {t("students.promote.another")}
            </button>
            <button type="button" className="btn btn-primary" onClick={close} data-autofocus>
              {t("common.done")}
            </button>
          </div>
        </div>
      ) : (years.loading && !years.data) || (classes.loading && !classes.data) ? (
        <LoadingRows rows={3} />
      ) : yearList.length === 0 ? (
        <p className="alert alert-info">{t("students.noYear")}</p>
      ) : (
        <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
          <FormAlert message={form.formError} />
          <div className="grid2">
            <SelectField
              label={t("students.promote.fromYear")}
              name="fromYearId"
              value={fromYearId}
              onChange={(e) => {
                set("fromYearId", e.target.value);
                set("toYearId", "");
              }}
              error={errors.fromYearId}
              options={[{ value: "", label: t("common.choose") }, ...yearOptions]}
            />
            <SelectField
              label={t("students.promote.fromSection")}
              name="fromSectionId"
              value={values.fromSectionId}
              onChange={(e) => set("fromSectionId", e.target.value)}
              error={errors.fromSectionId}
              options={[{ value: "", label: t("common.choose") }, ...options]}
              data-autofocus
            />
          </div>
          <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
            <legend className="field-label mb-1.5">{t("students.promote.action")}</legend>
            <div className="seg">
              <label>
                <input
                  type="radio"
                  name="action"
                  value="promote"
                  checked={values.action === "promote"}
                  onChange={() => set("action", "promote")}
                />
                {t("students.promote.toNextClass")}
              </label>
              <label>
                <input
                  type="radio"
                  name="action"
                  value="graduate"
                  checked={values.action === "graduate"}
                  onChange={() => set("action", "graduate")}
                />
                {t("students.promote.graduate")}
              </label>
            </div>
          </fieldset>
          {values.action === "promote" ? (
            <div className="grid2">
              <SelectField
                label={t("students.promote.toYear")}
                name="toYearId"
                value={toYearId}
                onChange={(e) => set("toYearId", e.target.value)}
                error={errors.toYearId}
                hint={laterYears.length === 0 ? t("students.promote.addNextYear") : undefined}
                options={[
                  { value: "", label: t("common.choose") },
                  ...laterYears.map((y) => ({ value: y.id, label: y.name })),
                ]}
              />
              <SelectField
                label={t("students.promote.toSection")}
                name="toSectionId"
                value={values.toSectionId}
                onChange={(e) => set("toSectionId", e.target.value)}
                error={errors.toSectionId}
                options={[{ value: "", label: t("common.choose") }, ...options]}
              />
            </div>
          ) : (
            <p className="field-hint">{t("students.promote.graduateHint")}</p>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <button type="button" className="btn" onClick={close}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={form.submitting}>
              {form.submitting
                ? t("common.working")
                : values.action === "graduate"
                  ? t("students.promote.submitGraduate")
                  : t("students.promote.submit")}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
