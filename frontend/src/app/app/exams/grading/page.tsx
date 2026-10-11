import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { GradingView } from "@/components/views/exams/grading-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Grading schemes" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.examsRead}>
      <GradingView />
    </RequirePermission>
  );
}
