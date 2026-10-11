import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { SyllabusCoverageReportView } from "@/components/views/lessons/coverage-report";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";

export const metadata: Metadata = { title: "Syllabus coverage" };

export default function Page() {
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <SyllabusCoverageReportView />
    </RequireAny>
  );
}
