import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ExamClassView } from "@/components/views/exams/exam-class-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Exam" };

export default async function Page({ params }: PageProps<"/app/exams/[examId]/[classId]">) {
  const { examId, classId } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.examsRead}>
      <ExamClassView key={`${examId}:${classId}`} examId={examId} classId={classId} />
    </RequirePermission>
  );
}
