import type { Metadata } from "next";
import { MARKS_PERMISSIONS, RequireAny } from "@/components/views/exams/exams-shared";
import { MarksGridView } from "@/components/views/exams/marks-grid";

export const metadata: Metadata = { title: "Marks entry" };

export default async function Page({ params }: PageProps<"/app/exams/marks/[paperId]/[sectionId]">) {
  const { paperId, sectionId } = await params;
  return (
    <RequireAny permissions={MARKS_PERMISSIONS}>
      <MarksGridView key={`${paperId}:${sectionId}`} paperId={paperId} sectionId={sectionId} />
    </RequireAny>
  );
}
