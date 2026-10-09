"use client";

import { ArrowLeft, Minus, Plus, Send } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { feesApi } from "@/lib/fees-api";
import { formatPaise, parseRupees, rupeesInput } from "@/lib/format";
import { useI18n, type MessageKey, type Translate, type TranslateVars } from "@/lib/i18n";
import {
  MAX_AMOUNT_PAISE,
  MAX_INSTALMENTS,
  type FeeHead,
  type FeeStructure,
  type StructureRequest,
  type StructureResult,
} from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { isPlainDate } from "@/lib/validation";

export type InstalmentValues = { label: string; dueDate: string };

export type StructureValues = {
  /** Rupees per head id, as typed; empty means the head is not charged. */
  amounts: Record<string, string>;
  instalments: InstalmentValues[];
  /** true: amounts per head and instalment are typed; false: each head is split evenly. */
  custom: boolean;
  /** Rupees per instalment index and head id (custom only). */
  shares: Record<string, string>[];
};

export type StructureIssue = { field: string; key: MessageKey; vars?: TranslateVars };

/** Splits paise into `parts` whole shares; the last one takes the rounding (as the API does). */
export function splitEvenly(total: number, parts: number): number[] {
  const base = Math.floor(total / parts);
  return Array.from({ length: parts }, (_, i) => (i === parts - 1 ? total - base * (parts - 1) : base));
}

/** The label the API gives an instalment left unnamed. */
export function defaultInstalmentLabel(count: number, index: number): string {
  if (count === 1) return "Annual";
  if (count === 2) return `Term ${index + 1}`;
  if (count === 4) return `Quarter ${index + 1}`;
  return `Instalment ${index + 1}`;
}

/** Heads with an amount above zero, in the order the school lists them, with their paise. */
export function chargedHeads(values: StructureValues, heads: FeeHead[]): { head: FeeHead; paise: number }[] {
  return heads
    .map((head) => ({ head, paise: parseRupees(values.amounts[head.id] ?? "") ?? 0 }))
    .filter((h) => h.paise > 0);
}

/** Each instalment's share of each charged head: the typed shares, or the even split. */
export function planShares(values: StructureValues, heads: FeeHead[]): Record<string, number>[] {
  const n = values.instalments.length;
  const plan: Record<string, number>[] = values.instalments.map(() => ({}));
  for (const { head, paise } of chargedHeads(values, heads)) {
    if (values.custom) {
      values.instalments.forEach((_, i) => {
        plan[i][head.id] = parseRupees(values.shares[i]?.[head.id] ?? "") ?? 0;
      });
    } else if (head.oneTime) {
      values.instalments.forEach((_, i) => {
        plan[i][head.id] = i === 0 ? paise : 0;
      });
    } else {
      splitEvenly(paise, n).forEach((share, i) => {
        plan[i][head.id] = share;
      });
    }
  }
  return plan;
}

/**
 * The checks POST/PUT /api/fees/structures makes: at least one head with an amount, 1–12
 * instalments due one after another, and typed shares that add up to each head's amount.
 */
