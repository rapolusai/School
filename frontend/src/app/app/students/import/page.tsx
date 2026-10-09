import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StudentImportView } from "@/components/views/students/student-import-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Import students" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.studentsManage}>
      <StudentImportView />
    </RequirePermission>
  );
}
