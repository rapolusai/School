import type { Metadata } from "next";
import { MARKS_PERMISSIONS, RequireAny } from "@/components/views/exams/exams-shared";
import { TermRecordsView } from "@/components/views/exams/term-records";

export const metadata: Metadata = { title: "Term records" };

export default async function Page({ params }: PageProps<"/app/exams/records/[termId]/[sectionId]">) {
  const { termId, sectionId } = await params;
  return (
    <RequireAny permissions={MARKS_PERMISSIONS}>
      <TermRecordsView key={`${termId}:${sectionId}`} termId={termId} sectionId={sectionId} />
    </RequireAny>
  );
}
