import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { TestBuilderView } from "@/components/views/lessons/test-builder-view";

export const metadata: Metadata = { title: "Online test" };

export default async function Page({ params }: PageProps<"/app/lessons/tests/[testId]">) {
  const { testId } = await params;
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <TestBuilderView key={testId} testId={testId} />
    </RequireAny>
  );
}
