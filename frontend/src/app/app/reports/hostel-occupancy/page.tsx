import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HostelOccupancyReportView } from "@/components/views/reports/hostel-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Hostel occupancy report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.hostelRead}>
      <HostelOccupancyReportView />
    </RequirePermission>
  );
}
