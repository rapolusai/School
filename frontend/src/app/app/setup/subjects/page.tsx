import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SubjectPlanView } from "@/components/views/setup/subject-plan-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Subjects by class" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.academicsRead}>
      <SubjectPlanView />
    </RequirePermission>
  );
}
