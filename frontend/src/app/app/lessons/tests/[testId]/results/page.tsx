import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { TestResultsView } from "@/components/views/lessons/test-results-view";

export const metadata: Metadata = { title: "Test results" };

export default async function Page({ params }: PageProps<"/app/lessons/tests/[testId]/results">) {
  const { testId } = await params;
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <TestResultsView key={testId} testId={testId} />
    </RequireAny>
  );
}
