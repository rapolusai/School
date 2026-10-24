import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SchoolImportView } from "@/components/views/setup/school-import-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Bring your students and staff" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.studentsManage}>
      <SchoolImportView />
    </RequirePermission>
  );
}
