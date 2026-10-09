import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { DepartmentsView } from "@/components/views/staff/departments-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Departments" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.staffRead}>
      <DepartmentsView />
    </RequirePermission>
  );
}
