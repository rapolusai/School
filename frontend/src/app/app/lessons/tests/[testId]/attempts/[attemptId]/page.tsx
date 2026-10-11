import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { AttemptMarkingView } from "@/components/views/lessons/test-results-view";

export const metadata: Metadata = { title: "Marking" };

export default async function Page({ params }: PageProps<"/app/lessons/tests/[testId]/attempts/[attemptId]">) {
  const { testId, attemptId } = await params;
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <AttemptMarkingView key={`${testId}:${attemptId}`} testId={testId} attemptId={attemptId} />
    </RequireAny>
  );
}
