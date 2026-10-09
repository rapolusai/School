"use client";

import { CircleCheck, RefreshCw, TriangleAlert } from "lucide-react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { plural, useI18n, type Translate } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import type { Clash, WeeklyWarning } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { cellName } from "./timetable-shared";

/** One sentence for a clash: who or which section, where, when. Exported for tests. */
export function clashText(t: Translate, clash: Clash): string {
  const when = cellName(t, clash.day, clash.period);
  if (clash.kind === "TEACHER") {
    return t("timetable.clash.teacher", {
      teacher: clash.teacherName ?? "—",
      sections: clash.entries.map((e) => `${e.sectionLabel} (${e.subjectName})`).join(", "),
      when,
    });
  }
  return t("timetable.clash.section", {
    section: clash.sectionLabel ?? "—",
    subjects: clash.entries.map((e) => e.subjectName).join(", "),
    when,
  });
}

export function ClashList({
  clashes,
  compact = false,
  onOpenSection,
}: {
  clashes: Clash[];
  compact?: boolean;
  onOpenSection?: (sectionId: string) => void;
}) {
  const { t } = useI18n();
  if (compact) {
    return (
      <ul className="mt-1 list-disc pl-5 text-[13.5px]">
        {clashes.map((c, i) => (
          <li key={i}>{clashText(t, c)}</li>
        ))}
      </ul>
    );
  }
  return (
    <ul className="list" data-testid="clash-list">
      {clashes.map((c, i) => {
        const sections = [...new Map(c.entries.map((e) => [e.sectionId, e.sectionLabel])).entries()];
        return (
          <li key={i} className="li items-start">
            <span className="badge-ic" style={{ background: "var(--bad-soft)", color: "var(--bad)" }}>
              <TriangleAlert size={18} aria-hidden="true" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-ink">{clashText(t, c)}</p>
              {onOpenSection ? (
                <div className="mt-1.5 flex flex-wrap gap-2">
                  {sections.map(([id, label]) => (
                    <button key={id} type="button" className="btn btn-sm" onClick={() => onOpenSection(id)}>
                      {t("timetable.clash.open", { section: label })}
                    </button>
                  ))}
                </div>
              ) : null}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

export function WarningList({ warnings }: { warnings: WeeklyWarning[] }) {
  const { t } = useI18n();
  return (
    <ul className="list" data-testid="warning-list">
      {warnings.map((w) => (
        <li key={`${w.sectionId}:${w.subjectId}`} className="li items-center">
          <span className="badge-ic" style={{ background: "var(--warn-soft)", color: "var(--warn)" }}>
            <TriangleAlert size={18} aria-hidden="true" />
          </span>
          <p className="min-w-0 flex-1 text-ink">
            {t("timetable.warning.overLimit", {
              section: w.sectionLabel,
              subject: w.subjectName,
              scheduled: w.scheduled,
              allowed: w.periodsPerWeek,
            })}
          </p>
        </li>
      ))}
    </ul>
  );
}

/** The whole-school check: every clash and every subject over its weekly allowance. */
export function ClashesPanel({ onOpenSection }: { onOpenSection: (sectionId: string) => void }) {
  const { t } = useI18n();
  const report = useApiData("timetable:clashes", timetableApi.clashes);
  const data = report.data;
  return (
    <div className="flex flex-col gap-3" data-testid="clash-report">
      <div className="toolbar" style={{ marginBottom: 0 }}>
        <p className="min-w-0 flex-1 text-[13.5px] text-ink-2">
          {data
            ? t("timetable.report.checked", { sections: data.sectionsChecked, periods: data.periodsChecked })
            : t("timetable.report.intro")}
        </p>
        <button type="button" className="btn" onClick={report.reload} disabled={report.loading}>
          <RefreshCw size={16} aria-hidden="true" />
          {report.loading ? t("common.working") : t("timetable.report.run")}
        </button>
      </div>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : !data.academicYear ? (
        <p className="empty">{t("timetable.noYear")}</p>
      ) : (
        <>
          {data.clashes.length === 0 ? (
            <div className="alert" style={{ background: "var(--good-soft)", color: "var(--good)" }}>
              <CircleCheck size={18} aria-hidden="true" className="mt-0.5 flex-none" />
              <span>{t("timetable.report.clean")}</span>
            </div>
          ) : (
            <section aria-labelledby="tt-clashes">
              <h2 id="tt-clashes" className="mb-1">
                {plural(t, "timetable.clashCount", data.clashes.length)}
              </h2>
              <ClashList clashes={data.clashes} onOpenSection={onOpenSection} />
            </section>
          )}
          {data.warnings.length > 0 ? (
            <section aria-labelledby="tt-warnings">
              <h2 id="tt-warnings" className="mb-1">
                {plural(t, "timetable.report.warnings", data.warnings.length)}
              </h2>
              <p className="text-[13px] text-ink-3">{t("timetable.report.warningsHint")}</p>
              <WarningList warnings={data.warnings} />
            </section>
          ) : null}
        </>
      )}
    </div>
  );
}
