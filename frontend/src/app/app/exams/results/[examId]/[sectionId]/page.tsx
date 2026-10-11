import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SectionResultsView } from "@/components/views/exams/section-results";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Results" };

export default async function Page({ params }: PageProps<"/app/exams/results/[examId]/[sectionId]">) {
  const { examId, sectionId } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.examsRead}>
      <SectionResultsView key={`${examId}:${sectionId}`} examId={examId} sectionId={sectionId} />
    </RequirePermission>
  );
}
