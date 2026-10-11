import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { LessonsHomeView } from "@/components/views/lessons/plans-view";

export const metadata: Metadata = { title: "Lesson plans" };

/** Lesson plans week by week (docs/api/phase-2-lessons.md): teachers plan, approvers and readers look on. */
export default function Page() {
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <LessonsHomeView />
    </RequireAny>
  );
}