export function validateStructure(values: StructureValues, heads: FeeHead[]): StructureIssue[] {
  const issues: StructureIssue[] = [];
  let total = 0;
  for (const head of heads) {
    const text = (values.amounts[head.id] ?? "").trim();
    if (!text) continue;
    const paise = parseRupees(text);
    if (paise === null) issues.push({ field: `amount.${head.id}`, key: "fees.v.amount" });
    else if (paise > MAX_AMOUNT_PAISE) issues.push({ field: `amount.${head.id}`, key: "fees.v.amountTooLarge" });
    else total += paise;
  }
  if (total <= 0 && !issues.length) issues.push({ field: "heads", key: "fees.v.noHeads" });
  const n = values.instalments.length;
  if (n < 1 || n > MAX_INSTALMENTS) issues.push({ field: "instalments", key: "fees.v.instalmentCount" });
  let previous = "";
  values.instalments.forEach((instalment, i) => {
    if (instalment.label.trim().length > 40) {
      issues.push({ field: `instalments[${i}].label`, key: "validation.tooLong" });
    }
    if (!isPlainDate(instalment.dueDate)) {
      issues.push({ field: `instalments[${i}].dueDate`, key: "validation.date" });
    } else {
      if (previous && instalment.dueDate <= previous) {
        issues.push({ field: `instalments[${i}].dueDate`, key: "fees.v.dueOrder" });
      }
      previous = instalment.dueDate;
    }
  });
  if (values.custom && !issues.length) {
    for (const { head, paise } of chargedHeads(values, heads)) {
      let sum = 0;
      values.instalments.forEach((_, i) => {
        const text = (values.shares[i]?.[head.id] ?? "").trim();
        const share = text ? parseRupees(text) : 0;
        if (share === null) issues.push({ field: `share.${i}.${head.id}`, key: "fees.v.amount" });
        else sum += share;
      });
      if (sum !== paise) {
        issues.push({
          field: `sum.${head.id}`,
          key: "fees.v.sharesMismatch",
          vars: { head: head.name, sum: formatPaise(sum), amount: formatPaise(paise) },
        });
      }
    }
  }
  return issues;
}

/** The request body for the editor's values. */
export function toStructureRequest(
  values: StructureValues,
  heads: FeeHead[],
  academicYearId: string,
  classId: string,
): StructureRequest {
  const charged = chargedHeads(values, heads);
  return {
    academicYearId,
    classId,
    heads: charged.map(({ head, paise }) => ({ headId: head.id, amountPaise: paise })),
    instalments: values.instalments.map((instalment, i) => ({
      label: instalment.label.trim() || null,
      dueDate: instalment.dueDate,
      shares: values.custom
        ? charged.map(({ head }) => ({
            headId: head.id,
            amountPaise: parseRupees(values.shares[i]?.[head.id] ?? "") ?? 0,
          }))
        : null,
    })),
  };
}

/** `count` due dates a whole number of months apart from the year's start, on the 10th. */
export function spreadDueDates(startsOn: string, count: number): string[] {
  const [year, month] = startsOn.split("-").map(Number);
  const step = Math.max(1, Math.floor(12 / count));
  return Array.from({ length: count }, (_, i) => {
    const date = new Date(Date.UTC(year, month - 1 + i * step, 10));
    return date.toISOString().slice(0, 10);
  });
}

/** "2026-06-10" + 1 month → "2026-07-10" (the day is kept, or the month's last day). */
export function addMonths(date: string, months: number): string {
  const [year, month, day] = date.split("-").map(Number);
  const lastDay = new Date(Date.UTC(year, month - 1 + months + 1, 0)).getUTCDate();
  return new Date(Date.UTC(year, month - 1 + months, Math.min(day, lastDay))).toISOString().slice(0, 10);
}

/** Editor values for a saved structure, or a blank one for a class that has none. */
export function structureValues(structure: FeeStructure | null, yearStartsOn: string): StructureValues {
  if (!structure) {
    return {
      amounts: {},
      instalments: spreadDueDates(yearStartsOn, 4).map((dueDate) => ({ label: "", dueDate })),
      custom: false,
      shares: [],
    };
  }
  const amounts: Record<string, string> = {};
  for (const head of structure.heads) amounts[head.headId] = rupeesInput(head.amountPaise);
  const n = structure.instalments.length;
  const shares = structure.instalments.map((instalment) =>
    Object.fromEntries(structure.heads.map((h) => [h.headId, rupeesInput(instalment.shares.find((s) => s.headId === h.headId)?.amountPaise ?? 0)])),
  );
  // Show the even split when that is what the saved shares are.
  const even = structure.heads.every((head) => {
    const expected = head.oneTime
      ? structure.instalments.map((_, i) => (i === 0 ? head.amountPaise : 0))
      : splitEvenly(head.amountPaise, n);
    return structure.instalments.every(
      (instalment, i) => (instalment.shares.find((s) => s.headId === head.headId)?.amountPaise ?? 0) === expected[i],
    );
  });
  return {
    amounts,
    instalments: structure.instalments.map((i) => ({
      label: i.label === defaultInstalmentLabel(n, i.seq - 1) ? "" : i.label,
      dueDate: i.dueDate,
    })),
    custom: !even,
    shares,
  };
}

