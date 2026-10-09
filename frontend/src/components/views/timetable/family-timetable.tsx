"use client";

import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { api } from "@/lib/api";
import { formatLongDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import type { FamilyTimetable } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { cellKey } from "./timetable-logic";
import { DayPeriods, SlotChip, WeekGrid } from "./timetable-shared";

/** A student's section timetable: today first, then the week. Read only. */
export function FamilyTimetableBody({ data }: { data: ApiData<FamilyTimetable> }) {
  const { t, lang } = useI18n();
  const view = data.data;
  if (data.error?.status === 404 && !view) return <p className="empty">{t("children.notLinked")}</p>;
  if (data.error && !view) return <ErrorState error={data.error} onRetry={data.reload} />;
  if (!view) return <LoadingRows rows={6} />;
  if (!view.sectionId) return <p className="empty">{t("timetable.family.noSection")}</p>;
  const byKey = new Map(view.slots.map((s) => [cellKey(s.day, s.period), s]));
  return (
    <div className="flex flex-col gap-3.5" data-testid="family-timetable">
      <section className="card" aria-labelledby="tt-family-today">
        <div className="card-head">
          <div className="min-w-0">
            <h2 id="tt-family-today">{t("timetable.family.today")}</h2>
            <p className="mt-1 text-[13px] text-ink-3">
              {formatLongDate(`${view.today}T12:00:00+05:30`, localeFor(lang))}
            </p>
          </div>
        </div>
        {!view.workingDay ? (
          <p className="text-sm text-ink-2">{t("timetable.closedToday")}</p>
        ) : (
          <DayPeriods periods={view.todayPeriods} emptyLabel={t("timetable.family.noClasses")} />
        )}
      </section>
      <section className="card" aria-labelledby="tt-family-week">
        <div className="card-head">
          <h2 id="tt-family-week">{t("timetable.family.week", { section: view.sectionLabel ?? "" })}</h2>
        </div>
        {view.slots.length === 0 ? (
          <p className="text-sm text-ink-2">{t("timetable.family.empty")}</p>
        ) : (
          <WeekGrid
            bells={view.bells}
            today={view.todayDay}
            caption={t("timetable.sectionCaption", { section: view.sectionLabel ?? "" })}
            cell={(day, period) => {
              const slot = byKey.get(cellKey(day, period));
              return slot ? (
                <SlotChip title={slot.subjectName} sub={slot.teacherName} room={slot.room} colourKey={slot.subjectId} />
              ) : null;
            }}
          />
        )}
      </section>
    </div>
  );
}

/** /app/timetable for a student. */
export function StudentTimetableView() {
  const { t } = useI18n();
  const data = useApiData("timetable:family:me", timetableApi.myTimetable);
  return (
    <>
      <PageHead eyebrow={data.data?.sectionLabel ?? undefined} title={t("timetable.title")} />
      <FamilyTimetableBody data={data} />
    </>
  );
}

function ChildTimetable({ studentId }: { studentId: string }) {
  const data = useApiData(`timetable:family:${studentId}`, () => timetableApi.childTimetable(studentId));
  return <FamilyTimetableBody data={data} />;
}

/** /app/timetable for a parent: one child at a time. */
export function ParentTimetableView({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const children = useApiData("me:children", api.myChildren);
  const [chosen, setChosen] = useState(initialChildId ?? "");
  const list = children.data ?? [];
  const childId = list.some((c) => c.id === chosen) ? chosen : (list[0]?.id ?? "");
  return (
    <>
      <PageHead title={t("timetable.title")} />
      {children.error && !children.data ? (
        <ErrorState error={children.error} onRetry={children.reload} />
      ) : !children.data ? (
        <LoadingRows rows={4} />
      ) : list.length === 0 ? (
        <p className="empty">{t("children.empty")}</p>
      ) : (
        <>
          {list.length > 1 ? (
            <div className="toolbar">
              <label className="field min-w-0 flex-1 sm:max-w-xs">
                <span className="field-label">{t("timetable.family.child")}</span>
                <select className="input" value={childId} onChange={(e) => setChosen(e.target.value)}>
                  {list.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.fullName}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          ) : null}
          <ChildTimetable key={childId} studentId={childId} />
        </>
      )}
    </>
  );
}
