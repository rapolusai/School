import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { SyllabusListView } from "@/components/views/lessons/syllabus-view";

export const metadata: Metadata = { title: "Syllabus" };

export default function Page() {
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <SyllabusListView />
    </RequireAny>
  );
}
