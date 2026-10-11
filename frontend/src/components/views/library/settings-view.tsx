"use client";

import { Pencil } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, parseRupees, rupeesInput } from "@/lib/format";
import { plural, useI18n } from "@/lib/i18n";
import { libraryApi } from "@/lib/library-api";
import type { BorrowingRule, LibrarySettings } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { ACCESSION_PREFIX_PATTERN, LibraryNav, memberTypeLabel } from "./library-shared";

/** /app/library/settings: accession numbers, the hold shelf, reminders to parents and the borrowing rules. */
export function LibrarySettingsView() {
  const { t } = useI18n();
  const settings = useApiData("library:settings", libraryApi.settings);
  const rules = useApiData("library:rules", libraryApi.rules);
  const [editing, setEditing] = useState<BorrowingRule | null>(null);
  const { toast } = useToast();

  return (
    <>
      <PageHead eyebrow={t("nav.library")} title={t("library.settings.title")} />
      <LibraryNav />
      <section className="card flex flex-col gap-3.5" aria-labelledby="library-settings">
        <h2 id="library-settings">{t("library.settings.general")}</h2>
        {settings.error && !settings.data ? (
          <ErrorState error={settings.error} onRetry={settings.reload} />
        ) : !settings.data ? (
          <LoadingRows rows={3} />
        ) : (
          <SettingsForm key={JSON.stringify(settings.data)} settings={settings.data} onSaved={settings.reload} />
        )}
      </section>

      <section className="card flex flex-col gap-3" aria-labelledby="library-rules">
        <h2 id="library-rules">{t("library.rules.title")}</h2>
        <p className="text-[13px] text-ink-2">{t("library.rules.intro")}</p>
        {rules.error && !rules.data ? (
          <ErrorState error={rules.error} onRetry={rules.reload} />
        ) : !rules.data ? (
          <LoadingRows rows={2} />
        ) : (
          <ul className="rowcards" data-testid="library-rules">
            {rules.data.map((rule) => (
              <li key={rule.memberType} className="rowcard flex-wrap items-start">
                <span className="min-w-0 flex-1">
                  <b className="block">
                    {memberTypeLabel(t, rule.memberType)}{" "}
                    {rule.custom ? null : <Pill>{t("library.rules.default")}</Pill>}
                  </b>
                  <span className="block text-[13px] text-ink-2">
                    {t("library.desk.rule", { books: rule.maxBooks, days: rule.loanDays })}{" "}
                    {plural(t, "library.desk.renewals", rule.maxRenewals)}
                  </span>
                  <span className="block text-[12.5px] text-ink-3">
                    {rule.finePerDayPaise > 0
                      ? t("library.rules.fine", {
                          amount: formatPaise(rule.finePerDayPaise),
                          max: rule.maxFinePaise > 0 ? formatPaise(rule.maxFinePaise) : t("library.rules.noCap"),
                        })
                      : t("library.rules.noFine")}
                  </span>
                </span>
                <button type="button" className="btn btn-sm" onClick={() => setEditing(rule)}>
                  <Pencil size={16} aria-hidden="true" />
                  {t("common.edit")}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {editing ? (
        <RuleDialog
          rule={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast(t("library.rules.saved"));
            rules.reload();
          }}
        />
      ) : null}
    </>
  );
}

type SettingsValues = { accessionPrefix: string; holdDays: string; remindersEnabled: boolean };

/** The same checks the API makes on the settings. Pure and exported for tests. */
export function validateSettings(values: SettingsValues): Problems {
  const problems: Problems = {};
  if (!ACCESSION_PREFIX_PATTERN.test(values.accessionPrefix.trim())) problems.accessionPrefix = "library.v.prefix";
  const days = values.holdDays.trim();
  if (!/^\d+$/.test(days) || Number(days) < 1 || Number(days) > 14) problems.holdDays = "library.v.holdDays";
  return problems;
}

function SettingsForm({ settings, onSaved }: { settings: LibrarySettings; onSaved: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<SettingsValues>({
    accessionPrefix: settings.accessionPrefix,
    holdDays: String(settings.holdDays),
    remindersEnabled: settings.remindersEnabled,
  });
  const { values, set, errors } = form;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateSettings(values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await libraryApi.saveSettings({
        accessionPrefix: values.accessionPrefix.trim(),
        holdDays: Number(values.holdDays.trim()),
        remindersEnabled: values.remindersEnabled,
      });
    });
    if (ok) {
      toast(t("library.settings.saved"));
      onSaved();
    }
  };

  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="library-settings-form">
      <FormAlert message={form.formError} />
      <div className="grid2">
        <TextField
          label={t("library.settings.prefix")}
          name="accessionPrefix"
          value={values.accessionPrefix}
          onChange={(e) => set("accessionPrefix", e.target.value.toUpperCase())}
          error={errors.accessionPrefix}
          hint={t("library.settings.prefixHint", { next: settings.nextAccessionNo })}
          maxLength={8}
          autoComplete="off"
        />
        <TextField
          label={t("library.settings.holdDays")}
          name="holdDays"
          inputMode="numeric"
          value={values.holdDays}
          onChange={(e) => set("holdDays", e.target.value)}
          error={errors.holdDays}
          hint={t("library.settings.holdDaysHint")}
          maxLength={2}
        />
      </div>
      <div className="field">
        <label className="check">
          <input
            type="checkbox"
            name="remindersEnabled"
            checked={values.remindersEnabled}
            onChange={(e) => set("remindersEnabled", e.target.checked)}
          />
          <span>{t("library.settings.reminders")}</span>
        </label>
        <p className="field-hint">{t("library.settings.remindersHint")}</p>
      </div>
      <div className="flex justify-end">
        <button type="submit" className="btn btn-primary" disabled={form.submitting}>
          {form.submitting ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

type RuleValues = { maxBooks: string; loanDays: string; maxRenewals: string; finePerDay: string; maxFine: string };

const inRange = (text: string, min: number, max: number) => /^\d+$/.test(text.trim()) && Number(text) >= min && Number(text) <= max;

/** The same checks the API makes on a borrowing rule. Pure and exported for tests. */
export function validateRule(values: RuleValues): Problems {
  const problems: Problems = {};
  if (!inRange(values.maxBooks, 1, 20)) problems.maxBooks = "library.v.maxBooks";
  if (!inRange(values.loanDays, 1, 180)) problems.loanDays = "library.v.loanDays";
  if (!inRange(values.maxRenewals, 0, 10)) problems.maxRenewals = "library.v.renewals";
  const perDay = parseRupees(values.finePerDay);
  if (perDay === null || perDay > 100_000) problems.finePerDayPaise = "library.v.finePerDay";
  const cap = parseRupees(values.maxFine);
  if (cap === null || cap > 10_000_000) problems.maxFinePaise = "library.v.maxFine";
  return problems;
}

function RuleDialog({ rule, onClose, onSaved }: { rule: BorrowingRule; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<RuleValues>({
    maxBooks: String(rule.maxBooks),
    loanDays: String(rule.loanDays),
    maxRenewals: String(rule.maxRenewals),
    finePerDay: rupeesInput(rule.finePerDayPaise),
    maxFine: rupeesInput(rule.maxFinePaise),
  });
  const { values, set, errors } = form;

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(validateRule(values), t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await libraryApi.saveRule(rule.memberType, {
        maxBooks: Number(values.maxBooks),
        loanDays: Number(values.loanDays),
        maxRenewals: Number(values.maxRenewals),
        finePerDayPaise: parseRupees(values.finePerDay) ?? 0,
        maxFinePaise: parseRupees(values.maxFine) ?? 0,
      });
    });
    if (ok) onSaved();
  };

  const field = (name: keyof RuleValues, apiName: string, label: string, hint?: string) => (
    <TextField
      label={label}
      name={apiName}
      inputMode="decimal"
      value={values[name]}
      onChange={(e) => set(name, e.target.value)}
      error={errors[apiName]}
      hint={hint}
      maxLength={10}
      autoComplete="off"
    />
  );

  return (
    <Dialog
      open
      onClose={onClose}
      title={t("library.rules.editTitle", { type: memberTypeLabel(t, rule.memberType) })}
      description={t("library.rules.editDescription")}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate data-testid="rule-form">
        <FormAlert message={form.formError} />
        <div className="grid2">
          {field("maxBooks", "maxBooks", t("library.rules.maxBooks"))}
          {field("loanDays", "loanDays", t("library.rules.loanDays"), t("library.rules.loanDaysHint"))}
        </div>
        <div className="grid2">
          {field("maxRenewals", "maxRenewals", t("library.rules.renewals"))}
          {field("finePerDay", "finePerDayPaise", t("library.rules.finePerDay"), t("library.rules.finePerDayHint"))}
        </div>
        {field("maxFine", "maxFinePaise", t("library.rules.maxFine"), t("library.rules.maxFineHint"))}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
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
