"use client";

import { ArrowRight, NotebookPen } from "lucide-react";
import Link from "next/link";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { api } from "@/lib/api";
import { homeworkApi } from "@/lib/homework-api";
import { plural, useI18n } from "@/lib/i18n";
import type { Child, StudentHomework } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { learnerGroup, LearnerHomeworkRows } from "./learner-homework";

/** How many items a dashboard card lists before "All homework". */
const CARD_ROWS = 4;

function DueCard({
  id,
  title,
  data,
  allHref,
  hrefFor,
  testId,
  timetableHref,
}: {
  id: string;
  title: string;
  data: ApiData<StudentHomework>;
  allHref: string;
  hrefFor: (homeworkId: string) => string;
  testId: string;
  /** A parent's way to the child's timetable (students have their own card). */
  timetableHref?: string;
}) {
  const { t } = useI18n();
  const view = data.data;
  const todo = view ? learnerGroup(view.items, "todo") : [];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby={id} data-testid={testId}>
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2 id={id}>{title}</h2>
          {view ? <p className="mt-1 text-[13px] text-ink-3">{plural(t, "homework.card.due", todo.length)}</p> : null}
        </div>
        <NotebookPen size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={3} />
      ) : todo.length === 0 ? (
        <p className="text-sm text-ink-2">{t("homework.card.nothingDue")}</p>
      ) : (
        <LearnerHomeworkRows items={todo.slice(0, CARD_ROWS)} today={view.today} hrefFor={(row) => hrefFor(row.id)} />
      )}
      <div className="mt-auto flex flex-wrap gap-x-5 gap-y-1">
        <Link href={allHref} className="link inline-flex items-center gap-1 text-[13.5px]">
          {t("homework.card.all")}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
        {timetableHref ? (
          <Link href={timetableHref} className="link inline-flex items-center gap-1 text-[13.5px]">
            {t("timetable.open")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        ) : null}
      </div>
    </section>
  );
}

/** Student dashboard: homework still to hand in, soonest first. */
export function StudentHomeworkCard() {
  const { t } = useI18n();
  const data = useApiData("homework:mine", homeworkApi.mine);
  if (data.error?.status === 404) return null;
  return (
    <DueCard
      id="hw-student-due"
      title={t("homework.card.title")}
      data={data}
      allHref="/app/homework"
      hrefFor={(homeworkId) => `/app/homework/${encodeURIComponent(homeworkId)}`}
      testId="student-homework-due"
    />
  );
}

function ChildDueCard({ child }: { child: Child }) {
  const { t } = useI18n();
  const data = useApiData(`homework:child:${child.id}`, () => homeworkApi.child(child.id));
  const query = `?child=${encodeURIComponent(child.id)}`;
  return (
    <DueCard
      id={`hw-child-due-${child.id}`}
      title={t("homework.card.childTitle", { name: child.fullName })}
      data={data}
      allHref={`/app/homework${query}`}
      hrefFor={(homeworkId) => `/app/homework/${encodeURIComponent(homeworkId)}${query}`}
      testId="child-homework-due"
      timetableHref={`/app/timetable${query}`}
    />
  );
}

/** Parent dashboard: one "Homework due" card per child still at school. */
export function ChildrenHomeworkCards() {
  const children = useApiData("me:children", api.myChildren);
  const list = (children.data ?? []).filter((c) => c.status === "ACTIVE" && c.sectionName);
  if (list.length === 0) return null;
  return (
    <div className="grid grid-cols-1 gap-3.5 md:grid-cols-2 xl:grid-cols-3">
      {list.map((child) => (
        <ChildDueCard key={child.id} child={child} />
      ))}
    </div>
  );
}