/** Translated messages per field for the issues found. */
function issueMessages(issues: StructureIssue[], t: Translate): Record<string, string> {
  const out: Record<string, string> = {};
  for (const issue of issues) if (!out[issue.field]) out[issue.field] = t(issue.key, issue.vars);
  return out;
}

/** Edits one class's fee structure for one year, then saves it as a draft or publishes it. */
export function StructureEditor({
  academicYearId,
  yearName,
  yearStartsOn,
  classId,
  className,
  structureId,
  canManage,
  onBack,
  onChanged,
}: {
  academicYearId: string;
  yearName: string;
  yearStartsOn: string;
  classId: string;
  className: string;
  structureId: string | null;
  canManage: boolean;
  onBack: () => void;
  onChanged: () => void;
}) {
  const { t } = useI18n();
  const heads = useApiData("fees:heads", feesApi.listHeads);
  const structure = useApiData(structureId ? `fees:structure:${structureId}` : null, () =>
    feesApi.getStructure(structureId as string),
  );

  const back = (
    <button type="button" className="linkbtn inline-flex items-center gap-1 text-[13.5px]" onClick={onBack}>
      <ArrowLeft size={16} aria-hidden="true" />
      {t("fees.structures.back")}
    </button>
  );
  const failed = heads.error ?? structure.error;
  if (failed && (!heads.data || (structureId && !structure.data))) {
    return (
      <>
        {back}
        <section className="card">
          <ErrorState
            error={failed}
            onRetry={() => {
              heads.reload();
              structure.reload();
            }}
          />
        </section>
      </>
    );
  }
  if (!heads.data || (structureId && !structure.data)) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }
  const saved = structure.data ?? null;
  const used = new Set(saved?.heads.map((h) => h.headId) ?? []);
  const usable = heads.data.filter((h) => h.active || used.has(h.id));
  return (
    <>
      {back}
      <StructureForm
        key={saved ? `${saved.id}:${saved.status}:${saved.totalPaise}` : "new"}
        heads={usable}
        saved={saved}
        academicYearId={academicYearId}
        yearName={yearName}
        yearStartsOn={yearStartsOn}
        classId={classId}
        className={className}
        canManage={canManage}
        onSaved={() => {
          structure.reload();
          onChanged();
        }}
        onCreated={onChanged}
      />
    </>
  );
}

