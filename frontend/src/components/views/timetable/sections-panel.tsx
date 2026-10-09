"use client";

import { TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { plural, useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import type { SectionSummary, SectionTimetable } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ClashList } from "./clashes-panel";
import { SectionEditor, SectionGrid } from "./section-editor";

function byClass(sections: SectionSummary[]) {
  const groups: { classId: string; className: string; sections: SectionSummary[] }[] = [];
  for (const section of sections) {
    const last = groups[groups.length - 1];
    if (last && last.classId === section.classId) last.sections.push(section);
    else groups.push({ classId: section.classId, className: section.className, sections: [section] });
  }
  return groups;
}

/** Section timetables: pick a class and section; managers edit the grid, everyone else reads it. */
export function SectionsPanel({
  initialSectionId,
  onOpenBells,
}: {
  initialSectionId?: string;
  onOpenBells?: () => void;
}) {
  const { t } = useI18n();
  const overview = useApiData("timetable:sections", timetableApi.sections);
  const [chosen, setChosen] = useState(initialSectionId ?? "");
  const [saved, setSaved] = useState<{ view: SectionTimetable; version: number } | null>(null);
  const list = overview.data?.sections ?? [];
  const sectionId = list.some((s) => s.sectionId === chosen) ? chosen : (list[0]?.sectionId ?? "");
  const section = useApiData(sectionId ? `timetable:section:${sectionId}` : null, () => timetableApi.section(sectionId));
  const loaded = section.data?.sectionId === sectionId ? section.data : undefined;
  const view = saved && saved.view.sectionId === sectionId ? saved.view : loaded;
  const summary = list.find((s) => s.sectionId === sectionId);

  if (overview.error && !overview.data) return <ErrorState error={overview.error} onRetry={overview.reload} />;
  if (!overview.data) return <LoadingRows rows={6} />;
  const data = overview.data;
  if (!data.academicYear) return <p className="empty">{t("timetable.noYear")}</p>;
  if (data.bells.weekday.length === 0) {
    return (
      <div className="empty flex flex-col items-center gap-3">
        <p>{t("timetable.noBells")}</p>
        {data.canEdit && onOpenBells ? (
          <button type="button" className="btn" onClick={onOpenBells}>
            {t("timetable.tab.bells")}
          </button>
        ) : null}
      </div>
    );
  }
  if (list.length === 0) return <p className="empty">{t("timetable.noSections")}</p>;

  return (
    <div className="flex flex-col gap-3">
      <div className="toolbar">
        <label className="field min-w-0 flex-1 sm:max-w-sm">
          <span className="field-label">{t("timetable.section")}</span>
          <select
            className="input"
            name="section"
            value={sectionId}
            onChange={(e) => {
              setChosen(e.target.value);
              setSaved(null);
            }}
          >
            {byClass(list).map((group) => (
              <optgroup key={group.classId} label={group.className}>
                {group.sections.map((s) => (
                  <option key={s.sectionId} value={s.sectionId}>
                    {`${s.label} · ${s.scheduled}/${s.cells}${s.clashes > 0 ? ` · ${t("timetable.clash")}` : ""}`}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>
        {data.clashes > 0 ? (
          <span className="sm:ml-auto sm:self-end">
            <Pill tone="bad" dot>
              {plural(t, "timetable.schoolClashes", data.clashes)}
            </Pill>
          </span>
        ) : null}
      </div>

      {section.error && !view ? (
        <ErrorState error={section.error} onRetry={section.reload} />
      ) : !view ? (
        <LoadingRows rows={8} />
      ) : (
        <section aria-labelledby="tt-section-title" className="flex flex-col gap-3">
          <div className="card-head" style={{ marginBottom: 0 }}>
            <div className="min-w-0">
              <h2 id="tt-section-title">{view.label}</h2>
              <p className="mt-1 text-[13px] text-ink-3" data-testid="section-meta">
                {[
                  view.classTeacherName ? t("timetable.classTeacher", { name: view.classTeacherName }) : null,
                  summary ? t("timetable.filled", { filled: view.slots.length, total: summary.cells }) : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
            </div>
            {view.clashes.length > 0 ? (
              <Pill tone="bad" dot>
                {plural(t, "timetable.clashCount", view.clashes.length)}
              </Pill>
            ) : (
              <Pill tone="good" dot>
                {t("timetable.noClash")}
              </Pill>
            )}
          </div>
          {view.clashes.length > 0 ? (
            <div className="alert alert-bad" role="status">
              <TriangleAlert size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <div className="min-w-0">
                <p className="font-semibold">{t("timetable.sectionClashes")}</p>
                <ClashList clashes={view.clashes} compact />
              </div>
            </div>
          ) : null}
          {view.canEdit ? (
            <SectionEditor
              key={`${view.sectionId}:${saved?.version ?? 0}`}
              view={view}
              onSaved={(next) => {
                setSaved((prev) => ({ view: next, version: (prev?.version ?? 0) + 1 }));
                overview.reload();
              }}
            />
          ) : (
            <SectionGrid view={view} />
          )}
        </section>
      )}
    </div>
  );
}
