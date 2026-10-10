import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LeaveReportView } from "@/components/views/reports/leave-report";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Leave taken" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.staffRead}>
      <LeaveReportView />
    </RequirePermission>
  );
}
