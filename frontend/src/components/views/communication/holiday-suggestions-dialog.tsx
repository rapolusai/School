"use client";

import { TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { calendarApi } from "@/lib/communication-api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import type { EntryRequest, HolidaySuggestion, HolidaySuggestions } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { isPlainDate } from "@/lib/validation";
import { MAX_TITLE } from "./communication-labels";

export type SuggestionRow = HolidaySuggestion & { key: string; selected: boolean; editDate: string; editTitle: string };

/** National holidays start ticked; festivals start unticked until the admin has checked their dates. */
export function suggestionRows(items: HolidaySuggestion[]): SuggestionRow[] {
  return items.map((item, index) => ({
    ...item,
    key: `${index}:${item.date}`,
    selected: !item.alreadyAdded && !item.needsConfirmation,
    editDate: item.date,
    editTitle: item.title,
  }));
}

/** The holidays to add, or the problem with the choice. Exported for tests. */
export function chosenHolidays(
  rows: SuggestionRow[],
  confirmed: boolean,
): { entries: EntryRequest[]; problem: "none" | "confirm" | "invalid" | null } {
  const chosen = rows.filter((r) => r.selected && !r.alreadyAdded);
  if (chosen.length === 0) return { entries: [], problem: "none" };
  if (chosen.some((r) => !isPlainDate(r.editDate) || !r.editTitle.trim() || r.editTitle.trim().length > MAX_TITLE)) {
    return { entries: [], problem: "invalid" };
  }
  if (chosen.some((r) => r.needsConfirmation) && !confirmed) return { entries: [], problem: "confirm" };
  return {
    entries: chosen.map((r) => ({
      kind: "HOLIDAY",
      title: r.editTitle.trim(),
      startsOn: r.editDate,
      audience: "SCHOOL",
    })),
    problem: null,
  };
}

/**
 * The holiday starter list for the current academic year. Nothing is added until the admin picks holidays and, for
 * festivals, confirms the dates against their state's list.
 */
export function HolidaySuggestionsDialog({
  open,
  onClose,
  onAdded,
}: {
  open: boolean;
  onClose: () => void;
  onAdded: (count: number) => void;
}) {
  const { t } = useI18n();
  const suggestions = useApiData(open ? "calendar:suggestions" : null, calendarApi.holidaySuggestions);

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t("calendar.starter.title")}
      description={suggestions.data ? t("calendar.starter.year", { year: suggestions.data.academicYearName }) : undefined}
      closeLabel={t("common.close")}
    >
      {suggestions.error && !suggestions.data ? (
        <ErrorState error={suggestions.error} onRetry={suggestions.reload} />
      ) : !suggestions.data ? (
        <LoadingRows rows={5} />
      ) : (
        <SuggestionForm key={suggestions.data.academicYearId} data={suggestions.data} onClose={onClose} onAdded={onAdded} />
      )}
    </Dialog>
  );
}

function SuggestionForm({
  data,
  onClose,
  onAdded,
}: {
  data: HolidaySuggestions;
  onClose: () => void;
  onAdded: (count: number) => void;
}) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [rows, setRows] = useState(() => suggestionRows(data.items));
  const [confirmed, setConfirmed] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const selected = rows.filter((r) => r.selected && !r.alreadyAdded);
  const festivalsChosen = selected.some((r) => r.needsConfirmation);

  const update = (key: string, patch: Partial<SuggestionRow>) => {
    setProblem(null);
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  };

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    const result = chosenHolidays(rows, confirmed);
    if (result.problem) {
      setProblem(
        result.problem === "none"
          ? t("calendar.starter.v.none")
          : result.problem === "confirm"
            ? t("calendar.starter.v.confirm")
            : t("calendar.starter.v.invalid"),
      );
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const added = await calendarApi.createAll(result.entries);
      onAdded(added.length);
    } catch (caught) {
      setProblem(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
      <div className="alert alert-info">
        <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
        <span>{t("calendar.starter.note")}</span>
      </div>
      <FormAlert message={problem} />
      {rows.length === 0 ? (
        <p className="empty">{t("calendar.starter.empty")}</p>
      ) : (
        <ul className="starter-list" data-testid="starter-list">
          {rows.map((row) => (
            <li key={row.key} className="starter-row" data-added={row.alreadyAdded ? "true" : undefined}>
              <input
                type="checkbox"
                className="starter-check"
                name="holidays"
                checked={row.selected && !row.alreadyAdded}
                disabled={row.alreadyAdded}
                onChange={(e) => update(row.key, { selected: e.target.checked })}
                aria-label={`${row.title}, ${formatPlainDate(row.date, locale)}`}
              />
              <input
                className="input starter-title"
                name="title"
                value={row.editTitle}
                maxLength={MAX_TITLE}
                disabled={row.alreadyAdded}
                onChange={(e) => update(row.key, { editTitle: e.target.value })}
                aria-label={t("calendar.starter.titleOf", { title: row.title })}
              />
              <input
                type="date"
                className="input starter-date"
                name="date"
                value={row.editDate}
                disabled={row.alreadyAdded}
                onChange={(e) => update(row.key, { editDate: e.target.value })}
                aria-label={t("calendar.starter.dateOf", { title: row.title })}
              />
              <span className="starter-tag">
                {row.alreadyAdded ? (
                  <Pill tone="good">{t("calendar.starter.added")}</Pill>
                ) : row.needsConfirmation ? (
                  <Pill tone="warn">{t("calendar.starter.checkDate")}</Pill>
                ) : (
                  <Pill tone="info">{t("calendar.starter.national")}</Pill>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {festivalsChosen ? (
        <label className="check">
          <input
            type="checkbox"
            name="confirmed"
            checked={confirmed}
            onChange={(e) => {
              setConfirmed(e.target.checked);
              setProblem(null);
            }}
          />
          <span>{t("calendar.starter.confirm")}</span>
        </label>
      ) : null}
      <div className="flex flex-wrap items-center justify-end gap-2 pt-1">
        <span className="mr-auto text-[13px] text-ink-3">{plural(t, "calendar.starter.selected", selected.length)}</span>
        <button type="button" className="btn" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t("common.working") : t("calendar.starter.add")}
        </button>
      </div>
    </form>
  );
}
