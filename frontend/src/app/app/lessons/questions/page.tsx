import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { QuestionBankView } from "@/components/views/lessons/question-bank-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Question bank" };

/** The question bank is for staff who manage lessons (docs/api/phase-2-lessons.md). */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.lessonsManage}>
      <QuestionBankView />
    </RequirePermission>
  );
}