function StructureForm({
  heads,
  saved,
  academicYearId,
  yearName,
  yearStartsOn,
  classId,
  className,
  canManage,
  onSaved,
  onCreated,
}: {
  heads: FeeHead[];
  saved: FeeStructure | null;
  academicYearId: string;
  yearName: string;
  yearStartsOn: string;
  classId: string;
  className: string;
  canManage: boolean;
  onSaved: () => void;
  onCreated: (id: string) => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<StructureValues>(structureValues(saved, yearStartsOn));
  const { values, setValues, errors, setErrors } = form;
  const [current, setCurrent] = useState<FeeStructure | null>(saved);
  const [publishing, setPublishing] = useState(false);
  const readOnly = !canManage;
  const n = values.instalments.length;
  const charged = chargedHeads(values, heads);
  const plan = planShares(values, heads);
  const total = charged.reduce((sum, h) => sum + h.paise, 0);

  const update = (next: Partial<StructureValues>, clear: string[] = []) => {
    setValues((prev) => ({ ...prev, ...next }));
    if (clear.length) setErrors((prev) => ({ ...prev, ...Object.fromEntries(clear.map((f) => [f, undefined])) }));
  };

  const setAmount = (headId: string, text: string) =>
    update({ amounts: { ...values.amounts, [headId]: text } }, [`amount.${headId}`, "heads"]);

  const setInstalment = (index: number, patch: Partial<InstalmentValues>) =>
    update(
      { instalments: values.instalments.map((item, i) => (i === index ? { ...item, ...patch } : item)) },
      [`instalments[${index}].dueDate`, `instalments[${index}].label`],
    );

  // Fewer instalments drops the last ones; more adds one a month after the last due date.
  const setCount = (count: number) => {
    if (count < 1 || count > MAX_INSTALMENTS || count === n) return;
    const instalments = values.instalments.slice(0, count);
    while (instalments.length < count) {
      const last = instalments[instalments.length - 1]?.dueDate;
      const next = isPlainDate(last ?? "") ? addMonths(last, 1) : spreadDueDates(yearStartsOn, 1)[0];
      instalments.push({ label: "", dueDate: next });
    }
    update({ instalments, shares: [], custom: false }, ["instalments"]);
  };

  const setCustom = (custom: boolean) => {
    // Start from the even split so only the differences need typing.
    const shares = custom
      ? plan.map((row) => Object.fromEntries(charged.map(({ head }) => [head.id, rupeesInput(row[head.id] ?? 0)])))
      : [];
    update({ custom, shares });
  };

  const setShare = (index: number, headId: string, text: string) =>
    update(
      { shares: values.instalments.map((_, i) => ({ ...(values.shares[i] ?? {}), ...(i === index ? { [headId]: text } : {}) })) },
      [`share.${index}.${headId}`, `sum.${headId}`],
    );

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const issues = validateStructure(values, heads);
    if (issues.length) {
      setErrors(issueMessages(issues, t));
      form.setFormError(t("validation.fixErrors"));
      const fields = new Set(issues.map((i) => i.field));
      Array.from(event.currentTarget.querySelectorAll<HTMLElement>("[name]"))
        .find((el) => fields.has(el.getAttribute("name") ?? ""))
        ?.focus();
      return;
    }
    let result: StructureResult | undefined;
    const body = toStructureRequest(values, heads, academicYearId, classId);
    const ok = await form.submit(t, async () => {
      result = current
        ? await feesApi.updateStructure(current.id, body)
        : await feesApi.createStructure(body);
    });
    if (ok && result) {
      const created = !current;
      setCurrent(result.structure);
      const dues = result.dues;
      toast(
        result.structure.status === "PUBLISHED"
          ? t("fees.structure.savedPublished", {
              updated: dues.studentsUpdated + dues.studentsCreated,
              kept: dues.paidCellsKept,
            })
          : t("fees.structure.savedDraft"),
      );
      if (created) onCreated(result.structure.id);
      else onSaved();
    }
  };

  const publish = async () => {
    if (!current) return;
    const result = await feesApi.publishStructure(current.id);
    setCurrent(result.structure);
    setPublishing(false);
    toast(t("fees.structure.published", { count: result.dues.studentsCreated + result.dues.studentsUpdated }));
    onSaved();
  };

  const status = current?.status ?? null;
  // Server errors on fields this form has no input for.
  const shown = (field: string) =>
    field === "heads" ||
    field === "instalments" ||
    field.startsWith("amount.") ||
    field.startsWith("share.") ||
    field.startsWith("sum.") ||
    /^instalments\[\d+\]\.(dueDate|label)$/.test(field);
  const otherErrors = Object.entries(errors).filter(
    (entry): entry is [string, string] => Boolean(entry[1]) && !shown(entry[0]),
  );

  return (
    <section className="card flex flex-col gap-4" aria-labelledby="structure-title">
      <div className="card-head mb-0">
        <div className="min-w-0">
          <p className="eyebrow">{yearName}</p>
          <h2 id="structure-title" className="mt-0.5 flex flex-wrap items-center gap-2">
            {t("fees.structure.title", { name: className })}
            {status ? (
              <Pill tone={status === "PUBLISHED" ? "good" : "warn"} dot>
                {t(status === "PUBLISHED" ? "fees.structure.status.PUBLISHED" : "fees.structure.status.DRAFT")}
              </Pill>
            ) : null}
          </h2>
          <p className="mt-1 text-sm text-ink-2">
            {status === "PUBLISHED" ? t("fees.structure.publishedNote") : t("fees.structure.draftNote")}
          </p>
        </div>
        {canManage && current && status === "DRAFT" ? (
          <button type="button" className="btn" onClick={() => setPublishing(true)}>
            <Send size={18} aria-hidden="true" />
            {t("fees.structure.publish")}
          </button>
        ) : null}
      </div>

      <form method="post" className="flex flex-col gap-5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        {otherErrors.length ? (
          <ul className="alert alert-bad flex-col" role="alert">
            {otherErrors.map(([field, message]) => (
              <li key={field}>{message}</li>
            ))}
          </ul>
        ) : null}

        <fieldset className="fieldset" disabled={readOnly}>
          <legend>{t("fees.structure.heads")}</legend>
          <p className="text-[13px] text-ink-3">{t("fees.structure.headsHint")}</p>
          {heads.length === 0 ? (
            <p className="text-sm text-ink-2">{t("fees.structure.noHeads")}</p>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {heads.map((head) => (
                <label key={head.id} className="field">
                  <span className="field-label">
                    {head.name}
                    {head.oneTime ? <span className="ml-1 font-normal text-ink-3">({t("fees.heads.oneTime")})</span> : null}
                  </span>
                  <input
                    className="input num"
                    name={`amount.${head.id}`}
                    inputMode="decimal"
                    placeholder="0"
                    value={values.amounts[head.id] ?? ""}
                    onChange={(e) => setAmount(head.id, e.target.value)}
                    aria-invalid={errors[`amount.${head.id}`] ? true : undefined}
                  />
                  {errors[`amount.${head.id}`] ? (
                    <span className="field-error">{errors[`amount.${head.id}`]}</span>
                  ) : null}
                </label>
              ))}
            </div>
          )}
          {errors.heads ? <p className="field-error">{errors.heads}</p> : null}
          <p className="text-sm font-semibold" data-testid="structure-total">
            {t("fees.structure.total", { amount: formatPaise(total) })}
          </p>
        </fieldset>

        <fieldset className="fieldset" disabled={readOnly}>
          <legend>{t("fees.structure.instalments")}</legend>
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-sm text-ink-2">{t("fees.structure.count")}</span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                className="iconbtn"
                onClick={() => setCount(n - 1)}
                disabled={n <= 1}
                aria-label={t("fees.structure.fewer")}
              >
                <Minus size={18} aria-hidden="true" />
              </button>
              <span className="num w-8 text-center font-semibold" data-testid="instalment-count">
                {n}
              </span>
              <button
                type="button"
                className="iconbtn"
                onClick={() => setCount(n + 1)}
                disabled={n >= MAX_INSTALMENTS}
                aria-label={t("fees.structure.more")}
              >
                <Plus size={18} aria-hidden="true" />
              </button>
            </div>
            <label className="check ml-auto">
              <input type="checkbox" name="custom" checked={values.custom} onChange={(e) => setCustom(e.target.checked)} />
              <span>{t("fees.structure.custom")}</span>
            </label>
          </div>
          <p className="text-[13px] text-ink-3">
            {values.custom ? t("fees.structure.customHint") : t("fees.structure.evenHint")}
          </p>
          {errors.instalments ? <p className="field-error">{errors.instalments}</p> : null}

          <div className="table-wrap">
            <table className="table structure-grid">
              <thead>
                <tr>
                  <th scope="col">{t("fees.structure.col.label")}</th>
                  <th scope="col">{t("fees.structure.col.dueDate")}</th>
                  {values.custom
                    ? charged.map(({ head }) => (
                        <th key={head.id} scope="col" className="r">
                          {head.name}
                        </th>
                      ))
                    : null}
                  <th scope="col" className="r">
                    {t("fees.structure.col.amount")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {values.instalments.map((instalment, i) => {
                  const rowTotal = Object.values(plan[i] ?? {}).reduce((sum, v) => sum + v, 0);
                  const dueError = errors[`instalments[${i}].dueDate`];
                  const labelError = errors[`instalments[${i}].label`];
                  return (
                    <tr key={i}>
                      <td className="min-w-[140px]">
                        <input
                          className="input"
                          name={`instalments[${i}].label`}
                          value={instalment.label}
                          maxLength={40}
                          placeholder={defaultInstalmentLabel(n, i)}
                          onChange={(e) => setInstalment(i, { label: e.target.value })}
                          aria-label={t("fees.structure.labelOf", { n: i + 1 })}
                          aria-invalid={labelError ? true : undefined}
                        />
                        {labelError ? <span className="field-error">{labelError}</span> : null}
                      </td>
                      <td className="min-w-[150px]">
                        <input
                          className="input"
                          type="date"
                          name={`instalments[${i}].dueDate`}
                          value={instalment.dueDate}
                          onChange={(e) => setInstalment(i, { dueDate: e.target.value })}
                          aria-label={t("fees.structure.dueDateOf", { n: i + 1 })}
                          aria-invalid={dueError ? true : undefined}
                        />
                        {dueError ? <span className="field-error">{dueError}</span> : null}
                      </td>
                      {values.custom
                        ? charged.map(({ head }) => (
                            <td key={head.id} className="r min-w-[110px]">
                              <input
                                className="input num text-right"
                                name={`share.${i}.${head.id}`}
                                inputMode="decimal"
                                value={values.shares[i]?.[head.id] ?? ""}
                                onChange={(e) => setShare(i, head.id, e.target.value)}
                                aria-label={t("fees.structure.shareOf", { head: head.name, n: i + 1 })}
                                aria-invalid={errors[`share.${i}.${head.id}`] ? true : undefined}
                              />
                            </td>
                          ))
                        : null}
                      <td className="r num font-semibold whitespace-nowrap">{formatPaise(rowTotal)}</td>
                    </tr>
                  );
                })}
              </tbody>
              {values.custom && charged.length ? (
                <tfoot>
                  <tr>
                    <th scope="row" colSpan={2} className="text-left">
                      {t("fees.structure.headTotals")}
                    </th>
                    {charged.map(({ head, paise }) => {
                      const sum = plan.reduce((acc, row) => acc + (row[head.id] ?? 0), 0);
                      return (
                        <td key={head.id} className={`r num${sum !== paise ? " text-bad" : ""}`}>
                          {formatPaise(sum)}
                          <span className="block text-[12px] text-ink-3">
                            {t("fees.structure.of", { amount: formatPaise(paise) })}
                          </span>
                        </td>
                      );
                    })}
                    <td className="r num font-semibold">{formatPaise(total)}</td>
                  </tr>
                </tfoot>
              ) : null}
            </table>
          </div>
          {charged
            .map(({ head }) => errors[`sum.${head.id}`])
            .filter(Boolean)
            .map((message) => (
              <p key={message} className="field-error" role="alert">
                {message}
              </p>
            ))}
        </fieldset>

        {canManage ? (
          <div className="flex flex-wrap justify-end gap-2">
            <button type="submit" className="btn btn-primary" disabled={form.submitting}>
              {form.submitting
                ? t("common.saving")
                : status === "PUBLISHED"
                  ? t("fees.structure.saveAndUpdate")
                  : t("fees.structure.saveDraft")}
            </button>
          </div>
        ) : null}
      </form>

      <ConfirmDialog
        open={publishing}
        title={t("fees.structure.publishTitle", { name: className })}
        body={t("fees.structure.publishBody", { name: className, year: yearName })}
        confirmLabel={t("fees.structure.publish")}
        danger={false}
        onConfirm={publish}
        onClose={() => setPublishing(false)}
      />
    </section>
  );
}
