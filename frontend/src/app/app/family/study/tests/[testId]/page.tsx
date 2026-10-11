import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TakeTestView } from "@/components/views/lessons/take-test";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Online test" };

/** A student's online test: start, answer one question per screen, submit, and see the released score. */
export default async function Page({ params }: PageProps<"/app/family/study/tests/[testId]">) {
  const { testId } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <TakeTestView key={testId} testId={testId} />
    </RequirePermission>
  );
}
