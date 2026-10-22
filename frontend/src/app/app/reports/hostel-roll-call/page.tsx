import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HostelRollCallReportView } from "@/components/views/reports/hostel-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Roll call summary report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.hostelRead}>
      <HostelRollCallReportView />
    </RequirePermission>
  );
}
