import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StudentsView } from "@/components/views/students/students-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Students" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.studentsRead}>
      <StudentsView />
    </RequirePermission>
  );
}
