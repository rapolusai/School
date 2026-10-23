import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StudentsView } from "@/components/views/govreport/students-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Government fields" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <StudentsView />
    </RequirePermission>
  );
}
