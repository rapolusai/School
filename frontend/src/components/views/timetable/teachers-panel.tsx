"use client";

import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import type { BellSchedule, TeacherTimetable, WeekDay } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ClashList } from "./clashes-panel";
import { cellKey, periodsOf, workingDays } from "./timetable-logic";
import { DAY_LABEL, DayPeriods, SlotChip, subjectColours, WeekGrid } from "./timetable-shared";

/** A teacher's week: the section, subject and room of each period. */
export function TeacherWeek({ view, today }: { view: TeacherTimetable; today?: WeekDay }) {
  const { t } = useI18n();
  const byKey = new Map<string, TeacherTimetable["slots"]>();
  for (const slot of view.slots) {
    const key = cellKey(slot.day, slot.period);
    byKey.set(key, [...(byKey.get(key) ?? []), slot]);
  }
  // One colour per section: a teacher usually has one or two subjects but many classes.
  const colours = subjectColours(view.slots.map((s) => s.sectionLabel));
  return (
    <WeekGrid
      bells={view.bells}
      today={today}
      caption={t("timetable.teacherCaption", { teacher: view.teacherName })}
      testId="teacher-grid"
      cell={(day, period) => {
        const slots = byKey.get(cellKey(day, period));
        if (!slots) return null;
        return (
          <span className="flex flex-col gap-1">
            {slots.map((s) => (
              <SlotChip
                key={s.sectionId}
                title={s.sectionLabel}
                sub={s.subjectName}
                room={s.room}
                tone={slots.length > 1 ? "bad" : undefined}
                colour={colours.get(s.sectionLabel)}
              />
            ))}
          </span>
        );
      }}
    />
  );
}

