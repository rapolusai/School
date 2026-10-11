import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { TestsListView } from "@/components/views/lessons/tests-view";

export const metadata: Metadata = { title: "Online tests" };

export default function Page() {
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <TestsListView />
    </RequireAny>
  );
}
