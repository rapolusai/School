import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { PlanDetailView } from "@/components/views/lessons/plan-detail-view";

export const metadata: Metadata = { title: "Lesson plan" };

export default async function Page({ params }: PageProps<"/app/lessons/plans/[planId]">) {
  const { planId } = await params;
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <PlanDetailView key={planId} planId={planId} />
    </RequireAny>
  );
}