/** Who is free in a period of a weekday (from the weekly timetable). */
function FreeFinder({ bells }: { bells: BellSchedule }) {
  const { t } = useI18n();
  const days = workingDays(bells);
  const [day, setDay] = useState<WeekDay>(days[0] ?? "MONDAY");
  const periods = periodsOf(bells, day).filter((p) => p.number !== null && !p.breakTime);
  const [period, setPeriod] = useState(1);
  const chosenPeriod = periods.some((p) => p.number === period) ? period : (periods[0]?.number ?? 1);
  const free = useApiData(days.length ? `timetable:free:${day}:${chosenPeriod}` : null, () =>
    timetableApi.freeTeachers({ day, period: chosenPeriod }),
  );
  return (
    <section className="border-t border-line pt-4" aria-labelledby="tt-free">
      <div className="card-head">
        <h2 id="tt-free">{t("timetable.free.title")}</h2>
      </div>
      <div className="toolbar">
        <label className="field">
          <span className="field-label">{t("timetable.dayLabel")}</span>
          <select className="input" value={day} onChange={(e) => setDay(e.target.value as WeekDay)}>
            {days.map((d) => (
              <option key={d} value={d}>
                {t(DAY_LABEL[d])}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span className="field-label">{t("timetable.period")}</span>
          <select className="input" value={chosenPeriod} onChange={(e) => setPeriod(Number(e.target.value))}>
            {periods.map((p) => (
              <option key={p.number} value={p.number ?? 0}>
                {`${p.label} · ${p.startsAt}`}
              </option>
            ))}
          </select>
        </label>
      </div>
      {free.error && !free.data ? (
        <ErrorState error={free.error} onRetry={free.reload} />
      ) : !free.data ? (
        <LoadingRows rows={2} />
      ) : free.data.teachers.length === 0 ? (
        <p className="text-sm text-ink-2">{t("timetable.free.none")}</p>
      ) : (
        <ul className="flex flex-wrap gap-2" data-testid="free-teachers">
          {free.data.teachers.map((teacher) => (
            <li key={teacher.id} className="chip">
              {teacher.name}
              <span className="text-ink-3">{plural(t, "timetable.free.periods", teacher.periodsThatDay)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Any teacher's week (and who is free when), for everyone with timetable.read. */
export function TeachersPanel() {
  const { t } = useI18n();
  const teachers = useApiData("timetable:teachers", timetableApi.teachers);
  const [chosen, setChosen] = useState("");
  const list = teachers.data ?? [];
  const teacherId = list.some((x) => x.id === chosen) ? chosen : (list[0]?.id ?? "");
  const week = useApiData(teacherId ? `timetable:teacher:${teacherId}` : null, () => timetableApi.teacher(teacherId));
  const view = week.data?.teacherId === teacherId ? week.data : undefined;

  if (teachers.error && !teachers.data) return <ErrorState error={teachers.error} onRetry={teachers.reload} />;
  if (!teachers.data) return <LoadingRows rows={6} />;
  if (list.length === 0) return <p className="empty">{t("timetable.noTeachers")}</p>;

  return (
    <div className="flex flex-col gap-3">
      <div className="toolbar">
        <label className="field min-w-0 flex-1 sm:max-w-sm">
          <span className="field-label">{t("timetable.teacher")}</span>
          <select className="input" name="teacher" value={teacherId} onChange={(e) => setChosen(e.target.value)}>
            {list.map((x) => (
              <option key={x.id} value={x.id}>
                {`${x.name} · ${plural(t, "timetable.periodsWeek", x.scheduled)}`}
              </option>
            ))}
          </select>
        </label>
      </div>
      {week.error && !view ? (
        <ErrorState error={week.error} onRetry={week.reload} />
      ) : !view ? (
        <LoadingRows rows={6} />
      ) : (
        <section aria-labelledby="tt-teacher" className="flex flex-col gap-3">
          <div className="card-head" style={{ marginBottom: 0 }}>
            <div className="min-w-0">
              <h2 id="tt-teacher">{view.teacherName}</h2>
              <p className="mt-1 text-[13px] text-ink-3">
                {[
                  plural(t, "timetable.periodsWeek", view.slots.length),
                  list.find((x) => x.id === teacherId)?.subjects.join(", "),
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
            </div>
            {view.clashes.length > 0 ? (
              <Pill tone="bad" dot>
                {plural(t, "timetable.clashCount", view.clashes.length)}
              </Pill>
            ) : null}
          </div>
          {view.clashes.length > 0 ? <ClashList clashes={view.clashes} compact /> : null}
          <TeacherWeek view={view} />
        </section>
      )}
      {view ? <FreeFinder bells={view.bells} /> : null}
    </div>
  );
}

/** The signed-in teacher's day (substitutions included) and week. */
export function MyTimetablePanel() {
  const { t, lang } = useI18n();
  const day = useApiData("timetable:me:today", () => timetableApi.myDay());
  const week = useApiData("timetable:me", timetableApi.mine);
  return (
    <div className="flex flex-col gap-3.5">
      <section className="card" aria-labelledby="tt-my-day">
        <div className="card-head">
          <div className="min-w-0">
            <h2 id="tt-my-day">{t("timetable.mine.today")}</h2>
            {day.data ? (
              <p className="mt-1 text-[13px] text-ink-3">
                {`${t(DAY_LABEL[day.data.day])}, ${formatPlainDate(day.data.date, localeFor(lang))}`}
              </p>
            ) : null}
          </div>
          {day.data?.absent ? <Pill tone="warn">{t("timetable.mine.away")}</Pill> : null}
        </div>
        {day.error && !day.data ? (
          <ErrorState error={day.error} onRetry={day.reload} />
        ) : !day.data ? (
          <LoadingRows rows={4} />
        ) : !day.data.workingDay ? (
          <p className="text-sm text-ink-2">{t("timetable.closedToday")}</p>
        ) : (
          <DayPeriods
            periods={day.data.periods}
            showTeacher={false}
            emptyLabel={t("timetable.mine.noClasses")}
            testId="my-day"
          />
        )}
      </section>
      <section className="card" aria-labelledby="tt-my-week">
        <div className="card-head">
          <h2 id="tt-my-week">{t("timetable.mine.week")}</h2>
          {week.data ? (
            <span className="text-[13px] text-ink-3">{plural(t, "timetable.periodsWeek", week.data.slots.length)}</span>
          ) : null}
        </div>
        {week.error && !week.data ? (
          <ErrorState error={week.error} onRetry={week.reload} />
        ) : !week.data ? (
          <LoadingRows rows={6} />
        ) : week.data.slots.length === 0 ? (
          <p className="text-sm text-ink-2">{t("timetable.mine.empty")}</p>
        ) : (
          <TeacherWeek view={week.data} today={day.data?.day} />
        )}
      </section>
    </div>
  );
}
