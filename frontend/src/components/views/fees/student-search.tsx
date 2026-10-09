"use client";

import { Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { ErrorState } from "@/components/ui/states";
import { feesApi } from "@/lib/fees-api";
import { classLabel, initials } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import type { StudentHit } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/**
 * Finds a student by name, admission number or parent's name (GET /api/fees/students?q=) and
 * hands the pick to `onPick`. Searches 250 ms after typing stops.
 */
export function StudentSearch({
  onPick,
  label,
  autoFocus = false,
}: {
  onPick: (student: StudentHit) => void;
  label?: string;
  autoFocus?: boolean;
}) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const q = query.trim();
  const results = useApiData(q ? `fees:search:${q}` : null, () => feesApi.searchStudents(q));

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const onChange = (value: string) => {
    setText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setQuery(value), 250);
  };

  const hits = results.data ?? [];
  const placeholder = label ?? t("fees.search.placeholder");

  return (
    <div className="flex flex-col gap-2">
      <label className="search max-w-none flex-none">
        <Search size={18} aria-hidden="true" />
        <span className="sr-only">{placeholder}</span>
        <input
          type="search"
          name="studentSearch"
          value={text}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          maxLength={100}
          autoComplete="off"
          autoFocus={autoFocus}
          data-autofocus={autoFocus ? true : undefined}
        />
      </label>
      {!q ? null : results.error && !results.data ? (
        <ErrorState error={results.error} onRetry={results.reload} />
      ) : results.loading && !results.data ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("common.loading")}
        </p>
      ) : hits.length === 0 ? (
        <p className="px-1 text-[13px] text-ink-3" role="status">
          {t("fees.search.none", { query: q })}
        </p>
      ) : (
        <ul className="pick-list" aria-label={t("fees.search.results")}>
          {hits.map((hit) => (
            <li key={hit.id}>
              <button type="button" className="pick" onClick={() => onPick(hit)}>
                <span className="avatar" aria-hidden="true">
                  {initials(hit.fullName)}
                </span>
                <span className="min-w-0 flex-1 text-left">
                  <b className="block truncate font-semibold">{hit.fullName}</b>
                  <span className="block truncate text-[12.5px] text-ink-3">
                    {[classLabel(hit.className, hit.sectionName), hit.admissionNo, hit.guardianName]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
