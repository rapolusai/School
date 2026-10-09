"use client";

import { ArrowRight, CalendarDays } from "lucide-react";
import Link from "next/link";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import { useApiData } from "@/lib/use-api-data";
import { DayPeriods } from "./timetable-shared";

function CardShell({
  id,
  title,
  testId,
  children,
}: {
  id: string;
  title: string;
  testId: string;
  children: React.ReactNode;
}) {
  const { t } = useI18n();
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={id} data-testid={testId}>
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id={id}>{title}</h2>
        <CalendarDays size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {children}
      <Link href="/app/timetable" className="link mt-auto inline-flex items-center gap-1 text-[13.5px]">
        {t("timetable.open")}
        <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </section>
  );
}

/** Teacher dashboard: today's classes, with any substitutions they cover. */
export function TodayClassesCard() {
  const { t } = useI18n();
  const day = useApiData("timetable:me:today", () => timetableApi.myDay());
  const data = day.data;
  return (
    <CardShell id="tt-today-classes" title={t("timetable.card.todayClasses")} testId="today-classes">
      {day.error && !data ? (
        <ErrorState error={day.error} onRetry={day.reload} />
      ) : !data ? (
        <LoadingRows rows={3} />
      ) : !data.workingDay ? (
        <p className="text-sm text-ink-2">{t("timetable.closedToday")}</p>
      ) : (
        <>
          {data.absent ? <Pill tone="warn">{t("timetable.mine.away")}</Pill> : null}
          <DayPeriods
            periods={data.periods}
            showBreaks={false}
            showTeacher={false}
            emptyLabel={t("timetable.mine.noClasses")}
            testId="today-classes-list"
          />
        </>
      )}
    </CardShell>
  );
}

/** Student dashboard: today's timetable of their section (substitutions shown). */
export function StudentTodayCard() {
  const { t } = useI18n();
  const data = useApiData("timetable:family:me", timetableApi.myTimetable);
  const view = data.data;
  if (data.error?.status === 404) return null;
  return (
    <CardShell id="tt-student-today" title={t("timetable.card.studentToday")} testId="student-today">
      {data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={3} />
      ) : !view.sectionId ? (
        <p className="text-sm text-ink-2">{t("timetable.family.noSection")}</p>
      ) : !view.workingDay ? (
        <p className="text-sm text-ink-2">{t("timetable.closedToday")}</p>
      ) : (
        <DayPeriods
          periods={view.todayPeriods}
          showBreaks={false}
          showSection={false}
          emptyLabel={t("timetable.family.noClasses")}
        />
      )}
    </CardShell>
  );
}
