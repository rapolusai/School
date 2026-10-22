import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HostelFeeDuesReportView } from "@/components/views/reports/hostel-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Boarder fee dues report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.hostelRead}>
      <HostelFeeDuesReportView />
    </RequirePermission>
  );
}
